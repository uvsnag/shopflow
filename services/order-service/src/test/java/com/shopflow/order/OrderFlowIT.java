package com.shopflow.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.outbox.OutboxPublisher;
import com.shopflow.common.outbox.OutboxRepository;
import com.shopflow.order.client.ProductClient;
import com.shopflow.order.domain.Order;
import com.shopflow.order.domain.OrderStatus;
import com.shopflow.order.repository.OrderRepository;
import com.shopflow.order.repository.OrderStatusHistoryRepository;
import com.shopflow.order.service.CartService;
import com.shopflow.order.service.OrderService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Integration test with real PostgreSQL + Kafka (Testcontainers):
 * Flyway runs, placing an order writes the outbox, the publisher sends the
 * event to Kafka, and the saga listener is idempotent. Skips itself when
 * Docker is not available (plain `mvn verify` on a laptop without Docker).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "shopflow.outbox.poll-ms=200",
        "shopflow.timeout-poll-ms=3600000",
        "management.tracing.enabled=false"
})
class OrderFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @Autowired OrderService orderService;
    @Autowired CartService cartService;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderStatusHistoryRepository historyRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean ProductClient productClient;

    @Test
    void orderCreatedReachesKafkaAndSagaRepliesAreIdempotent() throws Exception {
        when(productClient.getProduct(anyLong()))
                .thenReturn(new ProductClient.ProductInfo(1L, "Wireless Mouse", 250_000L, true));
        try (Consumer<String, String> consumer = consumer("it-" + UUID.randomUUID())) {
            consumer.subscribe(java.util.List.of(EventTypes.TOPIC_ORDER_EVENTS));

            // FR-07: place the order; data + event in one transaction (NFR-08)
            cartService.putItem(1L, 1L, 2);
            Order order = orderService.placeOrder(1L, "it-key-" + UUID.randomUUID());
            assertEquals(OrderStatus.PENDING, order.getStatus());
            assertEquals(500_000L, order.getTotalAmount());
            assertEquals(1, outboxRepository.findTop50BySentAtIsNullOrderByCreatedAtAsc().stream()
                    .filter(e -> e.getAggregateId().equals(String.valueOf(order.getId()))).count());

            // the scheduled publisher (poll-ms=200) moves it to Kafka
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer,
                    EventTypes.TOPIC_ORDER_EVENTS, Duration.ofSeconds(30));
            assertEquals(String.valueOf(order.getId()), record.key(), "key = orderId keeps per-order ordering");
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            assertEquals(EventTypes.ORDER_CREATED, envelope.eventType());
            assertEquals(1, ((Number) envelope.payload().get("userId")).intValue());

            // inventory replies StockReserved ... twice (redelivery). Processed once (NFR-09).
            EventEnvelope reply = new EventEnvelope(UUID.randomUUID(), EventTypes.STOCK_RESERVED, Instant.now(),
                    String.valueOf(order.getId()), Map.of());
            String replyJson = objectMapper.writeValueAsString(reply);
            kafkaTemplate.send(EventTypes.TOPIC_INVENTORY_EVENTS, String.valueOf(order.getId()), replyJson).get();
            kafkaTemplate.send(EventTypes.TOPIC_INVENTORY_EVENTS, String.valueOf(order.getId()), replyJson).get();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertEquals(OrderStatus.STOCK_RESERVED,
                            orderRepository.findById(order.getId()).orElseThrow().getStatus()));
            Thread.sleep(1500); // give the duplicate time to (not) do anything
            assertEquals(2, historyRepository.findByOrderIdOrderByChangedAtAsc(order.getId()).size(),
                    "PENDING + STOCK_RESERVED only: the duplicate event added nothing");
        }
    }

    private Consumer<String, String> consumer(String group) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(kafka.getBootstrapServers(), group, "true");
        props.put("auto.offset.reset", "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}

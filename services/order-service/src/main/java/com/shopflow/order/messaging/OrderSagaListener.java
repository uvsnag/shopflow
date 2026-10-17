package com.shopflow.order.messaging;

import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventMapper;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.idempotency.IdempotencyGuard;
import com.shopflow.order.domain.OrderStatus;
import com.shopflow.order.service.OrderService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order side of the choreography saga: reacts to inventory and payment events
 * and moves the order through its status machine.
 *
 * Each handler is @Transactional so the processed_events mark (NFR-09) and the
 * status change commit together: a failure rolls back both and the Kafka retry
 * can process the event again instead of skipping it as a duplicate.
 */
@Component
public class OrderSagaListener {

    private final EventMapper eventMapper;
    private final IdempotencyGuard guard;
    private final OrderService orderService;

    public OrderSagaListener(EventMapper eventMapper, IdempotencyGuard guard, OrderService orderService) {
        this.eventMapper = eventMapper;
        this.guard = guard;
        this.orderService = orderService;
    }

    @KafkaListener(topics = EventTypes.TOPIC_INVENTORY_EVENTS, groupId = "order-service")
    @Transactional
    public void onInventoryEvent(String message) {
        EventEnvelope event = eventMapper.parse(message);
        if (!guard.markProcessed(event.eventId())) {
            return; // NFR-09
        }
        Long orderId = Long.valueOf(event.orderId());
        switch (event.eventType()) {
            case EventTypes.STOCK_RESERVED ->
                    orderService.changeStatus(orderId, OrderStatus.STOCK_RESERVED, "stock reserved");
            case EventTypes.STOCK_RESERVATION_FAILED ->
                    orderService.changeStatus(orderId, OrderStatus.CANCELLED, "out of stock");
            default -> { }
        }
    }

    @KafkaListener(topics = EventTypes.TOPIC_PAYMENT_EVENTS, groupId = "order-service")
    @Transactional
    public void onPaymentEvent(String message) {
        EventEnvelope event = eventMapper.parse(message);
        if (!guard.markProcessed(event.eventId())) {
            return;
        }
        Long orderId = Long.valueOf(event.orderId());
        switch (event.eventType()) {
            case EventTypes.PAYMENT_SUCCEEDED ->
                    orderService.changeStatus(orderId, OrderStatus.PAID, "payment succeeded");
            case EventTypes.PAYMENT_FAILED ->
                    orderService.changeStatus(orderId, OrderStatus.CANCELLED, "payment failed");
            case EventTypes.PAYMENT_REFUNDED ->
                    orderService.changeStatus(orderId, OrderStatus.REFUNDED, "payment refunded");
            default -> { }
        }
    }
}

package com.shopflow.common.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The publisher keeps per-key ordering: a failed send stops the batch and leaves later rows unsent. */
class OutboxPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final OutboxPublisher publisher = new OutboxPublisher(repository, kafka);

    @Test
    void sendsPendingRowsInOrderAndMarksThem() {
        OutboxEvent first = new OutboxEvent("order-events", "1", "OrderCreated", "{\"n\":1}");
        OutboxEvent second = new OutboxEvent("order-events", "1", "OrderCancelled", "{\"n\":2}");
        when(repository.findTop50BySentAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishPending();

        var inOrder = inOrder(kafka);
        inOrder.verify(kafka).send("order-events", "1", "{\"n\":1}");
        inOrder.verify(kafka).send("order-events", "1", "{\"n\":2}");
        assertNotNull(first.getSentAt());
        assertNotNull(second.getSentAt());
    }

    @Test
    void stopsAtFirstFailureSoOrderIsKept() {
        OutboxEvent first = new OutboxEvent("order-events", "1", "OrderCreated", "{\"a\":1}");
        OutboxEvent second = new OutboxEvent("order-events", "1", "OrderCancelled", "{\"a\":2}");
        when(repository.findTop50BySentAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), eq("{\"a\":1}")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publishPending();

        assertNull(first.getSentAt());
        assertNull(second.getSentAt());
        verify(kafka, never()).send(anyString(), anyString(), eq("{\"a\":2}"));
    }
}

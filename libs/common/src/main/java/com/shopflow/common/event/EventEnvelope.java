package com.shopflow.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Shared envelope for every Kafka event. The Kafka message key is the orderId
 * so all events for one order stay in order on one partition.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String orderId,
        Map<String, Object> payload
) {
    public static EventEnvelope of(String eventType, String orderId, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), eventType, Instant.now(), orderId, payload);
    }
}

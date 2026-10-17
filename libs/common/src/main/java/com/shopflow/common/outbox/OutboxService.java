package com.shopflow.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopflow.common.event.EventEnvelope;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Call {@link #append} inside the same @Transactional method that changes
 * business data. Data and event then commit or roll back together.
 */
@Service
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void append(String topic, String orderId, String eventType, Map<String, Object> payload) {
        EventEnvelope envelope = EventEnvelope.of(eventType, orderId, payload);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            outboxRepository.save(new OutboxEvent(topic, orderId, eventType, json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + eventType, e);
        }
    }
}

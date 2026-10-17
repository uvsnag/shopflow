package com.shopflow.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Parses the JSON envelope received from Kafka listeners. */
@Component
public class EventMapper {

    private final ObjectMapper objectMapper;

    public EventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EventEnvelope parse(String json) {
        try {
            return objectMapper.readValue(json, EventEnvelope.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse event envelope: " + json, e);
        }
    }
}

package com.shopflow.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** NFR-08: the outbox row carries a complete, parseable envelope keyed by aggregate. */
class OutboxServiceTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final OutboxService service = new OutboxService(repository, objectMapper);
    private final EventMapper eventMapper = new EventMapper(objectMapper);

    @Test
    void appendsEnvelopeKeyedByOrderId() {
        service.append("order-events", "42", "OrderCreated", Map.of("userId", 7, "totalAmount", 1000));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent row = captor.getValue();

        assertEquals("order-events", row.getTopic());
        assertEquals("42", row.getAggregateId());
        assertEquals("OrderCreated", row.getEventType());
        assertNull(row.getSentAt());
        assertNotNull(row.getId());

        EventEnvelope parsed = eventMapper.parse(row.getPayload());
        assertEquals("OrderCreated", parsed.eventType());
        assertEquals("42", parsed.orderId());
        assertNotNull(parsed.eventId());
        assertNotNull(parsed.occurredAt());
        assertEquals(7, parsed.payload().get("userId"));
    }

    @Test
    void nonOrderEventsCanHaveNoOrderIdButStillHaveAKey() {
        service.append("user-events", "9", null, "UserRegistered", Map.of("email", "a@b.c"));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        assertEquals("9", captor.getValue().getAggregateId());
        assertNull(eventMapper.parse(captor.getValue().getPayload()).orderId());
    }

    @Test
    void markSentStampsTheRow() {
        OutboxEvent row = new OutboxEvent("t", "1", "X", "{}");
        row.markSent();
        assertNotNull(row.getSentAt());
    }

    @Test
    void parseRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> eventMapper.parse("not json"));
    }
}

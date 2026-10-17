package com.shopflow.inventory.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventMapper;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.idempotency.IdempotencyGuard;
import com.shopflow.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InventoryEventListenerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final InventoryService inventoryService = mock(InventoryService.class);
    private final InventoryEventListener listener =
            new InventoryEventListener(new EventMapper(objectMapper), guard, inventoryService);

    @Test
    void orderCreatedBecomesReservationLines() throws Exception {
        when(guard.markProcessed(any())).thenReturn(true);
        String message = objectMapper.writeValueAsString(new EventEnvelope(UUID.randomUUID(),
                EventTypes.ORDER_CREATED, Instant.now(), "42",
                Map.of("userId", 1, "items", List.of(
                        Map.of("productId", 1, "quantity", 2),
                        Map.of("productId", 3, "quantity", 1)))));

        listener.onOrderEvent(message);

        verify(inventoryService).reserveForOrder("42", List.of(
                new InventoryService.OrderLine(1L, 2), new InventoryService.OrderLine(3L, 1)));
    }

    @Test
    void cancelledReleasesAndPaymentConfirms() throws Exception {
        when(guard.markProcessed(any())).thenReturn(true);

        listener.onOrderEvent(envelope(EventTypes.ORDER_CANCELLED));
        listener.onPaymentEvent(envelope(EventTypes.PAYMENT_SUCCEEDED));
        listener.onPaymentEvent(envelope(EventTypes.PAYMENT_FAILED));

        verify(inventoryService).releaseForOrder("42");
        verify(inventoryService).confirmForOrder("42");
        verifyNoMoreInteractions(inventoryService);
    }

    @Test
    void duplicateIsSkipped() throws Exception {
        when(guard.markProcessed(any())).thenReturn(false);

        listener.onOrderEvent(envelope(EventTypes.ORDER_CANCELLED));

        verifyNoInteractions(inventoryService);
    }

    private String envelope(String type) throws Exception {
        return objectMapper.writeValueAsString(new EventEnvelope(UUID.randomUUID(), type, Instant.now(), "42", Map.of()));
    }
}

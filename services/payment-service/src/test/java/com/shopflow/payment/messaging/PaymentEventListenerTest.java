package com.shopflow.payment.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventMapper;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.idempotency.IdempotencyGuard;
import com.shopflow.payment.service.PaymentService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentEventListenerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final PaymentEventListener listener =
            new PaymentEventListener(new EventMapper(objectMapper), guard, paymentService);

    @Test
    void orderEventsMaintainTheSnapshotAndTriggerRefunds() throws Exception {
        when(guard.markProcessed(any())).thenReturn(true);

        listener.onOrderEvent(envelope(EventTypes.ORDER_CREATED, Map.of("userId", 1, "totalAmount", 500000)));
        listener.onOrderEvent(envelope(EventTypes.ORDER_CANCELLED, Map.of("reason", "customer cancel after payment")));
        listener.onOrderEvent(envelope(EventTypes.ORDER_SHIPPED, Map.of()));

        verify(paymentService).rememberOrder(42L, 1L, 500000L);
        verify(paymentService).markOrderCancelled(42L);
        verify(paymentService).refundByOrderId(42L, "customer cancel after payment");
        verifyNoMoreInteractions(paymentService);
    }

    @Test
    void inventoryEventsGateThePayButton() throws Exception {
        when(guard.markProcessed(any())).thenReturn(true);

        listener.onInventoryEvent(envelope(EventTypes.STOCK_RESERVED, Map.of()));
        listener.onInventoryEvent(envelope(EventTypes.STOCK_RESERVATION_FAILED, Map.of("reason", "x")));

        verify(paymentService).markOrderReserved(42L);
        verify(paymentService).markOrderCancelled(42L);
    }

    @Test
    void duplicatesAreIgnored() throws Exception {
        when(guard.markProcessed(any())).thenReturn(false);

        listener.onOrderEvent(envelope(EventTypes.ORDER_CANCELLED, Map.of()));
        listener.onInventoryEvent(envelope(EventTypes.STOCK_RESERVED, Map.of()));

        verifyNoInteractions(paymentService);
    }

    private String envelope(String type, Map<String, Object> payload) throws Exception {
        return objectMapper.writeValueAsString(new EventEnvelope(UUID.randomUUID(), type, Instant.now(), "42", payload));
    }
}

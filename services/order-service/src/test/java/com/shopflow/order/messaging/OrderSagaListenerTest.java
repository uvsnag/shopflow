package com.shopflow.order.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventMapper;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.idempotency.IdempotencyGuard;
import com.shopflow.order.service.OrderService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Must-have test case: the same Kafka event delivered twice is processed once (NFR-09). */
class OrderSagaListenerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final OrderService orderService = mock(OrderService.class);
    private final OrderSagaListener listener =
            new OrderSagaListener(new EventMapper(objectMapper), guard, orderService);

    @Test
    void inventoryEventsAreRouted() throws Exception {
        when(guard.markProcessed(any())).thenReturn(true);

        listener.onInventoryEvent(json(EventTypes.STOCK_RESERVED, "42"));
        listener.onInventoryEvent(json(EventTypes.STOCK_RESERVATION_FAILED, "43"));

        verify(orderService).onStockReserved(42L);
        verify(orderService).onStockReservationFailed(43L);
    }

    @Test
    void sameEventTwiceIsHandledOnce() throws Exception {
        String message = json(EventTypes.PAYMENT_SUCCEEDED, "42");
        when(guard.markProcessed(any())).thenReturn(true, false); // first delivery new, second a duplicate

        listener.onPaymentEvent(message);
        listener.onPaymentEvent(message);

        verify(orderService, times(1)).onPaymentSucceeded(42L);
    }

    @Test
    void paymentEventsAreRouted() throws Exception {
        when(guard.markProcessed(any())).thenReturn(true);

        listener.onPaymentEvent(json(EventTypes.PAYMENT_FAILED, "1"));
        listener.onPaymentEvent(json(EventTypes.PAYMENT_REFUNDED, "2"));

        verify(orderService).onPaymentFailed(1L);
        verify(orderService).onPaymentRefunded(2L);
    }

    private String json(String type, String orderId) throws Exception {
        return objectMapper.writeValueAsString(
                new EventEnvelope(UUID.randomUUID(), type, java.time.Instant.now(), orderId, Map.of("userId", 1)));
    }
}

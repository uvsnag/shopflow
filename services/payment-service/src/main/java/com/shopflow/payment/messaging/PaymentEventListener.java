package com.shopflow.payment.messaging;

import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventMapper;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.idempotency.IdempotencyGuard;
import com.shopflow.payment.service.PaymentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment side of the saga:
 * <ul>
 *   <li>order-events keep the local order snapshot up to date (ADR 0011) and
 *       trigger the refund compensation when a paid order is cancelled;</li>
 *   <li>inventory-events tell us when an order's stock is reserved, i.e. when
 *       it may be paid.</li>
 * </ul>
 * Handlers are @Transactional so the processed_events mark and the change
 * commit together; a failure rolls back both and the Kafka retry is not
 * skipped as a duplicate.
 */
@Component
public class PaymentEventListener {

    private final EventMapper eventMapper;
    private final IdempotencyGuard guard;
    private final PaymentService paymentService;

    public PaymentEventListener(EventMapper eventMapper, IdempotencyGuard guard, PaymentService paymentService) {
        this.eventMapper = eventMapper;
        this.guard = guard;
        this.paymentService = paymentService;
    }

    @KafkaListener(topics = EventTypes.TOPIC_ORDER_EVENTS, groupId = "payment-service")
    @Transactional
    public void onOrderEvent(String message) {
        EventEnvelope event = eventMapper.parse(message);
        if (!guard.markProcessed(event.eventId())) {
            return; // NFR-09
        }
        Long orderId = Long.valueOf(event.orderId());
        switch (event.eventType()) {
            case EventTypes.ORDER_CREATED -> paymentService.rememberOrder(orderId,
                    asLong(event.payload().get("userId")), asLong(event.payload().get("totalAmount")));
            case EventTypes.ORDER_CANCELLED -> {
                paymentService.markOrderCancelled(orderId);
                paymentService.refundByOrderId(orderId,
                        String.valueOf(event.payload().getOrDefault("reason", "order cancelled")));
            }
            default -> { }
        }
    }

    @KafkaListener(topics = EventTypes.TOPIC_INVENTORY_EVENTS, groupId = "payment-service")
    @Transactional
    public void onInventoryEvent(String message) {
        EventEnvelope event = eventMapper.parse(message);
        if (!guard.markProcessed(event.eventId())) {
            return;
        }
        Long orderId = Long.valueOf(event.orderId());
        switch (event.eventType()) {
            case EventTypes.STOCK_RESERVED -> paymentService.markOrderReserved(orderId);
            case EventTypes.STOCK_RESERVATION_FAILED -> paymentService.markOrderCancelled(orderId);
            default -> { }
        }
    }

    private static Long asLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }
}

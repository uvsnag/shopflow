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
 * Saga compensation: a cancelled order that was already paid gets a refund
 * (customer cancels a paid order -> REFUNDED).
 *
 * @Transactional so the processed_events mark and the refund commit together;
 * a failure rolls back both and the Kafka retry is not skipped as a duplicate.
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
        if (EventTypes.ORDER_CANCELLED.equals(event.eventType())) {
            paymentService.refundByOrderId(Long.valueOf(event.orderId()),
                    String.valueOf(event.payload().getOrDefault("reason", "order cancelled")));
        }
    }
}

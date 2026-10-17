package com.shopflow.notification.messaging;

import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventMapper;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.idempotency.IdempotencyGuard;
import com.shopflow.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listens to every topic and turns order status changes into notifications
 * (FR-11). If this service is down, messages wait in Kafka (NFR-13).
 */
@Component
public class NotificationEventListener {

    private final EventMapper eventMapper;
    private final IdempotencyGuard guard;
    private final NotificationService notificationService;

    public NotificationEventListener(EventMapper eventMapper, IdempotencyGuard guard,
                                     NotificationService notificationService) {
        this.eventMapper = eventMapper;
        this.guard = guard;
        this.notificationService = notificationService;
    }

    @KafkaListener(
            topics = {EventTypes.TOPIC_ORDER_EVENTS, EventTypes.TOPIC_PAYMENT_EVENTS},
            groupId = "notification-service")
    @Transactional
    public void onEvent(String message) {
        EventEnvelope event = eventMapper.parse(message);
        if (!guard.markProcessed(event.eventId())) {
            return; // NFR-09
        }
        Object userIdRaw = event.payload().get("userId");
        if (userIdRaw == null) {
            return; // every event should carry userId; skip rather than crash if one doesn't
        }
        Long userId = Long.valueOf(userIdRaw.toString());
        String orderId = event.orderId();

        switch (event.eventType()) {
            case EventTypes.ORDER_CREATED -> notificationService.notifyUser(userId, "ORDER",
                    "Order " + orderId + " placed", "We received your order and are reserving stock.");
            case EventTypes.ORDER_CANCELLED -> notificationService.notifyUser(userId, "ORDER",
                    "Order " + orderId + " cancelled",
                    "Reason: " + event.payload().getOrDefault("reason", "unknown"));
            case EventTypes.ORDER_SHIPPED -> notificationService.notifyUser(userId, "ORDER",
                    "Order " + orderId + " shipped", "Your order is on its way.");
            case EventTypes.ORDER_DELIVERED -> notificationService.notifyUser(userId, "ORDER",
                    "Order " + orderId + " delivered", "Enjoy your purchase!");
            case EventTypes.PAYMENT_SUCCEEDED -> notificationService.notifyUser(userId, "PAYMENT",
                    "Payment received for order " + orderId, "Thank you! We are preparing your order.");
            case EventTypes.PAYMENT_FAILED -> notificationService.notifyUser(userId, "PAYMENT",
                    "Payment failed for order " + orderId,
                    "Reason: " + event.payload().getOrDefault("reason", "unknown") + ". The order was cancelled.");
            case EventTypes.PAYMENT_REFUNDED -> notificationService.notifyUser(userId, "PAYMENT",
                    "Refund issued for order " + orderId, "Your payment was refunded.");
            default -> { }
        }
    }
}

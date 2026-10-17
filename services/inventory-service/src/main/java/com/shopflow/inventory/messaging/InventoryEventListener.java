package com.shopflow.inventory.messaging;

import com.shopflow.common.event.EventEnvelope;
import com.shopflow.common.event.EventMapper;
import com.shopflow.common.event.EventTypes;
import com.shopflow.common.idempotency.IdempotencyGuard;
import com.shopflow.inventory.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private final EventMapper eventMapper;
    private final IdempotencyGuard guard;
    private final InventoryService inventoryService;

    public InventoryEventListener(EventMapper eventMapper, IdempotencyGuard guard, InventoryService inventoryService) {
        this.eventMapper = eventMapper;
        this.guard = guard;
        this.inventoryService = inventoryService;
    }

    @KafkaListener(topics = EventTypes.TOPIC_ORDER_EVENTS, groupId = "inventory-service")
    public void onOrderEvent(String message) {
        EventEnvelope event = eventMapper.parse(message);
        if (!guard.markProcessed(event.eventId())) {
            log.debug("Duplicate event {} skipped", event.eventId()); // NFR-09
            return;
        }
        switch (event.eventType()) {
            case EventTypes.ORDER_CREATED -> reserve(event);
            case EventTypes.ORDER_CANCELLED -> inventoryService.releaseForOrder(event.orderId());
            default -> { /* OrderShipped etc. not relevant here */ }
        }
    }

    @KafkaListener(topics = EventTypes.TOPIC_PAYMENT_EVENTS, groupId = "inventory-service")
    public void onPaymentEvent(String message) {
        EventEnvelope event = eventMapper.parse(message);
        if (!guard.markProcessed(event.eventId())) {
            return;
        }
        if (EventTypes.PAYMENT_SUCCEEDED.equals(event.eventType())) {
            inventoryService.confirmForOrder(event.orderId());
        }
    }

    @SuppressWarnings("unchecked")
    private void reserve(EventEnvelope event) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) event.payload().get("items");
        List<InventoryService.OrderLine> lines = items.stream()
                .map(i -> new InventoryService.OrderLine(
                        Long.valueOf(i.get("productId").toString()),
                        Integer.parseInt(i.get("quantity").toString())))
                .toList();
        try {
            inventoryService.reserveForOrder(event.orderId(), lines);
        } catch (InventoryService.ReservationFailedException e) {
            inventoryService.publishReservationFailed(e.orderId, e.getMessage());
        }
    }
}

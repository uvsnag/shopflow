package com.shopflow.common.event;

/** Event type and topic names shared by all services. */
public final class EventTypes {

    public static final String TOPIC_ORDER_EVENTS = "order-events";
    public static final String TOPIC_INVENTORY_EVENTS = "inventory-events";
    public static final String TOPIC_PAYMENT_EVENTS = "payment-events";
    /** auth-service -> notification-service: who to e-mail (keyed by userId, not orderId). */
    public static final String TOPIC_USER_EVENTS = "user-events";

    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_CANCELLED = "OrderCancelled";
    public static final String ORDER_SHIPPED = "OrderShipped";
    public static final String ORDER_DELIVERED = "OrderDelivered";

    public static final String STOCK_RESERVED = "StockReserved";
    public static final String STOCK_RESERVATION_FAILED = "StockReservationFailed";

    public static final String PAYMENT_SUCCEEDED = "PaymentSucceeded";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String PAYMENT_REFUNDED = "PaymentRefunded";

    public static final String USER_REGISTERED = "UserRegistered";

    private EventTypes() {
    }
}

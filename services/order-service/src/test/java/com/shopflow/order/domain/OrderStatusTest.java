package com.shopflow.order.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the order status rules (see plan: Testing / Unit). */
class OrderStatusTest {

    @Test
    void happyPathTransitionsAreAllowed() {
        assertTrue(OrderStatus.PENDING.canGoTo(OrderStatus.STOCK_RESERVED));
        assertTrue(OrderStatus.STOCK_RESERVED.canGoTo(OrderStatus.PAID));
        assertTrue(OrderStatus.PAID.canGoTo(OrderStatus.SHIPPED));
        assertTrue(OrderStatus.SHIPPED.canGoTo(OrderStatus.DELIVERED));
    }

    @Test
    void earlyExitsAreAllowed() {
        assertTrue(OrderStatus.PENDING.canGoTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.STOCK_RESERVED.canGoTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.PAID.canGoTo(OrderStatus.REFUNDED));
    }

    @Test
    void illegalJumpsAreBlocked() {
        assertFalse(OrderStatus.PENDING.canGoTo(OrderStatus.PAID));
        assertFalse(OrderStatus.CANCELLED.canGoTo(OrderStatus.PAID));
        assertFalse(OrderStatus.DELIVERED.canGoTo(OrderStatus.CANCELLED));
        assertFalse(OrderStatus.SHIPPED.canGoTo(OrderStatus.REFUNDED));
    }
}

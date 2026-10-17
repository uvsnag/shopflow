package com.shopflow.order.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Order totals (BR-05) and the status machine guard. */
class OrderTest {

    @Test
    void totalIsSumOfFrozenUnitPrices() {
        Order order = new Order(1L, "key");
        order.addItem(10L, "Mouse", 250_000L, 2);
        order.addItem(11L, "Book", 450_000L, 1);
        assertEquals(950_000L, order.getTotalAmount());
        assertEquals("VND", order.getCurrency());
        assertEquals(2, order.getItems().size());
        assertEquals(OrderStatus.PENDING, order.getStatus());
    }

    @Test
    void illegalTransitionIsRejected() {
        Order order = new Order(1L, "key");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> order.transitionTo(OrderStatus.PAID));
        assertTrue(e.getMessage().contains("PENDING"));
        assertEquals(OrderStatus.PENDING, order.getStatus());
    }

    @Test
    void happyPathWalksTheMachine() {
        Order order = new Order(1L, "key");
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.SHIPPED);
        order.transitionTo(OrderStatus.DELIVERED);
        assertEquals(OrderStatus.DELIVERED, order.getStatus());
    }

    @Test
    void unpaidAndOldIsTimeoutCandidate() {
        Order order = new Order(1L, "key");
        Instant future = Instant.now().plus(Duration.ofMinutes(16));
        assertTrue(order.isUnpaidAndOlderThan(future));
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        assertTrue(order.isUnpaidAndOlderThan(future));
        order.transitionTo(OrderStatus.PAID);
        assertFalse(order.isUnpaidAndOlderThan(future));
    }
}

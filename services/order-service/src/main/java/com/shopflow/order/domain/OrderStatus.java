package com.shopflow.order.domain;

import java.util.Map;
import java.util.Set;

/**
 * PENDING -> STOCK_RESERVED -> PAID -> SHIPPED -> DELIVERED
 * Early exits: CANCELLED, REFUNDED. See the state diagram in the plan.
 */
public enum OrderStatus {
    PENDING, STOCK_RESERVED, PAID, SHIPPED, DELIVERED, CANCELLED, REFUNDED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING, Set.of(STOCK_RESERVED, CANCELLED),
            STOCK_RESERVED, Set.of(PAID, CANCELLED),
            PAID, Set.of(SHIPPED, REFUNDED),
            SHIPPED, Set.of(DELIVERED),
            DELIVERED, Set.of(),
            CANCELLED, Set.of(),
            REFUNDED, Set.of()
    );

    public boolean canGoTo(OrderStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }
}

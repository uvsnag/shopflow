package com.shopflow.inventory.domain;

import jakarta.persistence.*;

/**
 * Stock per product. @Version = optimistic locking: two people buying the
 * last item at the same time cannot both win (BR-01, must-have test case).
 */
@Entity
@Table(name = "stock")
public class Stock {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private int reserved;

    @Version
    private long version;

    protected Stock() {
    }

    public Stock(Long productId, int available) {
        this.productId = productId;
        this.available = available;
    }

    public boolean canReserve(int quantity) {
        return available >= quantity;
    }

    /** BR-02: move from available to reserved when the order is placed. */
    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Not enough stock for product " + productId);
        }
        available -= quantity;
        reserved += quantity;
    }

    /** BR-02: give the stock back on cancel / payment failure / timeout. */
    public void release(int quantity) {
        reserved -= quantity;
        available += quantity;
    }

    /** Payment succeeded: the reserved stock is really sold. */
    public void confirm(int quantity) {
        reserved -= quantity;
    }

    public void addStock(int quantity) {
        available += quantity; // FR-13
    }

    public Long getProductId() { return productId; }
    public int getAvailable() { return available; }
    public int getReserved() { return reserved; }
    public long getVersion() { return version; }
}

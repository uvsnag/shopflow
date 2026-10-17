package com.shopflow.payment.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * payment-service's own copy of the few order facts it needs to accept a
 * payment (ADR 0011): who owns the order, how much it costs, and whether stock
 * is reserved. Built from OrderCreated / StockReserved / OrderCancelled events,
 * so the amount can never come from the client (BR-04, BR-05) and no
 * synchronous call to order-service is needed on the pay path.
 */
@Entity
@Table(name = "order_snapshots")
public class OrderSnapshot {

    public enum Status { CREATED, RESERVED, CANCELLED }

    @Id
    @Column(name = "order_id")
    private Long orderId;

    /** Nullable: StockReserved can, in theory, overtake OrderCreated across topics. */
    @Column(name = "user_id")
    private Long userId;

    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected OrderSnapshot() {
    }

    public OrderSnapshot(Long orderId, Long userId, Long amount, Status status) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.status = status;
    }

    public void fillFromOrderCreated(Long userId, Long amount) {
        this.userId = userId;
        this.amount = amount;
        touch();
    }

    /** CANCELLED is final; anything else may move to RESERVED. */
    public void markReserved() {
        if (status != Status.CANCELLED) {
            status = Status.RESERVED;
        }
        touch();
    }

    public void markCancelled() {
        status = Status.CANCELLED;
        touch();
    }

    /** Payable = stock reserved and we know the owner and the price. */
    public boolean isPayable() {
        return status == Status.RESERVED && userId != null && amount != null;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getOrderId() { return orderId; }
    public Long getUserId() { return userId; }
    public Long getAmount() { return amount; }
    public Status getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }
}

package com.shopflow.payment.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * BR-04: order_id is UNIQUE — the database itself blocks a second payment
 * for the same order.
 */
@Entity
@Table(name = "payments")
public class Payment {

    public enum Status { PENDING, SUCCEEDED, FAILED, REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    /** Copied from the gateway identity at pay time, so payment events can carry it (FR-11). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    /** Reference the fake provider returns for this charge. */
    @Column(name = "provider_ref")
    private String providerRef;

    /** NFR-10: pressing "Pay" twice with the same key charges once. */
    @Column(name = "idempotency_key", unique = true, nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Payment() {
    }

    public Payment(Long userId, Long orderId, Long amount, String idempotencyKey) {
        this.userId = userId;
        this.orderId = orderId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getOrderId() { return orderId; }
    public Long getAmount() { return amount; }
    public Status getStatus() { return status; }
    public String getProviderRef() { return providerRef; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }

    public void setProviderRef(String providerRef) { this.providerRef = providerRef; }
    public void succeed() { this.status = Status.SUCCEEDED; }
    public void fail() { this.status = Status.FAILED; }
    public void refund() { this.status = Status.REFUNDED; }
    public boolean isPending() { return status == Status.PENDING; }
    public boolean isSucceeded() { return status == Status.SUCCEEDED; }
}

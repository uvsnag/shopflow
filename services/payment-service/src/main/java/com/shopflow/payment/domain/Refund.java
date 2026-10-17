package com.shopflow.payment.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A refund stays PENDING while the provider call keeps failing; a scheduled
 * job retries it (compensation must eventually happen). After the maximum
 * number of attempts it becomes FAILED for a human to look at.
 */
@Entity
@Table(name = "refunds")
public class Refund {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    private String reason;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Refund() {
    }

    public Refund(Long paymentId, Long amount, String reason) {
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
    }

    public void recordAttempt() { this.attempts++; }
    public void recordError(String error) {
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }
    public void succeed() { this.status = Status.SUCCEEDED; }
    public void fail() { this.status = Status.FAILED; }
    public boolean isPending() { return status == Status.PENDING; }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public Long getAmount() { return amount; }
    public Status getStatus() { return status; }
    public String getReason() { return reason; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
}

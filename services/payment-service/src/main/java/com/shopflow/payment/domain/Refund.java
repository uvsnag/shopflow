package com.shopflow.payment.domain;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;

/**
 * A refund is a queued command: it is created PENDING in the same transaction
 * that decides to refund (atomic with the Kafka processed_events mark), and
 * {@link com.shopflow.payment.service.RefundRetryJob} calls the bank outside
 * any transaction. A failed call is retried with exponential backoff; after
 * the maximum number of attempts it becomes FAILED for a human to look at.
 *
 * A partial unique index (V5) allows at most one PENDING or SUCCEEDED refund
 * per payment, so a payment can never be refunded twice.
 */
@Entity
@Table(name = "refunds")
public class Refund {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(10);

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

    /** When the job should (re)try the bank call; due immediately on creation. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    protected Refund() {
    }

    public Refund(Long paymentId, Long amount, String reason) {
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
    }

    /** The provider-side idempotency key: retries of this refund never pay out twice. */
    public String providerIdempotencyKey() {
        return "refund-" + id;
    }

    public void recordAttempt() { this.attempts++; }

    public void recordError(String error) {
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }

    /** 10s, 20s, 40s, ... capped at 10 minutes. */
    public void scheduleRetry(Instant now) {
        long factor = 1L << Math.min(Math.max(attempts - 1, 0), 16);
        Duration delay = FIRST_RETRY_DELAY.multipliedBy(factor);
        this.nextAttemptAt = now.plus(delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay);
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
    public Instant getNextAttemptAt() { return nextAttemptAt; }
}

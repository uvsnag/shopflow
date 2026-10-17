package com.shopflow.payment.domain;

import jakarta.persistence.*;

import java.time.Instant;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Refund() {
    }

    public Refund(Long paymentId, Long amount) {
        this.paymentId = paymentId;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public Long getAmount() { return amount; }
    public Status getStatus() { return status; }
    public void succeed() { this.status = Status.SUCCEEDED; }
    public void fail() { this.status = Status.FAILED; }
}

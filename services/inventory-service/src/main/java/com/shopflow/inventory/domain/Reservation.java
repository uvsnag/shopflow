package com.shopflow.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "reservations")
public class Reservation {

    public enum Status { RESERVED, CONFIRMED, RELEASED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.RESERVED;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected Reservation() {
    }

    public Reservation(String orderId, Long productId, int quantity, Instant expiresAt) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getOrderId() { return orderId; }
    public Long getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void confirm() { this.status = Status.CONFIRMED; }
    public void release() { this.status = Status.RELEASED; }
    public boolean isReserved() { return status == Status.RESERVED; }
}

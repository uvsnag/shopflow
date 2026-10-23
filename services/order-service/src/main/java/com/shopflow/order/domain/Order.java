package com.shopflow.order.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** NFR-10: an Idempotency-Key is unique per user (V2), so one user can never replay another's order. */
@Entity
@Table(name = "orders", uniqueConstraints =
        @UniqueConstraint(name = "uk_orders_user_idempotency_key", columnNames = {"user_id", "idempotency_key"}))
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    /** Smallest currency unit (BR-05: price frozen at order time). */
    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(nullable = false)
    private String currency = "VND";

    /** NFR-10: same user + same Idempotency-Key -> same order, enforced by the unique constraint above. */
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    /**
     * Items are part of every API response and open-in-view is off, so they are
     * loaded with the order (one extra SUBSELECT per query, not one per order).
     * Lazy items would throw LazyInitializationException in the controllers.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(Long userId, String idempotencyKey) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.totalAmount = 0L;
    }

    public void addItem(Long productId, String productName, Long unitPrice, int quantity) {
        items.add(new OrderItem(this, productId, productName, unitPrice, quantity));
        totalAmount += unitPrice * quantity;
    }

    public void transitionTo(OrderStatus next) {
        if (!status.canGoTo(next)) {
            throw new IllegalStateException(
                    "Order " + id + " cannot go from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public boolean isUnpaidAndOlderThan(Instant cutoff) {
        return (status == OrderStatus.PENDING || status == OrderStatus.STOCK_RESERVED)
                && createdAt.isBefore(cutoff);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public Long getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<OrderItem> getItems() { return items; }
}

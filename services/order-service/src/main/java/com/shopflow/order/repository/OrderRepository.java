package com.shopflow.order.repository;

import com.shopflow.order.domain.Order;
import com.shopflow.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** NFR-10: the key is scoped to the user (unique (user_id, idempotency_key)). */
    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    List<Order> findByStatusInAndCreatedAtBefore(List<OrderStatus> statuses, Instant cutoff);

    /** NFR-20: feeds the "orders per status" Grafana panel. Rows are [OrderStatus, Long]. */
    @Query("select o.status, count(o) from Order o group by o.status")
    List<Object[]> countByStatus();
}

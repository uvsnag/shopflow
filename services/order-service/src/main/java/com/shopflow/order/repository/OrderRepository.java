package com.shopflow.order.repository;

import com.shopflow.order.domain.Order;
import com.shopflow.order.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** NFR-10: the key is scoped to the user (unique (user_id, idempotency_key)). */
    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    /**
     * {@code SELECT ... FOR UPDATE}: every status change locks the order row.
     * Saga events from two topics, the timeout job, the customer and the admin
     * can all touch one order at the same moment; with the lock they run one
     * after another on fresh data instead of failing on @Version and burning
     * Kafka retries. (@Version stays as a safety net.)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> lockById(@Param("id") Long id);

    /** Ids only: the timeout job re-reads each order under lock, so loading items here would be wasted. */
    @Query("select o.id from Order o where o.status in :statuses and o.createdAt < :cutoff order by o.createdAt")
    List<Long> findIdsByStatusInAndCreatedAtBefore(@Param("statuses") List<OrderStatus> statuses,
                                                   @Param("cutoff") Instant cutoff);

    /** NFR-20: feeds the "orders per status" Grafana panel. Rows are [OrderStatus, Long]. */
    @Query("select o.status, count(o) from Order o group by o.status")
    List<Object[]> countByStatus();
}

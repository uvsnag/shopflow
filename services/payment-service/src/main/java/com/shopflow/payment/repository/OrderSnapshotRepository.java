package com.shopflow.payment.repository;

import com.shopflow.payment.domain.OrderSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The snapshot is fed by two listeners (order-events and inventory-events)
 * running on different threads, and either event may arrive first. Each
 * update is a single {@code INSERT ... ON CONFLICT DO UPDATE}, so the two can
 * never both try to create the row (no primary-key error, no Kafka retry) and
 * the merge rules live in one atomic statement.
 */
public interface OrderSnapshotRepository extends JpaRepository<OrderSnapshot, Long> {

    /** OrderCreated: remember owner and amount; the status is left alone (never revives a cancelled order). */
    @Modifying
    @Query(value = """
            INSERT INTO order_snapshots (order_id, user_id, amount, status, updated_at)
            VALUES (:orderId, :userId, :amount, 'CREATED', now())
            ON CONFLICT (order_id) DO UPDATE
               SET user_id = EXCLUDED.user_id, amount = EXCLUDED.amount, updated_at = now()
            """, nativeQuery = true)
    void upsertCreated(@Param("orderId") Long orderId, @Param("userId") Long userId, @Param("amount") Long amount);

    /** StockReserved: payable from now on — unless the order was already cancelled (CANCELLED is final). */
    @Modifying
    @Query(value = """
            INSERT INTO order_snapshots (order_id, status, updated_at)
            VALUES (:orderId, 'RESERVED', now())
            ON CONFLICT (order_id) DO UPDATE
               SET status = CASE WHEN order_snapshots.status = 'CANCELLED' THEN 'CANCELLED' ELSE 'RESERVED' END,
                   updated_at = now()
            """, nativeQuery = true)
    void upsertReserved(@Param("orderId") Long orderId);

    /** OrderCancelled / StockReservationFailed: no (new) payment for this order. */
    @Modifying
    @Query(value = """
            INSERT INTO order_snapshots (order_id, status, updated_at)
            VALUES (:orderId, 'CANCELLED', now())
            ON CONFLICT (order_id) DO UPDATE SET status = 'CANCELLED', updated_at = now()
            """, nativeQuery = true)
    void upsertCancelled(@Param("orderId") Long orderId);
}

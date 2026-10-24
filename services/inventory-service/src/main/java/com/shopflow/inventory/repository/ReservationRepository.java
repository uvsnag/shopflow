package com.shopflow.inventory.repository;

import com.shopflow.inventory.domain.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByOrderId(String orderId);

    boolean existsByOrderId(String orderId);

    /**
     * {@code SELECT ... FOR UPDATE} on one order's reservation lines. Release
     * (OrderCancelled), confirm (PaymentSucceeded) and the expiry job can run
     * at the same moment on different consumer threads; locking the lines
     * first makes the second one wait and then read the committed status, so a
     * line is never released AND confirmed (which would corrupt the counters).
     * Lock order everywhere: reservation lines first, then stock rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.orderId = :orderId order by r.id")
    List<Reservation> lockByOrderId(@Param("orderId") String orderId);

    /** Distinct order ids, so the expiry job can release each order in its own short transaction. */
    @Query("select distinct r.orderId from Reservation r where r.status = :status and r.expiresAt < :cutoff")
    List<String> findOrderIdsByStatusAndExpiresAtBefore(@Param("status") Reservation.Status status,
                                                        @Param("cutoff") Instant cutoff);
}

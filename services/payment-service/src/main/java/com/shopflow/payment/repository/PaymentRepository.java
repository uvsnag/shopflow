package com.shopflow.payment.repository;

import com.shopflow.payment.domain.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    /** NFR-10: the key is scoped to the user (unique (user_id, idempotency_key)). */
    Optional<Payment> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<Payment> findByProviderRef(String providerRef);

    /**
     * {@code SELECT ... FOR UPDATE}: every change of a payment's status (the
     * webhook, the post-charge update, refunds) takes this lock first. Without
     * it the request thread could write back a stale PENDING row right after
     * the webhook thread committed SUCCEEDED (Hibernate updates all columns),
     * losing the payment while PaymentSucceeded was already emitted.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") Long id);

    /**
     * Same lock, looked up by order. Lock BEFORE reading: a row already loaded
     * in the persistence context is returned as-is by a later locking query,
     * i.e. with the state from before the lock wait.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> lockByOrderId(@Param("orderId") Long orderId);
}

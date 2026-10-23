package com.shopflow.payment.repository;

import com.shopflow.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    /** NFR-10: the key is scoped to the user (unique (user_id, idempotency_key)). */
    Optional<Payment> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<Payment> findByProviderRef(String providerRef);
}

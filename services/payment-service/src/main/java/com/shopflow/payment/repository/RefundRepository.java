package com.shopflow.payment.repository;

import com.shopflow.payment.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    boolean existsByPaymentIdAndStatusIn(Long paymentId, List<Refund.Status> statuses);

    /** Refunds whose next attempt is due, oldest first. */
    List<Refund> findTop20ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(Refund.Status status, Instant now);
}

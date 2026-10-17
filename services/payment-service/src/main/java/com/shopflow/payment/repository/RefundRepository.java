package com.shopflow.payment.repository;

import com.shopflow.payment.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    boolean existsByPaymentIdAndStatus(Long paymentId, Refund.Status status);

    List<Refund> findByStatusAndCreatedAtBefore(Refund.Status status, Instant cutoff);
}

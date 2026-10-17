package com.shopflow.payment.repository;

import com.shopflow.payment.domain.OrderSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSnapshotRepository extends JpaRepository<OrderSnapshot, Long> {
}

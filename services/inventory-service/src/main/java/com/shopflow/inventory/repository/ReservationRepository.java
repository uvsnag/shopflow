package com.shopflow.inventory.repository;

import com.shopflow.inventory.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByOrderId(String orderId);

    List<Reservation> findByStatusAndExpiresAtBefore(Reservation.Status status, Instant cutoff);
}

package com.shopflow.inventory.service;

import com.shopflow.inventory.domain.Reservation;
import com.shopflow.inventory.repository.ReservationRepository;
import com.shopflow.inventory.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * NFR-12 (inventory side): safety net that releases reservations whose
 * 15 minutes ran out. Normally order-service's timeout job cancels the order
 * first and OrderCancelled releases the stock; this catches lost events.
 */
@Component
public class ExpiredReservationJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiredReservationJob.class);

    private final ReservationRepository reservationRepository;
    private final StockRepository stockRepository;

    public ExpiredReservationJob(ReservationRepository reservationRepository, StockRepository stockRepository) {
        this.reservationRepository = reservationRepository;
        this.stockRepository = stockRepository;
    }

    @Scheduled(fixedDelayString = "${shopflow.expired-reservation-poll-ms:60000}")
    @Transactional
    public void releaseExpired() {
        var expired = reservationRepository.findByStatusAndExpiresAtBefore(
                Reservation.Status.RESERVED, Instant.now());
        for (Reservation reservation : expired) {
            stockRepository.findById(reservation.getProductId())
                    .ifPresent(s -> s.release(reservation.getQuantity()));
            reservation.release();
            log.info("Released expired reservation {} (order {})", reservation.getId(), reservation.getOrderId());
        }
    }
}

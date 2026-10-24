package com.shopflow.inventory.service;

import com.shopflow.inventory.domain.Reservation;
import com.shopflow.inventory.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * NFR-12 (inventory side): safety net that releases reservations whose time
 * ran out. Normally order-service's 15-minute timeout cancels the order first
 * and OrderCancelled releases the stock; this only catches lost events.
 *
 * That is why the reservation TTL (default 30m) is deliberately LONGER than
 * the payment timeout (15m): if both were 15 minutes, a payment landing at
 * minute 14:59 could find its stock already handed back to other buyers.
 *
 * Each order is released in its own transaction (with the same row locks as
 * the saga handlers), so one conflicting order neither rolls back nor blocks
 * the rest, and a PaymentSucceeded arriving at the same moment cannot be
 * applied to a line this job is releasing.
 */
@Component
public class ExpiredReservationJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiredReservationJob.class);

    private final ReservationRepository reservationRepository;
    private final InventoryService inventoryService;

    public ExpiredReservationJob(ReservationRepository reservationRepository, InventoryService inventoryService) {
        this.reservationRepository = reservationRepository;
        this.inventoryService = inventoryService;
    }

    @Scheduled(fixedDelayString = "${shopflow.expired-reservation-poll-ms:60000}")
    public void releaseExpired() {
        Instant now = Instant.now();
        for (String orderId : reservationRepository.findOrderIdsByStatusAndExpiresAtBefore(
                Reservation.Status.RESERVED, now)) {
            try {
                inventoryService.releaseExpiredForOrder(orderId, now);
            } catch (Exception e) {
                log.warn("Could not release expired reservations of order {}, will retry", orderId, e);
            }
        }
    }
}

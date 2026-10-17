package com.shopflow.inventory.service;

import com.shopflow.common.event.EventTypes;
import com.shopflow.common.outbox.OutboxService;
import com.shopflow.common.web.NotFoundException;
import com.shopflow.inventory.domain.Reservation;
import com.shopflow.inventory.domain.Stock;
import com.shopflow.inventory.repository.ReservationRepository;
import com.shopflow.inventory.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final StockRepository stockRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxService outbox;
    private final Duration reservationTtl;

    public InventoryService(StockRepository stockRepository,
                            ReservationRepository reservationRepository,
                            OutboxService outbox,
                            @Value("${shopflow.reservation-ttl:15m}") Duration reservationTtl) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.outbox = outbox;
        this.reservationTtl = reservationTtl;
    }

    public record OrderLine(Long productId, int quantity) {}

    /**
     * Saga step 2-3: OrderCreated -> try to reserve every line (BR-01, BR-02).
     * All-or-nothing: one missing line fails the whole reservation.
     */
    @Transactional
    public void reserveForOrder(String orderId, List<OrderLine> lines) {
        try {
            for (OrderLine line : lines) {
                Stock stock = stockRepository.findById(line.productId())
                        .orElseThrow(() -> new IllegalStateException("No stock row for product " + line.productId()));
                stock.reserve(line.quantity()); // throws if not enough
                reservationRepository.save(new Reservation(
                        orderId, line.productId(), line.quantity(), Instant.now().plus(reservationTtl)));
            }
            outbox.append(EventTypes.TOPIC_INVENTORY_EVENTS, orderId, EventTypes.STOCK_RESERVED, Map.of());
        } catch (IllegalStateException notEnough) {
            log.info("Reservation failed for order {}: {}", orderId, notEnough.getMessage());
            // the @Transactional rollback undoes partial reserves; we still must
            // tell order-service, so throw a marker the listener converts
            throw new ReservationFailedException(orderId, notEnough.getMessage());
        }
    }

    public static class ReservationFailedException extends RuntimeException {
        public final String orderId;
        public ReservationFailedException(String orderId, String message) {
            super(message);
            this.orderId = orderId;
        }
    }

    /** Written in its own transaction after the reserve transaction rolled back. */
    @Transactional
    public void publishReservationFailed(String orderId, String reason) {
        outbox.append(EventTypes.TOPIC_INVENTORY_EVENTS, orderId,
                EventTypes.STOCK_RESERVATION_FAILED, Map.of("reason", reason));
    }

    /** Saga compensation: OrderCancelled -> give the stock back (BR-02). */
    @Transactional
    public void releaseForOrder(String orderId) {
        for (Reservation reservation : reservationRepository.findByOrderId(orderId)) {
            if (reservation.isReserved()) {
                stockRepository.findById(reservation.getProductId())
                        .ifPresent(s -> s.release(reservation.getQuantity()));
                reservation.release();
            }
        }
    }

    /** Saga step 6: PaymentSucceeded -> reserved stock becomes sold. */
    @Transactional
    public void confirmForOrder(String orderId) {
        for (Reservation reservation : reservationRepository.findByOrderId(orderId)) {
            if (reservation.isReserved()) {
                stockRepository.findById(reservation.getProductId())
                        .ifPresent(s -> s.confirm(reservation.getQuantity()));
                reservation.confirm();
            }
        }
    }

    /** FR-13: admin adds stock. Creates the stock row if it does not exist yet. */
    @Transactional
    public Stock addStock(Long productId, int quantity) {
        Stock stock = stockRepository.findById(productId)
                .orElseGet(() -> stockRepository.save(new Stock(productId, 0)));
        stock.addStock(quantity);
        return stock;
    }

    @Transactional(readOnly = true)
    public Stock getStock(Long productId) {
        return stockRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("No stock for product " + productId));
    }
}

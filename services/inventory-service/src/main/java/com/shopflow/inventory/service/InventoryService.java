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
import java.util.LinkedHashMap;
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
     * All-or-nothing and all in ONE transaction: the check pass runs first, so
     * a failed line changes nothing and the StockReservationFailed event is a
     * normal business outcome, not an exception. If two orders race for the
     * last item, @Version on stock makes the loser's commit fail; the whole
     * transaction (including the processed_events mark) rolls back and the
     * Kafka retry sees fresh stock.
     */
    @Transactional
    public void reserveForOrder(String orderId, List<OrderLine> lines) {
        if (!reservationRepository.findByOrderId(orderId).isEmpty()) {
            return; // redelivery after a crash: this order was already handled
        }

        // pass 1: check every line before touching anything
        Map<Long, Stock> stocks = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            Stock stock = stockRepository.findById(line.productId()).orElse(null);
            if (stock == null || !stock.canReserve(line.quantity())) {
                log.info("Reservation failed for order {}: not enough stock for product {}",
                        orderId, line.productId());
                outbox.append(EventTypes.TOPIC_INVENTORY_EVENTS, orderId,
                        EventTypes.STOCK_RESERVATION_FAILED,
                        Map.of("reason", "Not enough stock for product " + line.productId()));
                return;
            }
            stocks.put(line.productId(), stock);
        }

        // pass 2: everything fits, reserve it all
        for (OrderLine line : lines) {
            stocks.get(line.productId()).reserve(line.quantity());
            reservationRepository.save(new Reservation(
                    orderId, line.productId(), line.quantity(), Instant.now().plus(reservationTtl)));
        }
        outbox.append(EventTypes.TOPIC_INVENTORY_EVENTS, orderId, EventTypes.STOCK_RESERVED, Map.of());
    }

    /**
     * Saga compensation: OrderCancelled -> give the stock back (BR-02).
     * A RESERVED line moves reserved -> available. A CONFIRMED line (the order
     * was paid, then refunded) was already sold, so the quantity goes straight
     * back to available.
     */
    @Transactional
    public void releaseForOrder(String orderId) {
        for (Reservation reservation : reservationRepository.findByOrderId(orderId)) {
            if (reservation.isReserved()) {
                stockRepository.findById(reservation.getProductId())
                        .ifPresent(s -> s.release(reservation.getQuantity()));
                reservation.release();
            } else if (reservation.getStatus() == Reservation.Status.CONFIRMED) {
                stockRepository.findById(reservation.getProductId())
                        .ifPresent(s -> s.addStock(reservation.getQuantity()));
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

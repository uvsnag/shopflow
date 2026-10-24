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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Concurrency model: every stock change takes {@code SELECT ... FOR UPDATE}
 * row locks — reservation lines of the order first, then the stock rows in
 * product id order — so concurrent saga events and jobs serialize on the rows
 * they touch and never deadlock. The @Version column on stock stays as a
 * second line of defence for any writer that forgets to lock.
 */
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
                            @Value("${shopflow.reservation-ttl:30m}") Duration reservationTtl) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.outbox = outbox;
        this.reservationTtl = reservationTtl;
    }

    public record OrderLine(Long productId, int quantity) {}

    /**
     * Saga step 2-3: OrderCreated -> try to reserve every line (BR-01, BR-02).
     * All-or-nothing and all in ONE transaction: the stock rows are locked,
     * the check pass runs first, so a failed line changes nothing and the
     * StockReservationFailed event is a normal business outcome, not an
     * exception. Two orders racing for the last item queue on the row lock:
     * the second one sees the first one's reservation and fails cleanly.
     */
    @Transactional
    public void reserveForOrder(String orderId, List<OrderLine> lines) {
        if (reservationRepository.existsByOrderId(orderId)) {
            return; // redelivery after a crash: this order was already handled
        }

        // one entry per product (sorted), so a product listed twice is checked against its total
        Map<Long, Integer> wanted = new TreeMap<>();
        for (OrderLine line : lines) {
            wanted.merge(line.productId(), line.quantity(), Integer::sum);
        }
        Map<Long, Stock> stocks = lockStocks(List.copyOf(wanted.keySet()));

        // pass 1: check every line before touching anything
        for (var line : wanted.entrySet()) {
            Stock stock = stocks.get(line.getKey());
            if (stock == null || !stock.canReserve(line.getValue())) {
                log.info("Reservation failed for order {}: not enough stock for product {}",
                        orderId, line.getKey());
                outbox.append(EventTypes.TOPIC_INVENTORY_EVENTS, orderId,
                        EventTypes.STOCK_RESERVATION_FAILED,
                        Map.of("reason", "Not enough stock for product " + line.getKey()));
                return;
            }
        }

        // pass 2: everything fits, reserve it all
        Instant expiresAt = Instant.now().plus(reservationTtl);
        for (var line : wanted.entrySet()) {
            stocks.get(line.getKey()).reserve(line.getValue());
            reservationRepository.save(new Reservation(orderId, line.getKey(), line.getValue(), expiresAt));
        }
        outbox.append(EventTypes.TOPIC_INVENTORY_EVENTS, orderId, EventTypes.STOCK_RESERVED, Map.of());
    }

    /**
     * Saga compensation: OrderCancelled -> give the stock back (BR-02).
     * A RESERVED line moves reserved -> available. A CONFIRMED line (the order
     * was paid, then refunded) was already sold, so the quantity goes straight
     * back to available. RELEASED lines are skipped, so a repeated
     * OrderCancelled is harmless.
     */
    @Transactional
    public void releaseForOrder(String orderId) {
        List<Reservation> lines = reservationRepository.lockByOrderId(orderId).stream()
                .filter(r -> r.getStatus() != Reservation.Status.RELEASED)
                .toList();
        Map<Long, Stock> stocks = lockStocks(productIds(lines));
        for (Reservation reservation : lines) {
            Stock stock = stocks.get(reservation.getProductId());
            if (stock != null) {
                if (reservation.isReserved()) {
                    stock.release(reservation.getQuantity());
                } else {
                    stock.addStock(reservation.getQuantity());
                }
            }
            reservation.release();
        }
    }

    /**
     * Saga step 6: PaymentSucceeded -> reserved stock becomes sold.
     * Lines that were already released are left alone. Normally that is a
     * payment that landed after the order was cancelled — order-service has
     * it refunded. Taking the stock again here could oversell other orders
     * and would never be given back if the refund's OrderCancelled was
     * processed first, so it is only logged.
     */
    @Transactional
    public void confirmForOrder(String orderId) {
        List<Reservation> lines = reservationRepository.lockByOrderId(orderId);
        List<Reservation> reserved = lines.stream().filter(Reservation::isReserved).toList();
        Map<Long, Stock> stocks = lockStocks(productIds(reserved));
        for (Reservation reservation : reserved) {
            Stock stock = stocks.get(reservation.getProductId());
            if (stock != null) {
                stock.confirm(reservation.getQuantity());
            }
            reservation.confirm();
        }
        if (reserved.isEmpty() && !lines.isEmpty()
                && lines.stream().allMatch(r -> r.getStatus() == Reservation.Status.RELEASED)) {
            log.warn("Order {} was paid after its reservation was released; expecting a refund for it", orderId);
        }
    }

    /**
     * NFR-12 safety net (see {@link ExpiredReservationJob}): release the lines
     * of one order whose time ran out and that are still RESERVED. Runs in its
     * own transaction per order and takes the same locks as the saga handlers.
     */
    @Transactional
    public int releaseExpiredForOrder(String orderId, Instant now) {
        List<Reservation> expired = reservationRepository.lockByOrderId(orderId).stream()
                .filter(r -> r.isReserved() && r.getExpiresAt().isBefore(now))
                .toList();
        Map<Long, Stock> stocks = lockStocks(productIds(expired));
        for (Reservation reservation : expired) {
            Stock stock = stocks.get(reservation.getProductId());
            if (stock != null) {
                stock.release(reservation.getQuantity());
            }
            reservation.release();
            log.info("Released expired reservation {} (order {})", reservation.getId(), orderId);
        }
        return expired.size();
    }

    /** FR-13: admin adds stock. Creates the stock row if it does not exist yet. */
    @Transactional
    public Stock addStock(Long productId, int quantity) {
        stockRepository.insertIfAbsent(productId);
        Stock stock = stockRepository.lockById(productId)
                .orElseThrow(() -> new IllegalStateException("Stock row for product " + productId + " vanished"));
        stock.addStock(quantity);
        return stock;
    }

    @Transactional(readOnly = true)
    public Stock getStock(Long productId) {
        return stockRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("No stock for product " + productId));
    }

    private Map<Long, Stock> lockStocks(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return stockRepository.lockAllByIds(productIds).stream()
                .collect(Collectors.toMap(Stock::getProductId, Function.identity()));
    }

    private static List<Long> productIds(List<Reservation> reservations) {
        return reservations.stream().map(Reservation::getProductId).distinct().sorted().toList();
    }
}

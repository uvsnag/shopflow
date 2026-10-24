package com.shopflow.inventory;

import com.shopflow.common.event.EventTypes;
import com.shopflow.common.outbox.OutboxRepository;
import com.shopflow.inventory.domain.Reservation;
import com.shopflow.inventory.domain.Stock;
import com.shopflow.inventory.repository.ReservationRepository;
import com.shopflow.inventory.repository.StockRepository;
import com.shopflow.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Must-have test case: two customers buy the last item at the same time —
 * only one order gets it (BR-01). The stock row is locked FOR UPDATE, so the
 * loser waits, then sees the winner's reservation and fails cleanly with a
 * StockReservationFailed event — no lock exception, no Kafka retry needed.
 * Real PostgreSQL via Testcontainers; skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",   // no broker in this test
        "shopflow.outbox.poll-ms=3600000",
        "shopflow.expired-reservation-poll-ms=3600000",
        "management.tracing.enabled=false"
})
class ConcurrentReservationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired InventoryService inventoryService;
    @Autowired StockRepository stockRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired TransactionTemplate tx;

    @Test
    void flywaySeededTheStock() {
        assertEquals(50, stockRepository.findById(1L).orElseThrow().getAvailable());
    }

    @Test
    void twoOrdersForTheLastItemOnlyOneWins() throws Exception {
        long productId = 9_001L;
        tx.executeWithoutResult(s -> stockRepository.save(new Stock(productId, 1)));

        int racers = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        AtomicInteger lockConflicts = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < racers; i++) {
            String orderId = "race-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    inventoryService.reserveForOrder(orderId, List.of(new InventoryService.OrderLine(productId, 1)));
                } catch (OptimisticLockingFailureException e) {
                    lockConflicts.incrementAndGet(); // the loser rolls back; Kafka would redeliver
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Stock stock = stockRepository.findById(productId).orElseThrow();
        assertEquals(0, stock.getAvailable());
        assertEquals(1, stock.getReserved(), "never oversold");

        long reserved = reservationRepository.findAll().stream()
                .filter(r -> r.getProductId().equals(productId) && r.getStatus() == Reservation.Status.RESERVED)
                .count();
        assertEquals(1, reserved, "exactly one reservation");

        long reservedEvents = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().startsWith("race-") && e.getEventType().equals(EventTypes.STOCK_RESERVED))
                .count();
        assertEquals(1, reservedEvents, "exactly one StockReserved event");
        long failedEvents = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().startsWith("race-") && e.getEventType().equals(EventTypes.STOCK_RESERVATION_FAILED))
                .count();
        assertEquals(1, failedEvents, "the loser got a clean StockReservationFailed");
        assertEquals(0, lockConflicts.get(), "row lock: the loser waited instead of failing on @Version");
    }
}

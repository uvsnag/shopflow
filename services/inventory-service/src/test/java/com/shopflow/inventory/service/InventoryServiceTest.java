package com.shopflow.inventory.service;

import com.shopflow.common.event.EventTypes;
import com.shopflow.common.outbox.OutboxService;
import com.shopflow.inventory.domain.Reservation;
import com.shopflow.inventory.domain.Stock;
import com.shopflow.inventory.repository.ReservationRepository;
import com.shopflow.inventory.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-01 / BR-02: all-or-nothing reservation, release and confirm, all under row locks. */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock StockRepository stockRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock OutboxService outbox;

    InventoryService service;
    Stock mouse;
    Stock keyboard;

    @BeforeEach
    void setUp() {
        service = new InventoryService(stockRepository, reservationRepository, outbox, Duration.ofMinutes(30));
        mouse = new Stock(1L, 5);
        keyboard = new Stock(2L, 0);
    }

    @Test
    void reservesEveryLineUnderLockAndEmitsStockReserved() {
        when(stockRepository.lockAllByIds(List.of(1L))).thenReturn(List.of(mouse));

        service.reserveForOrder("42", List.of(new InventoryService.OrderLine(1L, 2)));

        assertEquals(3, mouse.getAvailable());
        assertEquals(2, mouse.getReserved());
        verify(reservationRepository).save(argThat(r -> r.getOrderId().equals("42")
                && r.getProductId().equals(1L) && r.getQuantity() == 2 && r.isReserved()
                && r.getExpiresAt().isAfter(Instant.now().plus(Duration.ofMinutes(29)))));
        verify(outbox).append(EventTypes.TOPIC_INVENTORY_EVENTS, "42", EventTypes.STOCK_RESERVED, Map.of());
        verify(stockRepository, never()).findById(any()); // never an unlocked read before a write
    }

    @Test
    void oneShortLineMeansNothingIsReserved() {
        when(stockRepository.lockAllByIds(List.of(1L, 2L))).thenReturn(List.of(mouse, keyboard));

        service.reserveForOrder("42", List.of(
                new InventoryService.OrderLine(2L, 1),
                new InventoryService.OrderLine(1L, 1)));

        assertEquals(5, mouse.getAvailable(), "the line that fit was NOT touched");
        verify(reservationRepository, never()).save(any());
        verify(outbox).append(eq(EventTypes.TOPIC_INVENTORY_EVENTS), eq("42"),
                eq(EventTypes.STOCK_RESERVATION_FAILED), argThat(p -> p.get("reason").toString().contains("product 2")));
    }

    @Test
    void sameProductOnTwoLinesIsCheckedAgainstItsTotal() {
        when(stockRepository.lockAllByIds(List.of(1L))).thenReturn(List.of(mouse)); // 5 available

        service.reserveForOrder("42", List.of(
                new InventoryService.OrderLine(1L, 3),
                new InventoryService.OrderLine(1L, 3)));

        assertEquals(5, mouse.getAvailable());
        verify(outbox).append(eq(EventTypes.TOPIC_INVENTORY_EVENTS), eq("42"), eq(EventTypes.STOCK_RESERVATION_FAILED), any());
    }

    @Test
    void unknownProductFailsCleanly() {
        when(stockRepository.lockAllByIds(List.of(99L))).thenReturn(List.of());

        service.reserveForOrder("42", List.of(new InventoryService.OrderLine(99L, 1)));

        verify(outbox).append(eq(EventTypes.TOPIC_INVENTORY_EVENTS), eq("42"), eq(EventTypes.STOCK_RESERVATION_FAILED), any());
    }

    @Test
    void redeliveryOfAlreadyHandledOrderDoesNothing() {
        when(reservationRepository.existsByOrderId("42")).thenReturn(true);

        service.reserveForOrder("42", List.of(new InventoryService.OrderLine(1L, 1)));

        verifyNoInteractions(stockRepository, outbox);
    }

    @Test
    void releaseLocksLinesBeforeStockAndReturnsReservedAndRefundedSoldStock() {
        Reservation reserved = new Reservation("42", 1L, 2, Instant.now());
        Reservation confirmed = new Reservation("42", 2L, 1, Instant.now());
        confirmed.confirm();
        Reservation released = new Reservation("42", 1L, 9, Instant.now());
        released.release();
        mouse.reserve(2);
        when(reservationRepository.lockByOrderId("42")).thenReturn(List.of(reserved, confirmed, released));
        when(stockRepository.lockAllByIds(List.of(1L, 2L))).thenReturn(List.of(mouse, keyboard));

        service.releaseForOrder("42");

        assertEquals(5, mouse.getAvailable());
        assertEquals(0, mouse.getReserved());
        assertEquals(1, keyboard.getAvailable(), "refunded item goes back on the shelf");
        assertEquals(Reservation.Status.RELEASED, reserved.getStatus());
        assertEquals(Reservation.Status.RELEASED, confirmed.getStatus());
        InOrder lockOrder = inOrder(reservationRepository, stockRepository);
        lockOrder.verify(reservationRepository).lockByOrderId("42");
        lockOrder.verify(stockRepository).lockAllByIds(any());
    }

    @Test
    void repeatedReleaseIsHarmless() {
        Reservation released = new Reservation("42", 1L, 2, Instant.now());
        released.release();
        when(reservationRepository.lockByOrderId("42")).thenReturn(List.of(released));

        service.releaseForOrder("42");

        verifyNoInteractions(stockRepository);
    }

    @Test
    void confirmTurnsReservedIntoSold() {
        Reservation reserved = new Reservation("42", 1L, 2, Instant.now());
        mouse.reserve(2);
        when(reservationRepository.lockByOrderId("42")).thenReturn(List.of(reserved));
        when(stockRepository.lockAllByIds(List.of(1L))).thenReturn(List.of(mouse));

        service.confirmForOrder("42");

        assertEquals(3, mouse.getAvailable());
        assertEquals(0, mouse.getReserved());
        assertEquals(Reservation.Status.CONFIRMED, reserved.getStatus());
    }

    @Test
    void confirmAfterReleaseDoesNotTouchStock() {
        // OrderCancelled was processed first: the payment is late and will be refunded
        Reservation released = new Reservation("42", 1L, 2, Instant.now());
        released.release();
        when(reservationRepository.lockByOrderId("42")).thenReturn(List.of(released));

        service.confirmForOrder("42");

        assertEquals(Reservation.Status.RELEASED, released.getStatus());
        verifyNoInteractions(stockRepository);
    }

    @Test
    void expiryReleasesOnlyExpiredReservedLines() {
        Instant now = Instant.now();
        Reservation expired = new Reservation("42", 1L, 2, now.minusSeconds(1));
        Reservation notYet = new Reservation("42", 2L, 1, now.plusSeconds(60));
        mouse.reserve(2);
        when(reservationRepository.lockByOrderId("42")).thenReturn(List.of(expired, notYet));
        when(stockRepository.lockAllByIds(List.of(1L))).thenReturn(List.of(mouse));

        assertEquals(1, service.releaseExpiredForOrder("42", now));

        assertEquals(5, mouse.getAvailable());
        assertEquals(Reservation.Status.RELEASED, expired.getStatus());
        assertTrue(notYet.isReserved());
    }

    @Test
    void expiryDoesNotReleaseALineConfirmedMeanwhile() {
        Instant now = Instant.now();
        Reservation confirmed = new Reservation("42", 1L, 2, now.minusSeconds(1));
        confirmed.confirm(); // PaymentSucceeded won the lock
        when(reservationRepository.lockByOrderId("42")).thenReturn(List.of(confirmed));

        assertEquals(0, service.releaseExpiredForOrder("42", now));
        assertEquals(Reservation.Status.CONFIRMED, confirmed.getStatus());
    }

    @Test
    void addStockCreatesTheRowAtomicallyThenLocksIt() {
        Stock created = new Stock(7L, 0);
        when(stockRepository.lockById(7L)).thenReturn(Optional.of(created));

        Stock stock = service.addStock(7L, 10);

        assertEquals(10, stock.getAvailable());
        InOrder order = inOrder(stockRepository);
        order.verify(stockRepository).insertIfAbsent(7L);
        order.verify(stockRepository).lockById(7L);
    }
}

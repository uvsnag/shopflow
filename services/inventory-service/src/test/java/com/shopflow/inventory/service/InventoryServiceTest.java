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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-01 / BR-02: all-or-nothing reservation, release and confirm. */
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
        service = new InventoryService(stockRepository, reservationRepository, outbox, Duration.ofMinutes(15));
        mouse = new Stock(1L, 5);
        keyboard = new Stock(2L, 0);
    }

    @Test
    void reservesEveryLineAndEmitsStockReserved() {
        when(reservationRepository.findByOrderId("42")).thenReturn(List.of());
        when(stockRepository.findById(1L)).thenReturn(Optional.of(mouse));

        service.reserveForOrder("42", List.of(new InventoryService.OrderLine(1L, 2)));

        assertEquals(3, mouse.getAvailable());
        assertEquals(2, mouse.getReserved());
        verify(reservationRepository).save(argThat(r -> r.getOrderId().equals("42")
                && r.getProductId().equals(1L) && r.getQuantity() == 2 && r.isReserved()
                && r.getExpiresAt().isAfter(Instant.now().plus(Duration.ofMinutes(14)))));
        verify(outbox).append(EventTypes.TOPIC_INVENTORY_EVENTS, "42", EventTypes.STOCK_RESERVED, java.util.Map.of());
    }

    @Test
    void oneShortLineMeansNothingIsReserved() {
        when(reservationRepository.findByOrderId("42")).thenReturn(List.of());
        when(stockRepository.findById(1L)).thenReturn(Optional.of(mouse));
        when(stockRepository.findById(2L)).thenReturn(Optional.of(keyboard));

        service.reserveForOrder("42", List.of(
                new InventoryService.OrderLine(1L, 1),
                new InventoryService.OrderLine(2L, 1)));

        assertEquals(5, mouse.getAvailable(), "the line that fit was NOT touched");
        verify(reservationRepository, never()).save(any());
        verify(outbox).append(eq(EventTypes.TOPIC_INVENTORY_EVENTS), eq("42"),
                eq(EventTypes.STOCK_RESERVATION_FAILED), argThat(p -> p.get("reason").toString().contains("product 2")));
    }

    @Test
    void unknownProductFailsCleanly() {
        when(reservationRepository.findByOrderId("42")).thenReturn(List.of());
        when(stockRepository.findById(99L)).thenReturn(Optional.empty());

        service.reserveForOrder("42", List.of(new InventoryService.OrderLine(99L, 1)));

        verify(outbox).append(eq(EventTypes.TOPIC_INVENTORY_EVENTS), eq("42"), eq(EventTypes.STOCK_RESERVATION_FAILED), any());
    }

    @Test
    void redeliveryOfAlreadyHandledOrderDoesNothing() {
        when(reservationRepository.findByOrderId("42"))
                .thenReturn(List.of(new Reservation("42", 1L, 1, Instant.now())));

        service.reserveForOrder("42", List.of(new InventoryService.OrderLine(1L, 1)));

        verifyNoInteractions(stockRepository, outbox);
    }

    @Test
    void releaseReturnsReservedStockAndRefundedSoldStock() {
        Reservation reserved = new Reservation("42", 1L, 2, Instant.now());
        Reservation confirmed = new Reservation("42", 2L, 1, Instant.now());
        confirmed.confirm();
        Reservation released = new Reservation("42", 1L, 9, Instant.now());
        released.release();
        mouse.reserve(2);
        when(reservationRepository.findByOrderId("42")).thenReturn(List.of(reserved, confirmed, released));
        when(stockRepository.findById(1L)).thenReturn(Optional.of(mouse));
        when(stockRepository.findById(2L)).thenReturn(Optional.of(keyboard));

        service.releaseForOrder("42");

        assertEquals(5, mouse.getAvailable());
        assertEquals(0, mouse.getReserved());
        assertEquals(1, keyboard.getAvailable(), "refunded item goes back on the shelf");
        assertEquals(Reservation.Status.RELEASED, reserved.getStatus());
        assertEquals(Reservation.Status.RELEASED, confirmed.getStatus());
    }

    @Test
    void confirmTurnsReservedIntoSold() {
        Reservation reserved = new Reservation("42", 1L, 2, Instant.now());
        mouse.reserve(2);
        when(reservationRepository.findByOrderId("42")).thenReturn(List.of(reserved));
        when(stockRepository.findById(1L)).thenReturn(Optional.of(mouse));

        service.confirmForOrder("42");

        assertEquals(3, mouse.getAvailable());
        assertEquals(0, mouse.getReserved());
        assertEquals(Reservation.Status.CONFIRMED, reserved.getStatus());
    }

    @Test
    void addStockCreatesTheRowOnFirstUse() {
        when(stockRepository.findById(7L)).thenReturn(Optional.empty());
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

        Stock stock = service.addStock(7L, 10);

        assertEquals(10, stock.getAvailable());
    }
}

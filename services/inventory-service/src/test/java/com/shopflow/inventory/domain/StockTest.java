package com.shopflow.inventory.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the stock math (see plan: Testing / Unit). */
class StockTest {

    @Test
    void reserveMovesAvailableToReserved() {
        Stock stock = new Stock(1L, 10);
        stock.reserve(3);
        assertEquals(7, stock.getAvailable());
        assertEquals(3, stock.getReserved());
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        Stock stock = new Stock(1L, 2);
        assertThrows(IllegalStateException.class, () -> stock.reserve(3));
    }

    @Test
    void releaseGivesStockBack() {
        Stock stock = new Stock(1L, 10);
        stock.reserve(4);
        stock.release(4);
        assertEquals(10, stock.getAvailable());
        assertEquals(0, stock.getReserved());
    }

    @Test
    void confirmRemovesReservedWithoutReturningIt() {
        Stock stock = new Stock(1L, 10);
        stock.reserve(4);
        stock.confirm(4);
        assertEquals(6, stock.getAvailable());
        assertEquals(0, stock.getReserved());
    }
}

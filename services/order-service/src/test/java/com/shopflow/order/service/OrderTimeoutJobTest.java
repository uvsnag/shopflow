package com.shopflow.order.service;

import com.shopflow.order.domain.OrderStatus;
import com.shopflow.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-03 / NFR-12: unpaid orders older than the timeout are cancelled, one failure does not stop the rest. */
class OrderTimeoutJobTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderService orderService = mock(OrderService.class);
    private final OrderTimeoutJob job = new OrderTimeoutJob(repository, orderService, Duration.ofMinutes(15));

    @Test
    void cancelsEveryStaleOrderEvenIfOneFails() {
        when(repository.findIdsByStatusInAndCreatedAtBefore(
                eq(List.of(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED)), any()))
                .thenReturn(List.of(1L, 2L));
        doThrow(new RuntimeException("lock timeout"))
                .when(orderService).cancelIfUnpaid(eq(1L), any(Instant.class));

        job.cancelUnpaidOrders();

        verify(orderService).cancelIfUnpaid(eq(1L), any(Instant.class));
        verify(orderService).cancelIfUnpaid(eq(2L), any(Instant.class));
    }

    @Test
    void cutoffIsTheTimeoutAgo() {
        when(repository.findIdsByStatusInAndCreatedAtBefore(any(), any())).thenReturn(List.of(1L));

        job.cancelUnpaidOrders();

        verify(orderService).cancelIfUnpaid(eq(1L), argThat(cutoff ->
                cutoff.isBefore(Instant.now().minus(Duration.ofMinutes(14)))
                        && cutoff.isAfter(Instant.now().minus(Duration.ofMinutes(16)))));
    }
}

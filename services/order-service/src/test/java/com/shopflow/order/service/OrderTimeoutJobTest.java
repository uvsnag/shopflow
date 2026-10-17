package com.shopflow.order.service;

import com.shopflow.order.domain.Order;
import com.shopflow.order.domain.OrderStatus;
import com.shopflow.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
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
        Order a = order(1L);
        Order b = order(2L);
        when(repository.findByStatusInAndCreatedAtBefore(
                eq(List.of(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED)), any()))
                .thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("already paid"))
                .when(orderService).changeStatus(1L, OrderStatus.CANCELLED, "payment timeout");

        job.cancelUnpaidOrders();

        verify(orderService).changeStatus(1L, OrderStatus.CANCELLED, "payment timeout");
        verify(orderService).changeStatus(2L, OrderStatus.CANCELLED, "payment timeout");
    }

    private static Order order(Long id) {
        Order o = new Order(1L, "k" + id);
        ReflectionTestUtils.setField(o, "id", id);
        return o;
    }
}

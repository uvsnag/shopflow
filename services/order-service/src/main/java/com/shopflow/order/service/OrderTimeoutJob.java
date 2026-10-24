package com.shopflow.order.service;

import com.shopflow.order.domain.OrderStatus;
import com.shopflow.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * BR-03 / NFR-12: unpaid orders are cancelled automatically after 15 minutes.
 * Each order is cancelled in its own transaction and re-checked under its row
 * lock ({@link OrderService#cancelIfUnpaid}), so a payment that lands between
 * the listing and the cancel wins, and two replicas running the job at the
 * same time cancel each order once.
 */
@Component
public class OrderTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutJob.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final Duration paymentTimeout;

    public OrderTimeoutJob(OrderRepository orderRepository,
                           OrderService orderService,
                           @Value("${shopflow.payment-timeout:15m}") Duration paymentTimeout) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.paymentTimeout = paymentTimeout;
    }

    @Scheduled(fixedDelayString = "${shopflow.timeout-poll-ms:60000}")
    public void cancelUnpaidOrders() {
        Instant cutoff = Instant.now().minus(paymentTimeout);
        List<Long> stale = orderRepository.findIdsByStatusInAndCreatedAtBefore(
                List.of(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED), cutoff);
        for (Long orderId : stale) {
            try {
                orderService.cancelIfUnpaid(orderId, cutoff);
            } catch (Exception e) {
                log.warn("Could not cancel stale order {}, will retry", orderId, e);
            }
        }
    }
}

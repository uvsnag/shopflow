package com.shopflow.order.config;

import com.shopflow.order.domain.OrderStatus;
import com.shopflow.order.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

/**
 * NFR-20: exports {@code shopflow_orders{status="PAID"}} etc. to Prometheus so
 * Grafana can show orders per status. Refreshed on a timer instead of on every
 * request: the count query is cheap but not free.
 */
@Component
public class OrderMetrics {

    private final OrderRepository orderRepository;
    private final MultiGauge ordersByStatus;

    public OrderMetrics(MeterRegistry registry, OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
        this.ordersByStatus = MultiGauge.builder("shopflow.orders")
                .description("Number of orders per status")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${shopflow.metrics-poll-ms:15000}", initialDelay = 5000)
    @Transactional(readOnly = true)
    public void refresh() {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            counts.put(status, 0L);
        }
        for (Object[] row : orderRepository.countByStatus()) {
            counts.put((OrderStatus) row[0], ((Number) row[1]).longValue());
        }
        ordersByStatus.register(counts.entrySet().stream()
                .map(e -> MultiGauge.Row.of(Tags.of("status", e.getKey().name()), e.getValue()))
                .toList(), true);
    }
}

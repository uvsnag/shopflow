package com.shopflow.order.service;

import com.shopflow.common.event.EventTypes;
import com.shopflow.common.outbox.OutboxService;
import com.shopflow.common.web.BadRequestException;
import com.shopflow.common.web.NotFoundException;
import com.shopflow.order.client.ProductClient;
import com.shopflow.order.domain.Cart;
import com.shopflow.order.domain.Order;
import com.shopflow.order.domain.OrderStatus;
import com.shopflow.order.domain.OrderStatusHistory;
import com.shopflow.order.repository.OrderRepository;
import com.shopflow.order.repository.OrderStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final CartService cartService;
    private final ProductClient productClient;
    private final OutboxService outbox;

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository historyRepository,
                        CartService cartService,
                        ProductClient productClient,
                        OutboxService outbox) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.cartService = cartService;
        this.productClient = productClient;
        this.outbox = outbox;
    }

    /**
     * FR-07: place an order from the cart. Saga step 1: save order PENDING and
     * OrderCreated in the outbox in one transaction (NFR-08). The same
     * Idempotency-Key returns the same order instead of a new one (NFR-10).
     */
    @Transactional
    public Order placeOrder(Long userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Cart cart = cartService.getOrCreate(userId);
        if (cart.getItems().isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }

        Order order = new Order(userId, idempotencyKey);
        for (var cartItem : cart.getItems()) {
            // BR-05: copy name and price at order time
            ProductClient.ProductInfo product = productClient.getProduct(cartItem.getProductId());
            if (product == null || !product.active()) {
                throw new BadRequestException("Product " + cartItem.getProductId() + " is not available");
            }
            order.addItem(product.id(), product.name(), product.price(), cartItem.getQuantity());
        }
        order = orderRepository.save(order);
        cart.clear();

        historyRepository.save(new OrderStatusHistory(order.getId(), null, OrderStatus.PENDING, "order placed"));
        outbox.append(EventTypes.TOPIC_ORDER_EVENTS, String.valueOf(order.getId()), EventTypes.ORDER_CREATED,
                Map.of("userId", userId,
                        "totalAmount", order.getTotalAmount(),
                        "items", order.getItems().stream()
                                .map(i -> Map.<String, Object>of(
                                        "productId", i.getProductId(),
                                        "quantity", i.getQuantity()))
                                .collect(Collectors.toList())));
        return order;
    }

    /** Central place for every status change: validates, records history, emits events. */
    @Transactional
    public void changeStatus(Long orderId, OrderStatus next, String reason) {
        Order order = find(orderId);
        OrderStatus from = order.getStatus();
        if (from == next) {
            return; // duplicate saga event, nothing to do
        }
        order.transitionTo(next);
        historyRepository.save(new OrderStatusHistory(orderId, from, next, reason));

        String key = String.valueOf(orderId);
        switch (next) {
            case CANCELLED -> outbox.append(EventTypes.TOPIC_ORDER_EVENTS, key,
                    EventTypes.ORDER_CANCELLED, Map.of("reason", reason, "userId", order.getUserId(),
                            "previousStatus", from.name()));
            case SHIPPED -> outbox.append(EventTypes.TOPIC_ORDER_EVENTS, key,
                    EventTypes.ORDER_SHIPPED, Map.of("userId", order.getUserId()));
            case DELIVERED -> outbox.append(EventTypes.TOPIC_ORDER_EVENTS, key,
                    EventTypes.ORDER_DELIVERED, Map.of("userId", order.getUserId()));
            default -> { /* PAID / STOCK_RESERVED / REFUNDED come from other services' events */ }
        }
        log.info("Order {} {} -> {} ({})", orderId, from, next, reason);
    }

    /** FR-10: customer cancels. Paid orders are refunded via OrderCancelled -> payment-service. */
    @Transactional
    public void customerCancel(Long orderId, Long userId) {
        Order order = find(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new NotFoundException("Order " + orderId + " not found"); // BR-06: don't leak others' orders
        }
        switch (order.getStatus()) {
            case PENDING, STOCK_RESERVED -> changeStatus(orderId, OrderStatus.CANCELLED, "customer cancel");
            case PAID -> changeStatus(orderId, OrderStatus.REFUNDED, "customer cancel after payment");
            default -> throw new BadRequestException("Order cannot be cancelled in status " + order.getStatus());
        }
        if (order.getStatus() == OrderStatus.REFUNDED) {
            // tell payment-service to refund and inventory to release (compensation)
            outbox.append(EventTypes.TOPIC_ORDER_EVENTS, String.valueOf(orderId),
                    EventTypes.ORDER_CANCELLED, Map.of("reason", "customer cancel after payment",
                            "userId", order.getUserId(), "previousStatus", "PAID"));
        }
    }

    @Transactional(readOnly = true)
    public Order getOwn(Long orderId, Long userId) {
        Order order = find(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new NotFoundException("Order " + orderId + " not found"); // BR-06
        }
        return order;
    }

    public Order find(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
    }
}

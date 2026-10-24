package com.shopflow.order.service;

import com.shopflow.common.event.EventTypes;
import com.shopflow.common.outbox.OutboxService;
import com.shopflow.common.web.BadRequestException;
import com.shopflow.common.web.ConflictException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Concurrency model: every status change re-reads the order with
 * {@code SELECT ... FOR UPDATE} ({@link OrderRepository#lockById}), so saga
 * events from two topics, the timeout job, the customer and the admin are
 * applied one after another on fresh data.
 *
 * Saga events are applied tolerantly: events for one order come from
 * different topics (inventory-events, payment-events) and different consumer
 * groups, so they can arrive "late" relative to what this service already
 * decided. An event that no longer fits the current status is recorded in the
 * log and ignored instead of throwing — throwing would only retry it until it
 * lands in the DLT, since waiting never makes a stale event valid.
 * Commands from people (admin ship, customer cancel) stay strict and answer 409.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final CartService cartService;
    private final ProductClient productClient;
    private final OutboxService outbox;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository historyRepository,
                        CartService cartService,
                        ProductClient productClient,
                        OutboxService outbox,
                        TransactionTemplate tx) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.cartService = cartService;
        this.productClient = productClient;
        this.outbox = outbox;
        this.tx = tx;
    }

    // ---------------------------------------------------------------- place order

    /**
     * FR-07: place an order from the cart. Saga step 1: save order PENDING and
     * OrderCreated in the outbox in one transaction (NFR-08). The same user
     * sending the same Idempotency-Key gets the same order back (NFR-10).
     *
     * <ol>
     *   <li>Prices are fetched from product-service BEFORE the transaction
     *       opens, so no DB connection or row lock is held while waiting on
     *       another service (up to 5s per product with the client timeouts).</li>
     *   <li>The transaction then locks the cart row. A second checkout of the
     *       same cart (double click, two tabs, a retried request) waits here.</li>
     *   <li>Under the lock the Idempotency-Key is checked again — the winner
     *       has committed by now — and the cart is compared with what was
     *       priced; if it changed in between, the client gets a 409 and retries
     *       instead of an order whose items and prices do not match.</li>
     *   <li>The unique (user_id, idempotency_key) constraint stays the final
     *       arbiter; the loser re-reads and returns the winner's order.</li>
     * </ol>
     */
    public Order placeOrder(Long userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        var existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Map<Long, Integer> pricedCart = cartService.contents(userId);
        if (pricedCart.isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }
        Map<Long, ProductClient.ProductInfo> products = new LinkedHashMap<>();
        for (Long productId : pricedCart.keySet()) {
            // BR-05: copy name and price at order time
            ProductClient.ProductInfo product = productClient.getProduct(productId);
            if (product == null || !product.active()) {
                throw new BadRequestException("Product " + productId + " is not available");
            }
            products.put(productId, product);
        }

        try {
            return tx.execute(status -> createOrder(userId, idempotencyKey, pricedCart, products));
        } catch (DataIntegrityViolationException raceLost) {
            return orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> raceLost); // some other constraint: surfaces as 409
        }
    }

    private Order createOrder(Long userId, String idempotencyKey, Map<Long, Integer> pricedCart,
                              Map<Long, ProductClient.ProductInfo> products) {
        Cart cart = cartService.lock(userId).orElse(null);

        // a concurrent request with the same key may have won while we waited for the lock
        var winner = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (winner.isPresent()) {
            return winner.get();
        }
        if (cart == null || cart.getItems().isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }
        if (!CartService.contentsOf(cart).equals(pricedCart)) {
            throw new ConflictException("The cart changed while the order was being placed; please retry");
        }

        Order order = new Order(userId, idempotencyKey);
        pricedCart.forEach((productId, quantity) -> {
            ProductClient.ProductInfo product = products.get(productId);
            order.addItem(product.id(), product.name(), product.price(), quantity);
        });
        Order saved = orderRepository.save(order);
        cart.clear();

        historyRepository.save(new OrderStatusHistory(saved.getId(), null, OrderStatus.PENDING, "order placed"));
        outbox.append(EventTypes.TOPIC_ORDER_EVENTS, String.valueOf(saved.getId()), EventTypes.ORDER_CREATED,
                Map.of("userId", userId,
                        "totalAmount", saved.getTotalAmount(),
                        "items", saved.getItems().stream()
                                .map(i -> Map.<String, Object>of(
                                        "productId", i.getProductId(),
                                        "quantity", i.getQuantity()))
                                .collect(Collectors.toList())));
        return saved;
    }

    // ---------------------------------------------------------------- commands (strict)

    /**
     * Status change requested by a person or a job (admin ship / deliver).
     * Strict: an illegal transition throws IllegalStateException (HTTP 409).
     */
    @Transactional
    public void changeStatus(Long orderId, OrderStatus next, String reason) {
        Order order = lock(orderId);
        if (order.getStatus() == next) {
            return; // repeated request, nothing to do
        }
        transition(order, next, reason);
    }

    /** FR-10: customer cancels. Paid orders are refunded via OrderCancelled -> payment-service. */
    @Transactional
    public void customerCancel(Long orderId, Long userId) {
        Order order = lock(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new NotFoundException("Order " + orderId + " not found"); // BR-06: don't leak others' orders
        }
        switch (order.getStatus()) {
            case PENDING, STOCK_RESERVED -> transition(order, OrderStatus.CANCELLED, "customer cancel");
            case PAID -> {
                transition(order, OrderStatus.REFUNDED, "customer cancel after payment");
                // tell payment-service to refund and inventory to release (compensation)
                emitCancelled(order, "customer cancel after payment", OrderStatus.PAID);
            }
            default -> throw new BadRequestException("Order cannot be cancelled in status " + order.getStatus());
        }
    }

    /**
     * BR-03 / NFR-12: cancel the order if it is STILL unpaid and older than the
     * cutoff. Re-checked under the row lock, so a payment that landed after
     * the job listed the order is never overwritten by the timeout.
     *
     * @return true if the order was cancelled
     */
    @Transactional
    public boolean cancelIfUnpaid(Long orderId, Instant cutoff) {
        Order order = lock(orderId);
        if (!order.isUnpaidAndOlderThan(cutoff)) {
            return false;
        }
        transition(order, OrderStatus.CANCELLED, "payment timeout");
        return true;
    }

    // ---------------------------------------------------------------- saga events (tolerant)

    /** Saga step 3 (order side). Stale if the order was cancelled meanwhile: inventory releases on OrderCancelled. */
    @Transactional
    public void onStockReserved(Long orderId) {
        Order order = lock(orderId);
        if (order.getStatus() == OrderStatus.PENDING) {
            transition(order, OrderStatus.STOCK_RESERVED, "stock reserved");
        } else {
            ignoreStale(order, EventTypes.STOCK_RESERVED);
        }
    }

    @Transactional
    public void onStockReservationFailed(Long orderId) {
        Order order = lock(orderId);
        if (order.getStatus() == OrderStatus.PENDING) {
            transition(order, OrderStatus.CANCELLED, "out of stock");
        } else {
            ignoreStale(order, EventTypes.STOCK_RESERVATION_FAILED);
        }
    }

    /**
     * Saga step 5 (order side).
     * <ul>
     *   <li>PENDING: payment-service only accepts a payment once it has seen
     *       StockReserved, so the stock IS reserved — our own StockReserved is
     *       just still in flight on the other topic. Both steps are applied now
     *       (the late StockReserved is then ignored) instead of rejecting the
     *       payment and letting the order time out with the customer's money.</li>
     *   <li>CANCELLED: the timeout (or the customer) cancelled the order while
     *       the bank's webhook was in flight. The money must not stay with us,
     *       so we ask payment-service to refund by emitting OrderCancelled once
     *       more. The order itself stays CANCELLED.</li>
     * </ul>
     */
    @Transactional
    public void onPaymentSucceeded(Long orderId) {
        Order order = lock(orderId);
        switch (order.getStatus()) {
            case PENDING -> {
                transition(order, OrderStatus.STOCK_RESERVED, "stock reserved (implied by payment)");
                transition(order, OrderStatus.PAID, "payment succeeded");
            }
            case STOCK_RESERVED -> transition(order, OrderStatus.PAID, "payment succeeded");
            case CANCELLED -> {
                log.warn("Order {} was paid after cancellation; requesting a refund", orderId);
                historyRepository.save(new OrderStatusHistory(orderId, OrderStatus.CANCELLED, OrderStatus.CANCELLED,
                        "payment arrived after cancellation; refund requested"));
                emitCancelled(order, "paid after cancellation", OrderStatus.CANCELLED);
            }
            default -> ignoreStale(order, EventTypes.PAYMENT_SUCCEEDED);
        }
    }

    @Transactional
    public void onPaymentFailed(Long orderId) {
        Order order = lock(orderId);
        switch (order.getStatus()) {
            case PENDING, STOCK_RESERVED -> transition(order, OrderStatus.CANCELLED, "payment failed");
            default -> ignoreStale(order, EventTypes.PAYMENT_FAILED);
        }
    }

    /**
     * Refund confirmed by payment-service.
     * <ul>
     *   <li>PAID: an admin refund (FR-16). The order becomes REFUNDED and
     *       OrderCancelled is emitted so inventory puts the sold stock back —
     *       without it the items would be lost from the shelf for good. The
     *       refund itself is already done; payment-service ignores the
     *       OrderCancelled because the payment is no longer SUCCEEDED.</li>
     *   <li>CANCELLED: the late-payment refund; only a history note.</li>
     *   <li>REFUNDED: the customer-cancel path already moved the order.</li>
     *   <li>SHIPPED / DELIVERED: goods are with the customer, so no restock and
     *       no status change; recorded in the history for the admin.</li>
     * </ul>
     */
    @Transactional
    public void onPaymentRefunded(Long orderId) {
        Order order = lock(orderId);
        switch (order.getStatus()) {
            case PAID -> {
                transition(order, OrderStatus.REFUNDED, "payment refunded");
                emitCancelled(order, "payment refunded", OrderStatus.PAID);
            }
            case REFUNDED -> { /* customer cancel already moved it */ }
            case CANCELLED -> historyRepository.save(new OrderStatusHistory(orderId,
                    OrderStatus.CANCELLED, OrderStatus.CANCELLED, "late payment refunded"));
            default -> historyRepository.save(new OrderStatusHistory(orderId,
                    order.getStatus(), order.getStatus(), "payment refunded"));
        }
    }

    // ---------------------------------------------------------------- queries

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

    // ---------------------------------------------------------------- internals

    private Order lock(Long orderId) {
        return orderRepository.lockById(orderId)
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
    }

    /** The one place a status changes: validates, records history, emits events. */
    private void transition(Order order, OrderStatus next, String reason) {
        OrderStatus from = order.getStatus();
        order.transitionTo(next);
        historyRepository.save(new OrderStatusHistory(order.getId(), from, next, reason));

        switch (next) {
            case CANCELLED -> emitCancelled(order, reason, from);
            case SHIPPED -> outbox.append(EventTypes.TOPIC_ORDER_EVENTS, String.valueOf(order.getId()),
                    EventTypes.ORDER_SHIPPED, Map.of("userId", order.getUserId()));
            case DELIVERED -> outbox.append(EventTypes.TOPIC_ORDER_EVENTS, String.valueOf(order.getId()),
                    EventTypes.ORDER_DELIVERED, Map.of("userId", order.getUserId()));
            default -> { /* STOCK_RESERVED / PAID / REFUNDED come from other services' events */ }
        }
        log.info("Order {} {} -> {} ({})", order.getId(), from, next, reason);
    }

    private void emitCancelled(Order order, String reason, OrderStatus previousStatus) {
        outbox.append(EventTypes.TOPIC_ORDER_EVENTS, String.valueOf(order.getId()), EventTypes.ORDER_CANCELLED,
                Map.of("reason", reason, "userId", order.getUserId(), "previousStatus", previousStatus.name()));
    }

    private static void ignoreStale(Order order, String eventType) {
        log.info("Ignoring {} for order {}: already {}", eventType, order.getId(), order.getStatus());
    }
}

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderStatusHistoryRepository historyRepository;
    @Mock CartService cartService;
    @Mock ProductClient productClient;
    @Mock OutboxService outbox;
    @Mock TransactionTemplate tx;
    @InjectMocks OrderService service;

    @Captor ArgumentCaptor<Map<String, Object>> payload;

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order(1L, "key-1");
        order.addItem(10L, "Mouse", 250_000L, 1);
        ReflectionTestUtils.setField(order, "id", 100L);
        // run the "new order" transaction body inline
        lenient().when(tx.execute(any())).thenAnswer(inv -> inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
        lenient().when(orderRepository.lockById(100L)).thenReturn(Optional.of(order));
    }

    private static Cart cartWith(Long productId, int quantity) {
        Cart cart = new Cart(1L);
        cart.putItem(productId, quantity);
        return cart;
    }

    private void cartIs(Cart cart) {
        lenient().when(cartService.contents(1L)).thenReturn(CartService.contentsOf(cart));
        lenient().when(cartService.lock(1L)).thenReturn(Optional.of(cart));
    }

    private void moveTo(OrderStatus... path) {
        for (OrderStatus s : path) {
            order.transitionTo(s);
        }
    }

    // ---- FR-07 / NFR-10

    @Test
    void sameIdempotencyKeyReturnsTheSameOrder() {
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "key-1")).thenReturn(Optional.of(order));

        assertSame(order, service.placeOrder(1L, "key-1"));

        verifyNoInteractions(productClient, outbox);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void anotherUsersKeyDoesNotReplayTheirOrder() {
        // the lookup is scoped by user: user 2 with user 1's key starts a normal (empty-cart) flow
        when(orderRepository.findByUserIdAndIdempotencyKey(2L, "key-1")).thenReturn(Optional.empty());
        when(cartService.contents(2L)).thenReturn(Map.of());

        assertThrows(BadRequestException.class, () -> service.placeOrder(2L, "key-1"));
        verify(orderRepository, never()).findByUserIdAndIdempotencyKey(1L, "key-1");
    }

    @Test
    void losingTheInsertRaceReturnsTheWinnersOrder() {
        cartIs(cartWith(10L, 2));
        // lookups before and under the lock miss (both requests got that far), the one after the failed insert finds the winner
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "key-1"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(order));
        when(productClient.getProduct(10L)).thenReturn(new ProductClient.ProductInfo(10L, "Mouse", 250_000L, true));
        when(orderRepository.save(any(Order.class))).thenThrow(new DataIntegrityViolationException("uk_orders_user_idempotency_key"));

        assertSame(order, service.placeOrder(1L, "key-1"));

        verify(outbox, never()).append(any(), any(), any(), any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    void sameKeyWaitingOnTheCartLockReturnsTheWinnersOrder() {
        Cart emptied = new Cart(1L); // the winner already cleared it
        when(cartService.contents(1L)).thenReturn(Map.of(10L, 2));
        when(cartService.lock(1L)).thenReturn(Optional.of(emptied));
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "key-1"))
                .thenReturn(Optional.empty(), Optional.of(order));
        when(productClient.getProduct(10L)).thenReturn(new ProductClient.ProductInfo(10L, "Mouse", 250_000L, true));

        assertSame(order, service.placeOrder(1L, "key-1"), "no 'cart is empty' error for a retried request");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cartChangedWhilePricingIsAConflict() {
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.empty());
        when(cartService.contents(1L)).thenReturn(Map.of(10L, 2));
        when(cartService.lock(1L)).thenReturn(Optional.of(cartWith(10L, 5))); // quantity changed meanwhile
        when(productClient.getProduct(10L)).thenReturn(new ProductClient.ProductInfo(10L, "Mouse", 250_000L, true));

        assertThrows(ConflictException.class, () -> service.placeOrder(1L, "k"));
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void productServiceIsCalledOutsideTheTransaction() {
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.empty());
        when(cartService.contents(1L)).thenReturn(Map.of(10L, 1));
        when(productClient.getProduct(10L)).thenThrow(new BadRequestException("Product 10 is not available"));

        assertThrows(BadRequestException.class, () -> service.placeOrder(1L, "k"));
        verifyNoInteractions(tx); // failed before any transaction was opened
    }

    @Test
    void missingKeyIsRejected() {
        assertThrows(BadRequestException.class, () -> service.placeOrder(1L, " "));
    }

    @Test
    void emptyCartIsRejected() {
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.empty());
        when(cartService.contents(1L)).thenReturn(Map.of());

        assertThrows(BadRequestException.class, () -> service.placeOrder(1L, "k"));
    }

    @Test
    void placeOrderFreezesPriceClearsCartAndEmitsOrderCreated() {
        Cart cart = cartWith(10L, 2);
        cartIs(cart);
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.empty());
        when(productClient.getProduct(10L)).thenReturn(new ProductClient.ProductInfo(10L, "Mouse", 250_000L, true));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            ReflectionTestUtils.setField(o, "id", 7L);
            return o;
        });

        Order placed = service.placeOrder(1L, "k");

        assertEquals(500_000L, placed.getTotalAmount());
        assertEquals("Mouse", placed.getItems().get(0).getProductName());
        assertEquals(OrderStatus.PENDING, placed.getStatus());
        assertTrue(cart.getItems().isEmpty(), "cart is emptied after ordering");
        verify(historyRepository).save(any(OrderStatusHistory.class));
        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("7"), eq(EventTypes.ORDER_CREATED), payload.capture());
        assertEquals(1L, payload.getValue().get("userId"));
        assertEquals(500_000L, payload.getValue().get("totalAmount"));
        assertNotNull(payload.getValue().get("items"));
    }

    @Test
    void hiddenProductCannotBeOrdered() {
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.empty());
        when(cartService.contents(1L)).thenReturn(Map.of(10L, 1));
        when(productClient.getProduct(10L)).thenReturn(new ProductClient.ProductInfo(10L, "Mouse", 1L, false));

        assertThrows(BadRequestException.class, () -> service.placeOrder(1L, "k"));
        verify(outbox, never()).append(any(), any(), any(), any());
    }

    // ---- commands: strict status machine + events

    @Test
    void changeStatusRecordsHistoryAndEmitsCancelledEvent() {
        service.changeStatus(100L, OrderStatus.CANCELLED, "out of stock");

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(historyRepository).save(argThat(h -> h.getFromStatus() == OrderStatus.PENDING
                && h.getToStatus() == OrderStatus.CANCELLED && "out of stock".equals(h.getReason())));
        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_CANCELLED), payload.capture());
        assertEquals("PENDING", payload.getValue().get("previousStatus"));
        assertEquals(1L, payload.getValue().get("userId"));
    }

    @Test
    void repeatedCommandIsANoOp() {
        moveTo(OrderStatus.STOCK_RESERVED, OrderStatus.PAID, OrderStatus.SHIPPED);

        service.changeStatus(100L, OrderStatus.SHIPPED, "admin ships");

        verifyNoInteractions(historyRepository, outbox);
    }

    @Test
    void illegalCommandBubblesUp() {
        assertThrows(IllegalStateException.class, () -> service.changeStatus(100L, OrderStatus.SHIPPED, "admin ships"));
        verifyNoInteractions(outbox);
    }

    @Test
    void shippedAndDeliveredNotifyButDoNotCompensate() {
        moveTo(OrderStatus.STOCK_RESERVED, OrderStatus.PAID);

        service.changeStatus(100L, OrderStatus.SHIPPED, "admin ships");
        service.changeStatus(100L, OrderStatus.DELIVERED, "admin delivers");

        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_SHIPPED), any());
        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_DELIVERED), any());
        verify(outbox, never()).append(any(), any(), eq(EventTypes.ORDER_CANCELLED), any());
    }

    // ---- saga events: tolerant of late / out-of-order delivery

    @Test
    void stockReservedMovesPendingOrder() {
        service.onStockReserved(100L);
        assertEquals(OrderStatus.STOCK_RESERVED, order.getStatus());
    }

    @Test
    void stockReservedAfterCancelIsIgnoredNotRetried() {
        moveTo(OrderStatus.CANCELLED); // customer cancelled before inventory answered

        assertDoesNotThrow(() -> service.onStockReserved(100L));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verifyNoInteractions(historyRepository, outbox);
    }

    @Test
    void reservationFailureCancelsOnlyAPendingOrder() {
        service.onStockReservationFailed(100L);
        assertEquals(OrderStatus.CANCELLED, order.getStatus());

        clearInvocations(outbox);
        service.onStockReservationFailed(100L); // duplicate / late: nothing more
        verifyNoInteractions(outbox);
    }

    @Test
    void paymentSucceededMovesReservedOrderToPaid() {
        moveTo(OrderStatus.STOCK_RESERVED);

        service.onPaymentSucceeded(100L);

        assertEquals(OrderStatus.PAID, order.getStatus());
        verify(outbox, never()).append(any(), any(), any(), any());
    }

    @Test
    void paymentOvertakingStockReservedStillPaysTheOrder() {
        // payment-service only accepts a payment after StockReserved, so a PENDING order here
        // means our own StockReserved is still in flight on the other topic
        service.onPaymentSucceeded(100L);

        assertEquals(OrderStatus.PAID, order.getStatus());
        verify(historyRepository, times(2)).save(any(OrderStatusHistory.class));

        service.onStockReserved(100L); // the late StockReserved is now stale
        assertEquals(OrderStatus.PAID, order.getStatus());
    }

    @Test
    void latePaymentOnCancelledOrderTriggersRefundInsteadOfFailing() {
        moveTo(OrderStatus.CANCELLED);

        service.onPaymentSucceeded(100L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus(), "order stays cancelled");
        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_CANCELLED), payload.capture());
        assertEquals("paid after cancellation", payload.getValue().get("reason"));
        verify(historyRepository).save(any(OrderStatusHistory.class));
    }

    @Test
    void paymentFailureCancelsUnpaidOrderAndIsIgnoredLater() {
        moveTo(OrderStatus.STOCK_RESERVED);
        service.onPaymentFailed(100L);
        assertEquals(OrderStatus.CANCELLED, order.getStatus());

        clearInvocations(outbox);
        assertDoesNotThrow(() -> service.onPaymentFailed(100L));
        verifyNoInteractions(outbox);
    }

    @Test
    void refundOfLatePaymentIsOnlyAHistoryNote() {
        moveTo(OrderStatus.CANCELLED);

        service.onPaymentRefunded(100L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(historyRepository).save(any(OrderStatusHistory.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void adminRefundOfPaidOrderMovesItToRefundedAndRestocks() {
        moveTo(OrderStatus.STOCK_RESERVED, OrderStatus.PAID);

        service.onPaymentRefunded(100L);

        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        // inventory only gives sold stock back on OrderCancelled
        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_CANCELLED), payload.capture());
        assertEquals("PAID", payload.getValue().get("previousStatus"));
    }

    @Test
    void refundAfterCustomerCancelDoesNotEmitAgain() {
        moveTo(OrderStatus.STOCK_RESERVED, OrderStatus.PAID, OrderStatus.REFUNDED);

        service.onPaymentRefunded(100L);

        verifyNoInteractions(outbox, historyRepository);
    }

    @Test
    void refundOfShippedOrderIsRecordedWithoutRestock() {
        moveTo(OrderStatus.STOCK_RESERVED, OrderStatus.PAID, OrderStatus.SHIPPED);

        assertDoesNotThrow(() -> service.onPaymentRefunded(100L));

        assertEquals(OrderStatus.SHIPPED, order.getStatus());
        verify(historyRepository).save(any(OrderStatusHistory.class));
        verifyNoInteractions(outbox);
    }

    // ---- BR-03 timeout

    @Test
    void timeoutCancelsStillUnpaidOrder() {
        ReflectionTestUtils.setField(order, "createdAt", Instant.now().minusSeconds(3600));

        assertTrue(service.cancelIfUnpaid(100L, Instant.now().minusSeconds(900)));
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void timeoutDoesNotTouchAnOrderPaidMeanwhile() {
        ReflectionTestUtils.setField(order, "createdAt", Instant.now().minusSeconds(3600));
        moveTo(OrderStatus.STOCK_RESERVED, OrderStatus.PAID); // paid between listing and lock

        assertFalse(service.cancelIfUnpaid(100L, Instant.now().minusSeconds(900)));
        assertEquals(OrderStatus.PAID, order.getStatus());
        verifyNoInteractions(outbox);
    }

    // ---- FR-10 / BR-06

    @Test
    void customerCannotCancelSomeoneElsesOrder() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        assertThrows(NotFoundException.class, () -> service.customerCancel(100L, 999L));
        assertThrows(NotFoundException.class, () -> service.getOwn(100L, 999L));
    }

    @Test
    void customerCancelOfUnpaidOrderReleasesStock() {
        moveTo(OrderStatus.STOCK_RESERVED);

        service.customerCancel(100L, 1L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(outbox, times(1)).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_CANCELLED), any());
    }

    @Test
    void customerCancelOfPaidOrderAsksForRefund() {
        moveTo(OrderStatus.STOCK_RESERVED, OrderStatus.PAID);

        service.customerCancel(100L, 1L);

        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_CANCELLED), payload.capture());
        assertEquals("PAID", payload.getValue().get("previousStatus"));
    }

    @Test
    void shippedOrderCannotBeCancelled() {
        moveTo(OrderStatus.STOCK_RESERVED, OrderStatus.PAID, OrderStatus.SHIPPED);

        assertThrows(BadRequestException.class, () -> service.customerCancel(100L, 1L));
        verifyNoInteractions(outbox);
    }
}

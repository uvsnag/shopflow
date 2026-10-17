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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    @InjectMocks OrderService service;

    @Captor ArgumentCaptor<Map<String, Object>> payload;

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order(1L, "key-1");
        order.addItem(10L, "Mouse", 250_000L, 1);
        ReflectionTestUtils.setField(order, "id", 100L);
    }

    // ---- FR-07 / NFR-10

    @Test
    void sameIdempotencyKeyReturnsTheSameOrder() {
        when(orderRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(order));

        assertSame(order, service.placeOrder(1L, "key-1"));

        verifyNoInteractions(productClient, outbox);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void missingKeyIsRejected() {
        assertThrows(BadRequestException.class, () -> service.placeOrder(1L, " "));
    }

    @Test
    void emptyCartIsRejected() {
        when(orderRepository.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(cartService.getOrCreate(1L)).thenReturn(new Cart(1L));

        assertThrows(BadRequestException.class, () -> service.placeOrder(1L, "k"));
    }

    @Test
    void placeOrderFreezesPriceClearsCartAndEmitsOrderCreated() {
        Cart cart = new Cart(1L);
        cart.putItem(10L, 2);
        when(orderRepository.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(cartService.getOrCreate(1L)).thenReturn(cart);
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
        Cart cart = new Cart(1L);
        cart.putItem(10L, 1);
        when(orderRepository.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(cartService.getOrCreate(1L)).thenReturn(cart);
        when(productClient.getProduct(10L)).thenReturn(new ProductClient.ProductInfo(10L, "Mouse", 1L, false));

        assertThrows(BadRequestException.class, () -> service.placeOrder(1L, "k"));
        verify(outbox, never()).append(any(), any(), any(), any());
    }

    // ---- status machine + events

    @Test
    void changeStatusRecordsHistoryAndEmitsCancelledEvent() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        service.changeStatus(100L, OrderStatus.CANCELLED, "out of stock");

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(historyRepository).save(argThat(h -> h.getFromStatus() == OrderStatus.PENDING
                && h.getToStatus() == OrderStatus.CANCELLED && "out of stock".equals(h.getReason())));
        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_CANCELLED), payload.capture());
        assertEquals("PENDING", payload.getValue().get("previousStatus"));
        assertEquals(1L, payload.getValue().get("userId"));
    }

    @Test
    void duplicateSagaEventIsANoOp() {
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        service.changeStatus(100L, OrderStatus.STOCK_RESERVED, "stock reserved");

        verifyNoInteractions(historyRepository, outbox);
    }

    @Test
    void illegalTransitionBubblesUp() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        assertThrows(IllegalStateException.class, () -> service.changeStatus(100L, OrderStatus.SHIPPED, "admin ships"));
        verifyNoInteractions(outbox);
    }

    @Test
    void shippedAndDeliveredNotifyButDoNotCompensate() {
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        order.transitionTo(OrderStatus.PAID);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        service.changeStatus(100L, OrderStatus.SHIPPED, "admin ships");
        service.changeStatus(100L, OrderStatus.DELIVERED, "admin delivers");

        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_SHIPPED), any());
        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_DELIVERED), any());
        verify(outbox, never()).append(any(), any(), eq(EventTypes.ORDER_CANCELLED), any());
    }

    // ---- payment outcomes

    @Test
    void paymentSucceededMovesReservedOrderToPaid() {
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        service.onPaymentSucceeded(100L);

        assertEquals(OrderStatus.PAID, order.getStatus());
        verify(outbox, never()).append(any(), any(), any(), any());
    }

    @Test
    void latePaymentOnCancelledOrderTriggersRefundInsteadOfFailing() {
        order.transitionTo(OrderStatus.CANCELLED);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        service.onPaymentSucceeded(100L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus(), "order stays cancelled");
        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_CANCELLED), payload.capture());
        assertEquals("paid after cancellation", payload.getValue().get("reason"));
        verify(historyRepository).save(any(OrderStatusHistory.class));
    }

    @Test
    void refundOfLatePaymentIsOnlyAHistoryNote() {
        order.transitionTo(OrderStatus.CANCELLED);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        service.onPaymentRefunded(100L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(historyRepository).save(any(OrderStatusHistory.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void refundOfPaidOrderMovesItToRefunded() {
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        order.transitionTo(OrderStatus.PAID);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        service.onPaymentRefunded(100L);

        assertEquals(OrderStatus.REFUNDED, order.getStatus());
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
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        service.customerCancel(100L, 1L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(outbox, times(1)).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_CANCELLED), any());
    }

    @Test
    void customerCancelOfPaidOrderAsksForRefund() {
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        order.transitionTo(OrderStatus.PAID);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        service.customerCancel(100L, 1L);

        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        verify(outbox).append(eq(EventTypes.TOPIC_ORDER_EVENTS), eq("100"), eq(EventTypes.ORDER_CANCELLED), payload.capture());
        assertEquals("PAID", payload.getValue().get("previousStatus"));
    }

    @Test
    void shippedOrderCannotBeCancelled() {
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.SHIPPED);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        assertThrows(BadRequestException.class, () -> service.customerCancel(100L, 1L));
        verifyNoInteractions(outbox);
    }
}

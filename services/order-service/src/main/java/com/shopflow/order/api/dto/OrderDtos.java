package com.shopflow.order.api.dto;

import com.shopflow.order.domain.Cart;
import com.shopflow.order.domain.Order;

import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    public record CartItemResponse(Long productId, int quantity) {}

    public record CartResponse(List<CartItemResponse> items, Instant updatedAt) {
        public static CartResponse from(Cart cart) {
            return new CartResponse(
                    cart.getItems().stream()
                            .map(i -> new CartItemResponse(i.getProductId(), i.getQuantity())).toList(),
                    cart.getUpdatedAt());
        }
    }

    public record OrderItemResponse(Long productId, String productName, Long unitPrice, int quantity) {}

    public record OrderResponse(Long id, String status, Long totalAmount, String currency,
                                Instant createdAt, List<OrderItemResponse> items) {
        public static OrderResponse from(Order order) {
            return new OrderResponse(order.getId(), order.getStatus().name(), order.getTotalAmount(),
                    order.getCurrency(), order.getCreatedAt(),
                    order.getItems().stream()
                            .map(i -> new OrderItemResponse(i.getProductId(), i.getProductName(),
                                    i.getUnitPrice(), i.getQuantity()))
                            .toList());
        }
    }

    private OrderDtos() {
    }
}

package com.shopflow.order.api;

import com.shopflow.common.security.CurrentUser;
import com.shopflow.order.api.dto.OrderDtos.CartResponse;
import com.shopflow.order.service.CartService;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** FR-06: cart management. */
@RestController
@RequestMapping("/cart")
@PreAuthorize("isAuthenticated()")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    public record PutItemRequest(@Min(0) int quantity) {}

    @GetMapping
    public CartResponse getCart() {
        return CartResponse.from(cartService.getOrCreate(CurrentUser.id()));
    }

    @PutMapping("/items/{productId}")
    public CartResponse putItem(@PathVariable Long productId, @RequestBody PutItemRequest req) {
        return CartResponse.from(cartService.putItem(CurrentUser.id(), productId, req.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@PathVariable Long productId) {
        return CartResponse.from(cartService.removeItem(CurrentUser.id(), productId));
    }
}

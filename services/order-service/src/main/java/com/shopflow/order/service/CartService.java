package com.shopflow.order.service;

import com.shopflow.order.domain.Cart;
import com.shopflow.order.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {

    private final CartRepository cartRepository;

    public CartService(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    @Transactional
    public Cart getOrCreate(Long userId) {
        return cartRepository.findByUserId(userId)
                .orElseGet(() -> cartRepository.save(new Cart(userId)));
    }

    /** FR-06: add or change an item (quantity 0 removes it). */
    @Transactional
    public Cart putItem(Long userId, Long productId, int quantity) {
        Cart cart = getOrCreate(userId);
        if (quantity <= 0) {
            cart.removeItem(productId);
        } else {
            cart.putItem(productId, quantity);
        }
        return cart;
    }

    @Transactional
    public Cart removeItem(Long userId, Long productId) {
        Cart cart = getOrCreate(userId);
        cart.removeItem(productId);
        return cart;
    }
}

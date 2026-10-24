package com.shopflow.order.service;

import com.shopflow.order.domain.Cart;
import com.shopflow.order.domain.CartItem;
import com.shopflow.order.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

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

    /** productId -> quantity of the user's cart right now; empty if there is no cart. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> contents(Long userId) {
        return cartRepository.findByUserId(userId).map(CartService::contentsOf).orElse(Map.of());
    }

    /** Locks the cart row until the caller's transaction ends (see {@link CartRepository#lockByUserId}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Cart> lock(Long userId) {
        return cartRepository.lockByUserId(userId);
    }

    public static Map<Long, Integer> contentsOf(Cart cart) {
        Map<Long, Integer> contents = new TreeMap<>();
        for (CartItem item : cart.getItems()) {
            contents.put(item.getProductId(), item.getQuantity());
        }
        return contents;
    }
}

package com.shopflow.order.api;

import com.shopflow.common.security.CurrentUser;
import com.shopflow.order.api.dto.OrderDtos.OrderResponse;
import com.shopflow.order.repository.OrderRepository;
import com.shopflow.order.service.OrderService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@PreAuthorize("isAuthenticated()")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    /** FR-07 + NFR-10: place order, idempotent on the Idempotency-Key header. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse placeOrder(@RequestHeader(name = "Idempotency-Key") String idempotencyKey) {
        return OrderResponse.from(orderService.placeOrder(CurrentUser.id(), idempotencyKey));
    }

    /** FR-09: own orders only (BR-06). */
    @GetMapping
    public List<OrderResponse> myOrders(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(CurrentUser.id(), PageRequest.of(page, size))
                .map(OrderResponse::from).getContent();
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return OrderResponse.from(orderService.getOwn(id, CurrentUser.id()));
    }

    /** FR-10: cancel before shipping; paid orders are refunded. */
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id) {
        orderService.customerCancel(id, CurrentUser.id());
        return OrderResponse.from(orderService.getOwn(id, CurrentUser.id()));
    }
}

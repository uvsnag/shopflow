package com.shopflow.order.api;

import com.shopflow.order.api.dto.OrderDtos.OrderResponse;
import com.shopflow.order.domain.OrderStatus;
import com.shopflow.order.repository.OrderRepository;
import com.shopflow.order.service.OrderService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** FR-14, FR-15: admin order management. */
@RestController
@RequestMapping("/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public AdminOrderController(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    @GetMapping
    public List<OrderResponse> list(@RequestParam(required = false) OrderStatus status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size);
        var result = status == null
                ? orderRepository.findAll(pageable)
                : orderRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        return result.map(OrderResponse::from).getContent();
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable Long id) {
        orderService.changeStatus(id, OrderStatus.SHIPPED, "admin ships");
        return OrderResponse.from(orderService.find(id));
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable Long id) {
        orderService.changeStatus(id, OrderStatus.DELIVERED, "admin delivers");
        return OrderResponse.from(orderService.find(id));
    }
}

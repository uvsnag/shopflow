package com.shopflow.inventory.api;

import com.shopflow.inventory.domain.Stock;
import com.shopflow.inventory.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** FR-13: admin adds stock. */
    @PostMapping("/admin/inventory/{productId}/add")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> addStock(@PathVariable Long productId,
                                        @RequestBody @Valid AddStockRequest req) {
        Stock stock = inventoryService.addStock(productId, req.quantity());
        return Map.of("productId", stock.getProductId(),
                "available", stock.getAvailable(),
                "reserved", stock.getReserved());
    }

    /** Used by the frontend to show "stock left" on product detail (FR-05). */
    @GetMapping("/inventory/{productId}")
    public Map<String, Object> getStock(@PathVariable Long productId) {
        Stock stock = inventoryService.getStock(productId);
        return Map.of("productId", stock.getProductId(), "available", stock.getAvailable());
    }

    public record AddStockRequest(@Min(1) int quantity) {}
}

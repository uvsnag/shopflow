package com.shopflow.product.api;

import com.shopflow.product.api.dto.ProductDtos.ProductResponse;
import com.shopflow.product.service.ProductService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** FR-04: search, filter by category, paging. Public. */
    @GetMapping
    public ProductService.PageResponse list(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) Long category,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return productService.list(q, category, page, Math.min(size, 100));
    }

    /** FR-05. Public. Stock left comes from inventory-service via the frontend. */
    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
    }
}

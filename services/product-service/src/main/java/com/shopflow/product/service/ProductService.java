package com.shopflow.product.service;

import com.shopflow.common.web.NotFoundException;
import com.shopflow.product.api.dto.ProductDtos.ProductRequest;
import com.shopflow.product.api.dto.ProductDtos.ProductResponse;
import com.shopflow.product.domain.Product;
import com.shopflow.product.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** NFR-14: list is cached in Redis, evicted on any product change. */
    @Cacheable(cacheNames = "products", key = "#q + ':' + #categoryId + ':' + #page + ':' + #size")
    @Transactional(readOnly = true)
    public PageResponse list(String q, Long categoryId, int page, int size) {
        Page<Product> result = productRepository.search(
                (q == null || q.isBlank()) ? null : q,
                categoryId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse(
                result.getContent().stream().map(ProductResponse::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public record PageResponse(List<ProductResponse> items, int page, int size, long totalItems, int totalPages) {}

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        return ProductResponse.from(find(id));
    }

    @CacheEvict(cacheNames = "products", allEntries = true)
    @Transactional
    public ProductResponse create(ProductRequest req) {
        Product product = productRepository.save(
                new Product(req.categoryId(), req.name(), req.description(), req.price(), req.imageUrl()));
        return ProductResponse.from(product);
    }

    @CacheEvict(cacheNames = "products", allEntries = true)
    @Transactional
    public ProductResponse update(Long id, ProductRequest req) {
        Product product = find(id);
        product.update(req.categoryId(), req.name(), req.description(), req.price(), req.imageUrl(), req.active());
        return ProductResponse.from(product);
    }

    @CacheEvict(cacheNames = "products", allEntries = true)
    @Transactional
    public void hide(Long id) {
        find(id).hide();
    }

    private Product find(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product " + id + " not found"));
    }
}

package com.shopflow.product.repository;

import com.shopflow.product.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Derived queries instead of one JPQL with "(:q IS NULL OR ...)": binding a
 * null parameter inside LIKE/CONCAT makes PostgreSQL complain that it cannot
 * determine the parameter type. The service picks the right method.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByActiveTrue(Pageable pageable);

    Page<Product> findByActiveTrueAndNameContainingIgnoreCase(String q, Pageable pageable);

    Page<Product> findByActiveTrueAndCategoryId(Long categoryId, Pageable pageable);

    Page<Product> findByActiveTrueAndCategoryIdAndNameContainingIgnoreCase(Long categoryId, String q, Pageable pageable);
}

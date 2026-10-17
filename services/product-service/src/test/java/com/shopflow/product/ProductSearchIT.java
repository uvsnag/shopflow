package com.shopflow.product;

import com.shopflow.common.web.NotFoundException;
import com.shopflow.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flyway seed + search queries against a real PostgreSQL (Testcontainers).
 * Redis cache is switched off here; the cache itself is exercised by the
 * running system. Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.cache.type=none",
        "shopflow.outbox.poll-ms=3600000",
        "management.tracing.enabled=false"
})
class ProductSearchIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ProductService productService;

    @Test
    void seededCatalogIsSearchable() {
        assertEquals(4, productService.list(null, null, 0, 20).totalItems());
        assertEquals("Wireless Mouse", productService.list("mouse", null, 0, 20).items().get(0).name());
        assertEquals("Clean Code", productService.list(null, 2L, 0, 20).items().get(0).name());
        assertEquals(1, productService.list("key", 1L, 0, 20).totalItems());
        assertEquals(0, productService.list("key", 2L, 0, 20).totalItems());
        assertEquals(2, productService.list(null, null, 0, 2).items().size());
        assertEquals(2, productService.list(null, null, 0, 2).totalPages());
    }

    @Test
    void hiddenProductsDisappearFromListAndDetail() {
        productService.hide(4L);

        assertEquals(3, productService.list(null, null, 0, 20).totalItems());
        assertThrows(NotFoundException.class, () -> productService.get(4L));
    }
}

package com.shopflow.order.client;

import com.shopflow.common.web.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The one allowed synchronous call in the main flow: placing an order needs
 * the current product name and price right now (BR-05 freezes them on the
 * order). Everything after that is async via Kafka.
 */
@Component
public class ProductClient {

    public record ProductInfo(Long id, String name, Long price, boolean active) {}

    private final RestClient restClient;

    public ProductClient(@Value("${shopflow.product-service-url:http://localhost:8082}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public ProductInfo getProduct(Long productId) {
        try {
            return restClient.get()
                    .uri("/products/{id}", productId)
                    .retrieve()
                    .body(ProductInfo.class);
        } catch (Exception e) {
            throw new BadRequestException("Product " + productId + " is not available");
        }
    }
}

package com.shopflow.product.api.dto;

import com.shopflow.product.domain.Product;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

public final class ProductDtos {

    public record ProductRequest(Long categoryId,
                                 @NotBlank String name,
                                 String description,
                                 @NotNull @Min(0) Long price,
                                 String imageUrl,
                                 Boolean active) {}

    public record ProductResponse(Long id, Long categoryId, String name, String description,
                                  Long price, String imageUrl, boolean active) implements Serializable {

        public static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getCategoryId(), p.getName(),
                    p.getDescription(), p.getPrice(), p.getImageUrl(), p.isActive());
        }
    }

    private ProductDtos() {
    }
}

package com.shopflow.product.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** Smallest currency unit: 150000 = 150,000 VND. */
    @Column(nullable = false)
    private Long price;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Product() {
    }

    public Product(Long categoryId, String name, String description, Long price, String imageUrl) {
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
    }

    public void update(Long categoryId, String name, String description, Long price, String imageUrl, Boolean active) {
        if (categoryId != null) this.categoryId = categoryId;
        if (name != null) this.name = name;
        if (description != null) this.description = description;
        if (price != null) this.price = price;
        if (imageUrl != null) this.imageUrl = imageUrl;
        if (active != null) this.active = active;
        this.updatedAt = Instant.now();
    }

    public void hide() {
        this.active = false; // FR-12: hide, never hard-delete (orders reference it)
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getCategoryId() { return categoryId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Long getPrice() { return price; }
    public String getImageUrl() { return imageUrl; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

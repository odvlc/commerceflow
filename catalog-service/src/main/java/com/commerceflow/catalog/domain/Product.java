package com.commerceflow.catalog.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {

    @Id
    private UUID id;
    private String sku;
    private String name;

    @Column(length = 4000)
    private String description;

    private String category;

    @Column(precision = 19, scale = 2)
    private BigDecimal price;

    private String currency;
    private boolean active;

    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
    private String idempotencyKey;
    private String requestHash;

    protected Product() {
    }

    public Product(UUID id, String idempotencyKey, String requestHash) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
    }

    public void revise(
            String sku,
            String name,
            String description,
            String category,
            BigDecimal price,
            String currency,
            boolean active) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.category = category;
        this.price = price;
        this.currency = currency;
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isActive() {
        return active;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getRequestHash() {
        return requestHash;
    }
}

package com.commerceflow.catalog.api;

import com.commerceflow.catalog.domain.Product;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductView(
    UUID id,
    String sku,
    String name,
    String description,
    String category,
    BigDecimal price,
    String currency,
    boolean active,
    long version,
    Instant createdAt,
    Instant updatedAt) {
  public static ProductView from(Product p) {
    return new ProductView(
        p.getId(),
        p.getSku(),
        p.getName(),
        p.getDescription(),
        p.getCategory(),
        p.getPrice(),
        p.getCurrency(),
        p.isActive(),
        p.getVersion(),
        p.getCreatedAt(),
        p.getUpdatedAt());
  }
}

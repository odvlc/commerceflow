package com.commerceflow.catalog.search;

import com.commerceflow.contracts.CheckoutEvents.ProductChanged;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

@Document(indexName = ProductSearch.READ_ALIAS, createIndex = false)
public record ProductDocument(
    @Id String id,
    String sku,
    String name,
    String description,
    String category,
    long priceMinor,
    String currency,
    boolean active,
    long productVersion,
    String updatedAt) {
  public static ProductDocument from(ProductChanged product) {
    if (product.version() < 0) throw new IllegalArgumentException("Invalid product version");
    return new ProductDocument(
        product.id().toString(),
        product.sku(),
        product.name(),
        product.description(),
        product.category(),
        product.price().movePointRight(2).longValueExact(),
        product.currency(),
        product.active(),
        product.version(),
        product.updatedAt().toString());
  }
}

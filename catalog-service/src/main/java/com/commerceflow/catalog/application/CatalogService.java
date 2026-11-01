/*
 * Owns product writes and their outbox events; optional cache reads can be bypassed for trusted
 * checkout prices.
 */
package com.commerceflow.catalog.application;

import com.commerceflow.catalog.api.ProductInput;
import com.commerceflow.catalog.api.ProductView;
import com.commerceflow.catalog.domain.Product;
import com.commerceflow.catalog.persistence.ProductRepository;
import com.commerceflow.contracts.CheckoutEvents.ProductChanged;
import com.commerceflow.support.BusinessException;
import com.commerceflow.support.OutboxStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class CatalogService {

    private final ProductRepository products;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final OutboxStore outbox;
    private final ObjectProvider<ProductCache> cache;

    public CatalogService(
            ProductRepository products,
            JdbcClient jdbc,
            JsonMapper json,
            OutboxStore outbox,
            ObjectProvider<ProductCache> cache) {
        this.products = products;
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public Page<ProductView> list(int page, int size, String category) {
        var paging = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        return (category == null
                ? products.findByActiveTrue(paging)
                : products.findByActiveTrueAndCategory(category, paging))
                .map(ProductView::from);
    }

    @Transactional(readOnly = true)
    public Page<ProductView> adminList(int page, int size) {
        return products.findAll(PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id"))))
                .map(ProductView::from);
    }

    @Transactional(readOnly = true)
    public ProductView get(UUID id) {
        return get(id, false);
    }

    @Transactional(readOnly = true)
    public ProductView get(UUID id, boolean fresh) {
        var storage = cache.getIfAvailable();
        // Fresh reads bypass Redis so checkout validates current catalog prices.
        if (!fresh && storage != null) {
            var hit = storage.get(id);
            if (hit.isPresent())
                return hit.get();
        }
        var product = required(id);
        if (!product.isActive())
            throw missing();
        var result = ProductView.from(product);
        if (!fresh && storage != null)
            storage.put(result);
        return result;
    }

    @Transactional
    public ProductView create(String key, ProductInput input, String correlationId) {
        String hash = hash(json.writeValueAsString(input));
        // Database-scoped serialization keeps concurrent retries in order within the transaction.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", key)
                .query(Object.class)
                .single();
        var existing = products.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash))
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "Key already used with different contents");
            return ProductView.from(existing.get());
        }
        var product = new Product(UUID.randomUUID(), key, hash);
        revise(product, input);
        // Flush first so the outgoing snapshot contains the database-assigned optimistic version.
        products.saveAndFlush(product);
        emit(product, correlationId);
        return ProductView.from(product);
    }

    @Transactional
    public ProductView update(UUID id, long expectedVersion, ProductInput input, String correlationId) {
        var product = required(id);
        if (product.getVersion() != expectedVersion)
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Product changed; reload before editing");
        revise(product, input);
        // Detect optimistic write failure before appending the event for the revised product.
        products.flush();
        emit(product, correlationId);
        return ProductView.from(product);
    }

    private void revise(Product product, ProductInput input) {
        jdbc.sql("INSERT INTO categories(name) VALUES (:name) ON CONFLICT DO NOTHING")
                .param("name", input.category().trim())
                .update();
        product.revise(
                input.sku(),
                input.name().trim(),
                input.description(),
                input.category().trim(),
                input.price(),
                input.currency(),
                input.active());
    }

    private void emit(Product product, String correlationId) {
        outbox.append(
                "ProductChanged",
                product.getId(),
                correlationId,
                new ProductChanged(
                        product.getId(),
                        product.getSku(),
                        product.getName(),
                        product.getDescription(),
                        product.getCategory(),
                        product.getPrice(),
                        product.getCurrency(),
                        product.isActive(),
                        product.getVersion(),
                        product.getUpdatedAt()));
        var id = product.getId();
        var version = product.getVersion();
        TransactionSynchronizationManager
                // Invalidate after commit so readers cannot refill the cache from old database state.
                .registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                var storage = cache.getIfAvailable();
                                if (storage != null)
                                    storage.invalidate(id, version);
                            }
                        });
    }

    private Product required(UUID id) {
        return products.findById(id).orElseThrow(CatalogService::missing);
    }

    private static BusinessException missing() {
        return new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

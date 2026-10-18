package com.commerceflow.catalog.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import com.commerceflow.catalog.api.ProductView;
import com.commerceflow.catalog.persistence.ProductRepository;
import com.commerceflow.contracts.CheckoutEvents.ProductChanged;
import com.commerceflow.support.BusinessException;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.*;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "catalog.search.enabled", havingValue = "true", matchIfMissing = true)
public class ProductSearch {

    public static final String READ_ALIAS = "cf-products-read";
    public static final String WRITE_ALIAS = "cf-products-write";

    public record SearchItem(
            UUID id,
            String sku,
            String name,
            String description,
            String category,
            BigDecimal price,
            String currency) {

    }

    public record SearchPage(List<SearchItem> items, long totalElements, int page, int size) {

    }

    public record RebuildResult(String index, long documents) {

    }

    private final ElasticsearchClient client;
    private final ElasticsearchOperations operations;
    private final JdbcClient jdbc;
    private final ProductRepository products;
    private final EntityManager entities;
    private final JsonMapper json;
    private final MeterRegistry metrics;

    public ProductSearch(
            ElasticsearchClient client,
            ElasticsearchOperations operations,
            JdbcClient jdbc,
            ProductRepository products,
            EntityManager entities,
            JsonMapper json,
            MeterRegistry metrics) {
        this.client = client;
        this.operations = operations;
        this.jdbc = jdbc;
        this.products = products;
        this.entities = entities;
        this.json = json;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            ensureIndex();
        } catch (Exception error) {
            LoggerFactory.getLogger(getClass())
                    .warn("Search index unavailable; catalog persistence remains available", error);
        }
    }

    public SearchPage search(
            String text,
            String category,
            BigDecimal minimum,
            BigDecimal maximum,
            String currency,
            int page,
            int size,
            String sort) {
        if ((long) page * size + size > 10_000
                || minimum != null && maximum != null && minimum.compareTo(maximum) > 0)
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "INVALID_SEARCH_RANGE", "Invalid search range or page window");
        var sample = Timer.start(metrics);
        try {
            var filters = new ArrayList<Map<String, Object>>();
            filters.add(Map.of("term", Map.of("active", true)));
            filters.add(Map.of("term", Map.of("currency", currency)));
            if (category != null) filters.add(Map.of("term", Map.of("category", category)));
            var range = new HashMap<String, Object>();
            if (minimum != null) range.put("gte", minimum.movePointRight(2).longValueExact());
            if (maximum != null) range.put("lte", maximum.movePointRight(2).longValueExact());
            if (!range.isEmpty()) filters.add(Map.of("range", Map.of("priceMinor", range)));
            Object match =
                    text == null || text.isBlank()
                            ? Map.of("match_all", Map.of())
                            : Map.of(
                            "multi_match", Map.of("query", text, "fields", List.of("name^3", "description")));
            Sort ordering =
                    switch (sort) {
                        case "priceAsc" -> Sort.by("priceMinor").ascending().and(Sort.by("id"));
                        case "priceDesc" -> Sort.by("priceMinor").descending().and(Sort.by("id"));
                        case "newest" -> Sort.by("updatedAt").descending().and(Sort.by("id"));
                        default -> Sort.by(Sort.Order.desc("_score"), Sort.Order.asc("id"));
                    };
            var query =
                    new StringQuery(
                            json.writeValueAsString(
                                    Map.of("bool", Map.of("must", List.of(match), "filter", filters))),
                            PageRequest.of(page, size),
                            ordering);
            query.setTrackTotalHits(true);
            var hits = operations.search(query, ProductDocument.class);
            var items =
                    hits.getSearchHits().stream()
                            .map(
                                    hit -> {
                                        var d = hit.getContent();
                                        return new SearchItem(
                                                UUID.fromString(d.id()),
                                                d.sku(),
                                                d.name(),
                                                d.description(),
                                                d.category(),
                                                BigDecimal.valueOf(d.priceMinor(), 2),
                                                d.currency());
                                    })
                            .toList();
            return new SearchPage(items, hits.getTotalHits(), page, size);
        } catch (RuntimeException error) {
            metrics.counter("commerceflow.search.failures").increment();
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SEARCH_UNAVAILABLE",
                    "Product search temporarily unavailable; browse the catalog instead");
        } finally {
            sample.stop(metrics.timer("commerceflow.search.query"));
        }
    }

    @Transactional
    public void index(ProductChanged product) {
        lock();
        try {
            ensureIndex();
            write(WRITE_ALIAS, product);
        } catch (IOException error) {
            throw new IllegalStateException("Elasticsearch indexing failed", error);
        }
    }

    @Transactional
    public RebuildResult rebuild() {
        // A shared database lock serializes rebuild/swap with all catalog indexing instances.
        // Product writes continue and their durable Kafka events apply to the new alias after the swap.
        lock();
        String index = "cf-products-v1-" + UUID.randomUUID();
        long count = 0;
        try {
            createIndex(index, false);
            int page = 0;
            while (true) {
                var batch = products.findAll(PageRequest.of(page++, 500, Sort.by("id")));
                for (var product : batch) {
                    var p = ProductView.from(product);
                    write(
                            index,
                            new ProductChanged(
                                    p.id(),
                                    p.sku(),
                                    p.name(),
                                    p.description(),
                                    p.category(),
                                    p.price(),
                                    p.currency(),
                                    p.active(),
                                    p.version(),
                                    p.updatedAt()));
                    count++;
                }
                entities.clear();
                if (!batch.hasNext()) break;
            }
            client.indices().refresh(r -> r.index(index));
            client
                    .indices()
                    .updateAliases(
                            a ->
                                    a.actions(v -> v.remove(r -> r.index("*").alias(READ_ALIAS).mustExist(false)))
                                            .actions(v -> v.remove(r -> r.index("*").alias(WRITE_ALIAS).mustExist(false)))
                                            .actions(v -> v.add(r -> r.index(index).alias(READ_ALIAS)))
                                            .actions(
                                                    v -> v.add(r -> r.index(index).alias(WRITE_ALIAS).isWriteIndex(true))));
            return new RebuildResult(index, count);
        } catch (IOException | RuntimeException error) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SEARCH_REBUILD_FAILED",
                    "Rebuild failed; inspect search availability and retained indices before retrying");
        }
    }

    private void lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('commerceflow-product-index',0))")
                .query(Object.class)
                .single();
    }

    private void ensureIndex() throws IOException {
        if (client.indices().existsAlias(r -> r.name(READ_ALIAS)).value()) return;
        try {
            createIndex("cf-products-v1", true);
        } catch (ElasticsearchException error) {
            if (!client.indices().existsAlias(r -> r.name(READ_ALIAS)).value()) throw error;
        }
    }

    private void createIndex(String index, boolean aliases) throws IOException {
        try (var mapping =
                     Objects.requireNonNull(getClass().getResourceAsStream("/search/product-index.json"))) {
            client
                    .indices()
                    .create(
                            r -> {
                                r.index(index).withJson(mapping);
                                if (aliases)
                                    r.aliases(READ_ALIAS, a -> a).aliases(WRITE_ALIAS, a -> a.isWriteIndex(true));
                                return r;
                            });
        }
    }

    private void write(String index, ProductChanged product) throws IOException {
        try {
            client.index(
                    r ->
                            r.index(index)
                                    .id(product.id().toString())
                                    .version(product.version() + 1)
                                    .versionType(VersionType.External)
                                    .document(ProductDocument.from(product)));
        } catch (ElasticsearchException error) {
            if (error.status() != 409)
                throw error; // Same or older source version cannot overwrite this document.
        }
    }
}

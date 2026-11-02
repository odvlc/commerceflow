/*
 * Uses atomic value/version operations to resist stale refills; cache failure falls back to the
 * catalog database.
 */
package com.commerceflow.catalog.application;

import com.commerceflow.catalog.api.ProductView;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "catalog.cache.enabled", havingValue = "true", matchIfMissing = true)
public class ProductCache {

    // The longer-lived version watermark rejects an old read that finishes after invalidation.
    private static final DefaultRedisScript<Long> STORE =
            new DefaultRedisScript<>(
                    """
                            local version=redis.call('GET',KEYS[2])
                            if version and tonumber(version)>tonumber(ARGV[1]) then return 0 end
                            redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3])
                            redis.call('SET',KEYS[2],ARGV[1],'EX',ARGV[4])
                            return 1
                            """,
                    Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE =
            new DefaultRedisScript<>(
                    """
                            local version=redis.call('GET',KEYS[2])
                            if version and tonumber(version)>=tonumber(ARGV[1]) then return 0 end
                            redis.call('DEL',KEYS[1])
                            redis.call('SET',KEYS[2],ARGV[1],'EX',ARGV[2])
                            return 1
                            """,
                    Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final MeterRegistry metrics;
    private final long ttl;

    public ProductCache(
            StringRedisTemplate redis,
            JsonMapper json,
            MeterRegistry metrics,
            @Value("${catalog.cache.ttl:PT60S}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.metrics = metrics;
        this.ttl = ttl.toSeconds();
        if (this.ttl < 1)
            throw new IllegalArgumentException("Cache TTL must be positive");
    }

    public Optional<ProductView> get(UUID id) {
        try {
            String raw = redis.opsForValue().get(keys(id).getFirst());
            metrics.counter(raw == null ? "commerceflow.cache.misses" : "commerceflow.cache.hits")
                    .increment();
            return raw == null ? Optional.empty() : Optional.of(json.readValue(raw, ProductView.class));
        } catch (RuntimeException error) {
            failure();
            return Optional.empty();
        }
    }

    public void put(ProductView product) {
        try {
            redis.execute(
                    STORE,
                    keys(product.id()),
                    Long.toString(product.version()),
                    json.writeValueAsString(product),
                    Long.toString(ttl),
                    Long.toString(ttl * 2));
        } catch (RuntimeException error) {
            failure();
        }
    }

    public boolean invalidate(UUID id, long version) {
        try {
            redis.execute(INVALIDATE, keys(id), Long.toString(version), Long.toString(ttl * 2));
            return true;
        } catch (RuntimeException error) {
            failure();
            return false;
        }
    }

    private List<String> keys(UUID id) {
        // Redis hash tags co-locate value and version keys so each Lua script remains a single-slot operation.
        return List.of("cf:product:{" + id + "}:value", "cf:product:{" + id + "}:version");
    }

    private void failure() {
        metrics.counter("commerceflow.cache.failures").increment();
    }
}

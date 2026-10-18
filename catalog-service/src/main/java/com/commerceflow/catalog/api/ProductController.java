package com.commerceflow.catalog.api;

import com.commerceflow.catalog.application.CatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1")
public class ProductController {

    private final CatalogService catalog;

    public ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    public record ProductPage(
            List<ProductView> items, int page, int size, long totalElements, int totalPages) {

    }

    @GetMapping("/products")
    public ProductPage list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 80) String category) {
        var result = catalog.list(page, size, category);
        return new ProductPage(
                result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/products/{id}")
    public ProductView get(
            @PathVariable UUID id,
            @RequestHeader(value = "Cache-Control", defaultValue = "") String cacheControl) {
        return catalog.get(id, cacheControl.toLowerCase(java.util.Locale.ROOT).contains("no-cache"));
    }

    @GetMapping("/admin/products")
    public ProductPage adminList(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = catalog.adminList(page, size);
        return new ProductPage(
                result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    @PostMapping("/admin/products")
    public ResponseEntity<ProductView> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key,
            @Valid @RequestBody ProductInput input,
            @RequestAttribute("correlationId") String correlationId) {
        var result = catalog.create(key, input, correlationId);
        return ResponseEntity.created(URI.create("/api/v1/products/" + result.id()))
                .eTag(Long.toString(result.version()))
                .body(result);
    }

    @PutMapping("/admin/products/{id}")
    public ResponseEntity<ProductView> update(
            @PathVariable UUID id,
            @RequestHeader("If-Match") @Pattern(regexp = "\"[0-9]+\"") String version,
            @Valid @RequestBody ProductInput input,
            @RequestAttribute("correlationId") String correlationId) {
        var result =
                catalog.update(id, Long.parseLong(version.replace("\"", "")), input, correlationId);
        return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result);
    }
}

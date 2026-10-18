package com.commerceflow.catalog.search;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@Validated
@ConditionalOnProperty(name = "catalog.search.enabled", havingValue = "true", matchIfMissing = true)
public class SearchController {

    private final ProductSearch search;

    public SearchController(ProductSearch search) {
        this.search = search;
    }

    @GetMapping("/api/v1/products/search")
    public ProductSearch.SearchPage search(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) @Size(max = 80) String category,
            @RequestParam(required = false)
            @DecimalMin("0")
            @DecimalMax("999999999.99")
            @Digits(integer = 9, fraction = 2)
            BigDecimal minPrice,
            @RequestParam(required = false)
            @DecimalMin("0")
            @DecimalMax("999999999.99")
            @Digits(integer = 9, fraction = 2)
            BigDecimal maxPrice,
            @RequestParam(defaultValue = "USD") @Pattern(regexp = "USD|EUR|GBP") String currency,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "relevance")
            @Pattern(regexp = "relevance|priceAsc|priceDesc|newest")
            String sort) {
        return search.search(q, category, minPrice, maxPrice, currency, page, size, sort);
    }

    @PostMapping("/api/v1/admin/search/rebuild")
    public ProductSearch.RebuildResult rebuild() {
        return search.rebuild();
    }
}

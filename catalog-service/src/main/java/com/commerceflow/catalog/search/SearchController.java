package com.commerceflow.catalog.search;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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

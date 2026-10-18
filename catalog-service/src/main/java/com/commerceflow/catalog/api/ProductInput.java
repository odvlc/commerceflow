package com.commerceflow.catalog.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ProductInput(
    @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String sku,
    @NotBlank @Size(max = 200) String name,
    @NotNull @Size(max = 4000) String description,
    @NotBlank @Size(max = 80) String category,
    @NotNull @DecimalMin("0.00") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2)
        BigDecimal price,
    @NotNull @Pattern(regexp = "USD|EUR|GBP") String currency,
    @NotNull Boolean active) {}

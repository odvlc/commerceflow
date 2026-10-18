package com.commerceflow.cart;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
@Validated
public class CartController {
  public record AddItem(@NotNull UUID productId, @Min(1) @Max(1000) int quantity) {}

  public record SetQuantity(@Min(1) @Max(1000) int quantity) {}

  private final CartService carts;

  public CartController(CartService carts) {
    this.carts = carts;
  }

  @GetMapping
  public CartService.CartView get(@AuthenticationPrincipal Jwt jwt) {
    return carts.get(UUID.fromString(jwt.getSubject()));
  }

  @PostMapping("/items")
  public CartService.CartView add(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody AddItem input,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key) {
    return carts.mutate(
        UUID.fromString(jwt.getSubject()), "ADD", input.productId(), input.quantity(), key);
  }

  @PatchMapping("/items/{productId}")
  public CartService.CartView patch(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID productId,
      @Valid @RequestBody SetQuantity input,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key) {
    return carts.mutate(UUID.fromString(jwt.getSubject()), "SET", productId, input.quantity(), key);
  }

  @DeleteMapping("/items/{productId}")
  public CartService.CartView delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID productId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key) {
    return carts.mutate(UUID.fromString(jwt.getSubject()), "DELETE", productId, 0, key);
  }
}

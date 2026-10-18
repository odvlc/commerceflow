package com.commerceflow.cart;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cart")
@Validated
public class CartController {

    public record AddItem(@NotNull UUID productId, @Min(1) @Max(1000) int quantity) {

    }

    public record SetQuantity(@Min(1) @Max(1000) int quantity) {

    }

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    @GetMapping
    public org.springframework.http.ResponseEntity<CartService.CartView> get(@AuthenticationPrincipal Jwt jwt) {
        var cart = carts.get(UUID.fromString(jwt.getSubject()));
        return org.springframework.http.ResponseEntity.ok().eTag(cart.revision()).body(cart);
    }

    @PostMapping("/items")
    public CartService.CartView add(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddItem input,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key, @RequestHeader(value = "If-Match", required = false) String revision) {
        return carts.mutate(
                UUID.fromString(jwt.getSubject()), "ADD", input.productId(), input.quantity(), key, revision);
    }

    @PatchMapping("/items/{productId}")
    public CartService.CartView patch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID productId,
            @Valid @RequestBody SetQuantity input,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key, @RequestHeader(value = "If-Match", required = false) String revision) {
        return carts.mutate(UUID.fromString(jwt.getSubject()), "SET", productId, input.quantity(), key, revision);
    }

    @DeleteMapping("/items/{productId}")
    public CartService.CartView delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID productId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key, @RequestHeader(value = "If-Match", required = false) String revision) {
        return carts.mutate(UUID.fromString(jwt.getSubject()), "DELETE", productId, 0, key, revision);
    }
}

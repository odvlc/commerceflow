package com.commerceflow.order;

import com.commerceflow.order.OrderModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderView> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key,
            @Valid @RequestBody CreateOrder input,
            @RequestAttribute("correlationId") String correlation) {
        var order = orders.create(customer(jwt), key, input, correlation);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.id())).body(order);
    }

    @GetMapping("/orders/{id}")
    public OrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orders.get(customer(jwt), id);
    }

    @GetMapping("/orders")
    public OrderPage list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orders.list(customer(jwt), page, size);
    }

    @PostMapping("/orders/{id}/cancel")
    public OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orders.cancel(customer(jwt), id);
    }

    @GetMapping("/orders/{id}/explanation")
    public OrderExplanation explanation(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return orders.explanation(customer(jwt),id);
    }
    @GetMapping("/admin/orders/{id}/explanation")
    public OrderExplanation supportExplanation(@PathVariable UUID id) { return orders.adminExplanation(id); }

    @GetMapping("/admin/orders")
    public OrderPage admin(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orders.adminList(page, size);
    }

    private UUID customer(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

/*
 * Separates customer-owned order access from administrative/support routes; supplied order IDs
 * never grant ownership.
 */
package com.commerceflow.order;

import com.commerceflow.order.OrderModels.CreateOrder;
import com.commerceflow.order.OrderModels.OrderExplanation;
import com.commerceflow.order.OrderModels.OrderPage;
import com.commerceflow.order.OrderModels.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

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
        // Customer routes always pass the signed subject; the service conceals orders owned by another
        // user.
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
    public OrderExplanation explanation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orders.explanation(customer(jwt), id);
    }

    @GetMapping("/admin/orders/{id}/explanation")
    public OrderExplanation supportExplanation(@PathVariable UUID id) {
        // The separate support/admin route is role-checked before this unscoped read is permitted.
        return orders.adminExplanation(id);
    }

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

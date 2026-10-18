package com.commerceflow.inventory;

import com.commerceflow.contracts.CheckoutEvents.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.UUID;

import org.springframework.web.bind.annotation.*;

@RestController
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    public record StockWrite(
            @NotNull @Min(0) @Max(1_000_000_000) Integer availableQuantity, @Min(0) Long version) {

    }

    @GetMapping("/api/v1/admin/inventory/{productId}")
    public InventoryService.Stock get(@PathVariable UUID productId) {
        return inventory.stock(productId);
    }

    @PutMapping("/api/v1/admin/inventory/{productId}")
    public InventoryService.Stock put(
            @PathVariable UUID productId, @Valid @RequestBody StockWrite input) {
        return inventory.setStock(productId, input.availableQuantity(), input.version());
    }

    @PostMapping("/internal/v1/reservations/{orderId}")
    public InventoryService.Reservation reserve(
            @PathVariable UUID orderId,
            @RequestBody ReserveInventory input,
            @RequestAttribute("correlationId") String correlation) {
        return inventory.reserve(orderId, input, correlation);
    }

    @PostMapping("/internal/v1/reservations/{orderId}/commit")
    public InventoryService.Reservation commit(
            @PathVariable UUID orderId, @RequestAttribute("correlationId") String correlation) {
        return inventory.commit(orderId, correlation);
    }

    @PostMapping("/internal/v1/reservations/{orderId}/release")
    public InventoryService.Reservation release(
            @PathVariable UUID orderId, @RequestAttribute("correlationId") String correlation) {
        return inventory.release(orderId, correlation);
    }
}

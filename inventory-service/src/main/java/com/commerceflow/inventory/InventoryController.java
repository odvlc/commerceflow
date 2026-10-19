/*
 * Separates stock administration from service reservation/commit/release commands, with bounded
 * quantities.
 */
package com.commerceflow.inventory;

import com.commerceflow.contracts.CheckoutEvents.ReserveInventory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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
        // These internal commands are protected by service scope; accepting an order UUID is not
        // customer authorization.
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

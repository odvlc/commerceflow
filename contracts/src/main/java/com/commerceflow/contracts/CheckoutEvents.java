/*
 * Defines immutable checkout command/result payloads only; consumers must handle replay and late
 * delivery.
 */
package com.commerceflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CheckoutEvents {

    private CheckoutEvents() {
    }

    public enum PaymentScenario {
        APPROVE,
        DECLINE,
        TIMEOUT,
        DELAY
    }

    public record StockItem(UUID productId, int quantity) {

        public StockItem {
            if (productId == null || quantity < 1 || quantity > 1000)
                throw new IllegalArgumentException("Invalid stock item");
        }
    }

    public record ReserveInventory(List<StockItem> items) {

        public ReserveInventory {
            // Freeze the list at the wire boundary so later caller mutation cannot change an already
            // validated command.
            items = List.copyOf(items);
            if (items.isEmpty()
                    || items.size() > 100
                    || items.stream().map(StockItem::productId).distinct().count() != items.size())
                throw new IllegalArgumentException("Unique inventory items required");
        }
    }

    public record InventoryResult(String status, String reason) {

    }

    public record AuthorizePayment(BigDecimal amount, String currency, PaymentScenario scenario) {

        public AuthorizePayment {
            // Validate message payloads as well as HTTP DTOs; Kafka commands do not pass through MVC
            // validation.
            if (amount == null
                    || amount.signum() < 0
                    || amount.scale() > 2
                    || amount.precision() > 19
                    || currency == null
                    || !currency.matches("USD|EUR|GBP")
                    || scenario == null) throw new IllegalArgumentException("Invalid payment request");
        }
    }

    public record PaymentResult(String status, String reason) {

    }

    public record OrderStatus(
            UUID customerId, String status, BigDecimal totalAmount, String currency) {

    }

    public record NotificationCreated(
            UUID id, UUID orderId, UUID customerId, String status, String message, Instant createdAt) {

    }

    public record ProductChanged(
            UUID id,
            String sku,
            String name,
            String description,
            String category,
            BigDecimal price,
            String currency,
            boolean active,
            long version,
            Instant updatedAt) {

    }

    public record Command(String reason) {

    }
}

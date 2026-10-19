/*
 * Defines validated order requests and public views, including persisted status reasons rather
 * than inferred explanations.
 */
package com.commerceflow.order;

import com.commerceflow.contracts.CheckoutEvents.PaymentScenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderModels {

    private OrderModels() {
    }

    public record Item(@NotNull UUID productId, @Min(1) @Max(1000) int quantity) {

    }

    public record CreateOrder(
            @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items,
            @NotNull PaymentScenario paymentScenario) {

    }

    public record Line(UUID productId, int quantity, BigDecimal unitPrice) {

    }

    public record OrderView(
            UUID id,
            UUID customerId,
            OrderState status,
            BigDecimal totalAmount,
            String currency,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<Line> items) {

    }

    public record OrderPage(List<OrderView> items, int page, int size, long totalElements) {

    }

    // History versions identify committed business transitions, not model-inferred explanations.
    public record StatusHistory(long version, OrderState status, String reason, Instant recordedAt) {

    }

    public record OrderExplanation(UUID id, OrderState status, long version, String reason, boolean reasonKnown,
                                   Instant observedAt, List<StatusHistory> history) {

    }
}

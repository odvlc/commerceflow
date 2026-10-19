/*
 * Builds idempotent orders from trusted catalog quotes and enforces ownership on customer reads
 * and cancellations.
 */
package com.commerceflow.order;

import com.commerceflow.contracts.CheckoutEvents.Command;
import com.commerceflow.contracts.CheckoutEvents.ReserveInventory;
import com.commerceflow.contracts.CheckoutEvents.StockItem;
import com.commerceflow.order.OrderModels.CreateOrder;
import com.commerceflow.order.OrderModels.Item;
import com.commerceflow.order.OrderModels.Line;
import com.commerceflow.order.OrderModels.OrderExplanation;
import com.commerceflow.order.OrderModels.OrderPage;
import com.commerceflow.order.OrderModels.OrderView;
import com.commerceflow.support.BusinessException;
import com.commerceflow.support.OutboxStore;
import com.commerceflow.support.RequestHash;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final TrustedCatalog catalog;
    private final OutboxStore outbox;
    private final java.time.Duration timeout;

    public OrderService(
            OrderRepository orders,
            TrustedCatalog catalog,
            OutboxStore outbox,
            @org.springframework.beans.factory.annotation.Value("${saga.step-timeout:PT60S}")
            java.time.Duration timeout) {
        this.orders = orders;
        this.catalog = catalog;
        this.outbox = outbox;
        this.timeout = timeout;
    }

    @Transactional
    public OrderView create(UUID customer, String key, CreateOrder request, String correlation) {
        var items = request.items().stream().sorted(Comparator.comparing(Item::productId)).toList();
        if (items.stream().map(Item::productId).distinct().count() != items.size())
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "DUPLICATE_PRODUCT", "Each product must occur once");
        var hash = RequestHash.sha256(items + ":" + request.paymentScenario());
        orders.lockKey(customer, key);
        // Resolve retries before repricing: the original accepted order is authoritative for the same
        // request key.
        var existing = orders.byKey(customer, key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash))
                throw new BusinessException(
                        HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Key reused with different contents");
            return orders.view(existing.get());
        }
        var lines = new ArrayList<Line>();
        BigDecimal total = new BigDecimal("0.00");
        String currency = null;
        for (var item : items) {
            // Prices and active status come from Catalog, never from the browser cart payload.
            var quote = catalog.quote(item.productId(), correlation);
            if (currency != null && !currency.equals(quote.currency()))
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MIXED_CURRENCIES",
                        "All products must use one currency");
            currency = quote.currency();
            lines.add(new Line(item.productId(), item.quantity(), quote.price()));
            total = total.add(quote.price().multiply(BigDecimal.valueOf(item.quantity())));
        }
        var id = UUID.randomUUID();
        orders.create(
                id,
                customer,
                key,
                hash,
                request,
                lines,
                total,
                currency,
                correlation,
                Instant.now().plus(timeout));
        // The order, first saga step and command outbox row commit together; Kafka publication happens
        // later.
        orders.step(id, "RESERVE_INVENTORY", "PENDING", null);
        outbox.append(
                "ReserveInventory",
                id,
                correlation,
                new ReserveInventory(
                        items.stream().map(i -> new StockItem(i.productId(), i.quantity())).toList()));
        return orders.view(orders.required(id, false));
    }

    @Transactional(readOnly = true)
    public OrderView get(UUID customer, UUID id) {
        return orders.view(owned(customer, id, false));
    }

    @Transactional(readOnly = true)
    public OrderPage list(UUID customer, int page, int size) {
        return orders.list(customer, page, size);
    }

    @Transactional(readOnly = true)
    public OrderPage adminList(int page, int size) {
        return orders.list(null, page, size);
    }

    @Transactional
    public OrderView cancel(UUID customer, UUID id) {
        var order = owned(customer, id, true);
        switch (order.status()) {
            case CANCELLED, CANCELLING_INVENTORY, CANCELLING_PAYMENT -> {
                return orders.view(order);
            }
            case PENDING_INVENTORY -> {
                orders.recordReason(id, "CUSTOMER_CANCELLED");
                orders.move(order, OrderState.CANCELLING_INVENTORY, Instant.now().plus(timeout));
                orders.step(id, "RELEASE_INVENTORY", "PENDING", null);
                outbox.append(
                        "ReleaseInventory", id, order.correlation(), new Command("CUSTOMER_CANCELLED"));
            }
            // Cancel payment before releasing stock because an in-flight authorization can still
            // succeed.
            case AWAITING_PAYMENT -> {
                orders.recordReason(id, "CUSTOMER_CANCELLED");
                orders.move(order, OrderState.CANCELLING_PAYMENT, Instant.now().plus(timeout));
                orders.step(id, "CANCEL_PAYMENT", "PENDING", null);
                outbox.append("CancelPayment", id, order.correlation(), new Command("CUSTOMER_CANCELLED"));
            }
            default -> throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ORDER_NOT_CANCELLABLE",
                    "Order fulfillment has already started");
        }
        return orders.view(orders.required(id, false));
    }

    @Transactional(readOnly = true)
    public OrderExplanation explanation(UUID customer, UUID id) {
        return orders.explanation(owned(customer, id, false));
    }

    @Transactional(readOnly = true)
    public OrderExplanation adminExplanation(UUID id) {
        return orders.explanation(orders.required(id, false));
    }

    private OrderRepository.Row owned(UUID customer, UUID id, boolean lock) {
        var order = orders.required(id, lock);
        // Use the same not-found response for missing and foreign-owned IDs to avoid disclosing another
        // order.
        if (!order.customerId().equals(customer))
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
        return order;
    }
}

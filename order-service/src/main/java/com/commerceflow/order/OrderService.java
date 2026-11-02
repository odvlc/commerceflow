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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final TrustedCatalog catalog;
    private final OutboxStore outbox;
    private final Duration timeout;

    public OrderService(
            OrderRepository orderRepository,
            TrustedCatalog catalog,
            OutboxStore outbox,
            @Value("${saga.step-timeout:PT60S}")
            Duration timeout) {
        this.orderRepository = orderRepository;
        this.catalog = catalog;
        this.outbox = outbox;
        this.timeout = timeout;
    }

    @Transactional
    public OrderView create(UUID customer, String key, CreateOrder request, String correlation) {
        List<Item> items = request.items().stream().sorted(Comparator.comparing(Item::productId)).toList();
        if (items.stream().map(Item::productId).distinct().count() != items.size())
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_PRODUCT", "Each product must occur once");

        var hash = RequestHash.sha256(items + ":" + request.paymentScenario());
        orderRepository.lockKey(customer, key);
        // Resolve retries before repricing. The accepted order remains authoritative for its request key.
        Optional<OrderRepository.Row> existing = orderRepository.byKey(customer, key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash))
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Key reused with different contents");
            return orderRepository.view(existing.get());
        }
        ArrayList<Line> lines = new ArrayList<>();
        BigDecimal total = new BigDecimal("0.00");
        String currency = null;
        for (var item : items) {
            // Prices and active status come from Catalog, never from the browser cart payload.
            TrustedCatalog.Quote quote = catalog.quote(item.productId(), correlation);
            if (currency != null && !currency.equals(quote.currency()))
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MIXED_CURRENCIES",
                        "All products must use one currency");
            currency = quote.currency();
            lines.add(new Line(item.productId(), item.quantity(), quote.price()));
            total = total.add(quote.price().multiply(BigDecimal.valueOf(item.quantity())));
        }
        UUID id = UUID.randomUUID();
        orderRepository.create(
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
        orderRepository.step(id, "RESERVE_INVENTORY", "PENDING", null);
        outbox.append(
                "ReserveInventory",
                id,
                correlation,
                new ReserveInventory(
                        items.stream().map(i -> new StockItem(i.productId(), i.quantity())).toList()));
        return orderRepository.view(orderRepository.required(id, false));
    }

    @Transactional(readOnly = true)
    public OrderView get(UUID customer, UUID id) {
        return orderRepository.view(owned(customer, id, false));
    }

    @Transactional(readOnly = true)
    public OrderPage list(UUID customer, int page, int size) {
        return orderRepository.list(customer, page, size);
    }

    @Transactional(readOnly = true)
    public OrderPage adminList(int page, int size) {
        return orderRepository.list(null, page, size);
    }

    @Transactional
    public OrderView cancel(UUID customer, UUID id) {
        var order = owned(customer, id, true);
        switch (order.status()) {
            case CANCELLED, CANCELLING_INVENTORY, CANCELLING_PAYMENT -> {
                return orderRepository.view(order);
            }
            case PENDING_INVENTORY -> {
                orderRepository.recordReason(id, "CUSTOMER_CANCELLED");
                orderRepository.move(order, OrderState.CANCELLING_INVENTORY, Instant.now().plus(timeout));
                orderRepository.step(id, "RELEASE_INVENTORY", "PENDING", null);
                outbox.append(
                        "ReleaseInventory", id, order.correlation(), new Command("CUSTOMER_CANCELLED"));
            }
            // Cancel payment before releasing stock; an in-flight authorization may still succeed.
            case AWAITING_PAYMENT -> {
                orderRepository.recordReason(id, "CUSTOMER_CANCELLED");
                orderRepository.move(order, OrderState.CANCELLING_PAYMENT, Instant.now().plus(timeout));
                orderRepository.step(id, "CANCEL_PAYMENT", "PENDING", null);
                outbox.append("CancelPayment", id, order.correlation(), new Command("CUSTOMER_CANCELLED"));
            }
            default -> throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ORDER_NOT_CANCELLABLE",
                    "Order fulfillment has already started");
        }
        return orderRepository.view(orderRepository.required(id, false));
    }

    @Transactional(readOnly = true)
    public OrderExplanation explanation(UUID customer, UUID id) {
        return orderRepository.explanation(owned(customer, id, false));
    }

    @Transactional(readOnly = true)
    public OrderExplanation adminExplanation(UUID id) {
        return orderRepository.explanation(orderRepository.required(id, false));
    }

    private OrderRepository.Row owned(UUID customer, UUID id, boolean lock) {
        var order = orderRepository.required(id, lock);
        // Return the same not-found response for missing and foreign-owned orders.
        if (!order.customerId().equals(customer))
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
        return order;
    }
}

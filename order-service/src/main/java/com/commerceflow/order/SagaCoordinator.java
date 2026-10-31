/*
 * Advances the persisted checkout saga and emits local outbox commands; payment uncertainty is
 * reconciled before stock release.
 */
package com.commerceflow.order;

import com.commerceflow.contracts.CheckoutEvents.AuthorizePayment;
import com.commerceflow.contracts.CheckoutEvents.Command;
import com.commerceflow.contracts.CheckoutEvents.InventoryResult;
import com.commerceflow.contracts.CheckoutEvents.OrderStatus;
import com.commerceflow.contracts.CheckoutEvents.PaymentResult;
import com.commerceflow.support.BusinessMetrics;
import com.commerceflow.support.OutboxStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.commerceflow.order.OrderState.AWAITING_PAYMENT;
import static com.commerceflow.order.OrderState.CANCELLED;
import static com.commerceflow.order.OrderState.CANCELLING_INVENTORY;
import static com.commerceflow.order.OrderState.CANCELLING_PAYMENT;
import static com.commerceflow.order.OrderState.COMMITTING_INVENTORY;
import static com.commerceflow.order.OrderState.CONFIRMED;
import static com.commerceflow.order.OrderState.PENDING_INVENTORY;

@Service
public class SagaCoordinator {

    private final OrderRepository orders;
    private final OutboxStore outbox;
    private final JdbcClient jdbc;
    private final Duration timeout;
    private final MeterRegistry metrics;

    public SagaCoordinator(
            OrderRepository orders,
            OutboxStore outbox,
            JdbcClient jdbc,
            @Value("${saga.step-timeout:PT60S}") Duration timeout,
            MeterRegistry metrics) {
        this.orders = orders;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.timeout = timeout;
        this.metrics = metrics;
    }

    @Transactional
    public void inventory(UUID id, InventoryResult result) {
        var order = orders.required(id, true);
        switch (result.status()) {
            case "RESERVED" -> {
                if (order.status() != PENDING_INVENTORY)
                    return;
                orders.step(id, "RESERVE_INVENTORY", "SUCCEEDED", null);
                move(order, AWAITING_PAYMENT);
                command(
                        order,
                        "AUTHORIZE_PAYMENT",
                        "AuthorizePayment",
                        new AuthorizePayment(order.total(), order.currency(), order.scenario()));
            }
            case "REJECTED" -> {
                if (order.status() != PENDING_INVENTORY)
                    return;
                orders.recordReason(id, "INSUFFICIENT_STOCK".equals(result.reason()) ? "INSUFFICIENT_STOCK" : "INVENTORY_REJECTED");
                orders.step(id, "RESERVE_INVENTORY", "FAILED", result.reason());
                move(order, CANCELLED);
            }
            case "COMMITTED" -> {
                if (order.status() != COMMITTING_INVENTORY)
                    return;
                orders.step(id, "COMMIT_INVENTORY", "SUCCEEDED", null);
                move(order, CONFIRMED);
            }
            case "RELEASED" -> {
                if (order.status() != CANCELLING_INVENTORY)
                    return;
                orders.step(id, "RELEASE_INVENTORY", "SUCCEEDED", null);
                move(order, CANCELLED);
            }
            default -> throw new IllegalArgumentException("Unknown inventory result " + result.status());
        }
    }

    @Transactional
    public void payment(UUID id, PaymentResult result) {
        var order = orders.required(id, true);
        switch (result.status()) {
            case "AUTHORIZED" -> {
                // Late authorization cannot revive a cancellation; payment reconciliation handles the
                // already-issued operation.
                if (order.status() != AWAITING_PAYMENT)
                    return;
                orders.step(id, "AUTHORIZE_PAYMENT", "SUCCEEDED", null);
                move(order, COMMITTING_INVENTORY);
                command(order, "COMMIT_INVENTORY", "CommitInventory", new Command("PAYMENT_AUTHORIZED"));
            }
            case "DECLINED", "CANCELLED", "REFUNDED" -> {
                if (order.status() != AWAITING_PAYMENT && order.status() != CANCELLING_PAYMENT)
                    return;
                if (result.status().equals("DECLINED"))
                    orders.recordReason(id, "PAYMENT_DECLINED");
                orders.step(
                        id,
                        order.status() == CANCELLING_PAYMENT ? "CANCEL_PAYMENT" : "AUTHORIZE_PAYMENT",
                        result.status().equals("DECLINED") ? "FAILED" : "SUCCEEDED",
                        result.reason());
                move(order, CANCELLING_INVENTORY);
                command(
                        order,
                        "RELEASE_INVENTORY",
                        "ReleaseInventory",
                        new Command("PAYMENT_" + result.status()));
            }
            default -> throw new IllegalArgumentException("Unknown payment result " + result.status());
        }
    }

    @Transactional
    public void expiring(UUID id) {
        var order = orders.required(id, true);
        reconcile(order, "RESERVATION_EXPIRED");
    }

    @Transactional
    public int reconcileDeadlines() {
        var ids = jdbc.sql(
                        "SELECT id FROM orders WHERE deadline_at<now() AND status NOT IN"
                                + " ('CONFIRMED','CANCELLED') ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED")
                .query(UUID.class)
                .list();
        for (var id : ids)
            reconcile(orders.required(id, true), "STEP_TIMEOUT");
        return ids.size();
    }

    private void reconcile(OrderRepository.Row order, String reason) {
        switch (order.status()) {
            case PENDING_INVENTORY -> {
                orders.recordReason(order.id(), "RESERVATION_EXPIRED".equals(reason) ? "RESERVATION_EXPIRED" : "INVENTORY_TIMEOUT");
                orders.step(order.id(), "RESERVE_INVENTORY", "FAILED", reason);
                move(order, CANCELLING_INVENTORY);
                command(order, "RELEASE_INVENTORY", "ReleaseInventory", new Command(reason));
            }
            // A timeout does not prove payment failed; cancel/reconcile it before releasing inventory.
            case AWAITING_PAYMENT -> {
                orders.recordReason(order.id(), "RESERVATION_EXPIRED".equals(reason) ? "RESERVATION_EXPIRED" : "PAYMENT_TIMEOUT");
                // Record the timeout, then request cancellation; elapsed time alone does not prove no
                // charge occurred.
                orders.step(order.id(), "AUTHORIZE_PAYMENT", "FAILED", reason);
                move(order, CANCELLING_PAYMENT);
                command(order, "CANCEL_PAYMENT", "CancelPayment", new Command(reason));
            }
            case CANCELLING_PAYMENT -> {
                extend(order);
                command(order, "CANCEL_PAYMENT", "CancelPayment", new Command(reason));
            }
            case CANCELLING_INVENTORY -> {
                extend(order);
                command(order, "RELEASE_INVENTORY", "ReleaseInventory", new Command(reason));
            }
            case COMMITTING_INVENTORY -> {
                // Payment is authorized. Never release stock on uncertainty; reconcile the idempotent
                // commit instead.
                extend(order);
                command(order, "COMMIT_INVENTORY", "CommitInventory", new Command(reason));
            }
            case CONFIRMED, CANCELLED -> {
            }
        }
    }

    private void move(OrderRepository.Row order, OrderState target) {
        orders.move(order, target, Instant.now().plus(timeout));
        outbox.append(
                "OrderStatusChanged",
                order.id(),
                order.correlation(),
                new OrderStatus(order.customerId(), target.name(), order.total(), order.currency()));
        if (target == CONFIRMED || target == CANCELLED)
            BusinessMetrics.afterCommit(
                    () -> {
                        metrics.counter("commerceflow.orders.completed", "result", target.name()).increment();
                        metrics.timer("commerceflow.saga.completion", "result", target.name())
                                .record(Duration.between(order.created(), Instant.now()));
                    });
    }

    private void command(OrderRepository.Row order, String step, String event, Object payload) {
        // Count a step attempt when issuing its command, not when a duplicate result is received.
        orders.step(order.id(), step, "PENDING", null);
        outbox.append(event, order.id(), order.correlation(), payload);
    }

    private void extend(OrderRepository.Row order) {
        jdbc.sql("UPDATE orders SET deadline_at=:deadline,updated_at=now() WHERE id=:id")
                .param("id", order.id())
                .param("deadline", Timestamp.from(Instant.now().plus(timeout)))
                .update();
    }
}

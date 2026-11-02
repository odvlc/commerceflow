/*
 * Persists synthetic customer notifications and their outgoing events together; no external
 * message delivery occurs here.
 */
package com.commerceflow.notification;

import com.commerceflow.contracts.CheckoutEvents.NotificationCreated;
import com.commerceflow.contracts.CheckoutEvents.OrderStatus;
import com.commerceflow.support.OutboxStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    public record Notification(
            UUID id,
            UUID orderId,
            String status,
            String message,
            Instant createdAt) {

    }

    public record NotificationPage(
            List<Notification> items,
            int page,
            int size,
            long totalElements) {

    }

    private final JdbcClient jdbc;
    private final OutboxStore outbox;

    public NotificationService(JdbcClient jdbc, OutboxStore outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional
    public void handleOrderStatus(UUID eventId, UUID order, OrderStatus result, String correlation) {
        String message = switch (result.status()) {
            case "CONFIRMED" -> "Your order is confirmed.";
            case "CANCELLED" -> "Your order was cancelled. Reserved stock has been released.";
            case "AWAITING_PAYMENT" -> "Stock reserved. Your payment is being processed.";
            case "COMMITTING_INVENTORY" -> "Payment authorized. Finalizing your order.";
            case "CANCELLING_PAYMENT", "CANCELLING_INVENTORY" -> "Your cancellation is being processed.";
            default -> throw new IllegalArgumentException("Unknown order status");
        };
        var id = UUID.randomUUID();
        int inserted = jdbc.sql("INSERT INTO notifications(id,event_id,customer_id,order_id,status,message) VALUES"
                        + " (:id,:event,:customer,:order,:status,:message) ON CONFLICT(order_id,status)"
                        + " DO NOTHING")
                .param("id", id)
                .param("event", eventId)
                .param("customer", result.customerId())
                .param("order", order)
                .param("status", result.status())
                .param("message", message)
                .update();
        // Emit an event only when a new order/status notification is stored.
        if (inserted == 1)
            outbox.append(
                    "NotificationCreated",
                    order,
                    correlation,
                    new NotificationCreated(id, order, result.customerId(), result.status(), message, Instant.now()));
    }

    @Transactional(readOnly = true)
    public NotificationPage list(UUID customerId, int page, int size) {
        var total = jdbc.sql("SELECT count(*) FROM notifications WHERE customer_id=:id")
                .param("id", customerId)
                .query(Long.class)
                .single();
        var items = jdbc.sql("SELECT id,order_id,status,message,created_at FROM notifications"
                        + " WHERE customer_id=:id ORDER BY created_at DESC,id LIMIT :size OFFSET :offset")
                .param("id", customerId)
                .param("size", size)
                // Promote before multiplication so a large validated page number cannot
                // overflow a 32-bit offset.
                .param("offset", (long) page * size)
                .query((rs, n) ->
                        new Notification(
                                rs.getObject(1, UUID.class),
                                rs.getObject(2, UUID.class),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getTimestamp(5).toInstant()))
                .list();
        return new NotificationPage(items, page, size, total);
    }
}

/*
 * Reserves stock with conditional SQL and deterministic product locks; replayed commands reuse the
 * order reservation outcome.
 */
package com.commerceflow.inventory;

import com.commerceflow.contracts.CheckoutEvents.Command;
import com.commerceflow.contracts.CheckoutEvents.InventoryResult;
import com.commerceflow.contracts.CheckoutEvents.ReserveInventory;
import com.commerceflow.contracts.CheckoutEvents.StockItem;
import com.commerceflow.support.BusinessException;
import com.commerceflow.support.BusinessMetrics;
import com.commerceflow.support.OutboxStore;
import com.commerceflow.support.RequestHash;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryService {

    public record Stock(UUID productId, int availableQuantity, int reservedQuantity, long version) {

    }

    public record Reservation(UUID orderId, String status) {

    }

    private final JdbcClient jdbc;
    private final OutboxStore outbox;
    private final Duration ttl;
    private final MeterRegistry metrics;

    public InventoryService(
            JdbcClient jdbc,
            OutboxStore outbox,
            @Value("${inventory.reservation-ttl:PT5M}") Duration ttl,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.ttl = ttl;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public Stock stock(UUID productId) {
        return jdbc.sql("SELECT * FROM inventory WHERE product_id=:id")
                .param("id", productId)
                .query(
                        (rs, row) ->
                                new Stock(
                                        productId,
                                        rs.getInt("available_quantity"),
                                        rs.getInt("reserved_quantity"),
                                        rs.getLong("version")))
                .optional()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND", "Stock not found"));
    }

    @Transactional
    public Stock setStock(UUID productId, int available, Long version) {
        if (available < 0 || available > 1_000_000_000)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "Invalid stock quantity");
        if (version == null) {
            int changed =
                    jdbc.sql(
                                    "INSERT INTO inventory(product_id,available_quantity) VALUES (:id,:quantity) ON"
                                            + " CONFLICT DO NOTHING")
                            .param("id", productId)
                            .param("quantity", available)
                            .update();
            if (changed == 0) {
                var existing = stock(productId);
                if (existing.availableQuantity() != available)
                    throw error(HttpStatus.CONFLICT, "VERSION_REQUIRED", "Existing stock requires a version");
            }
        } else {
            int changed =
                    jdbc.sql(
                                    "UPDATE inventory SET available_quantity=:quantity,version=version+1 WHERE"
                                            + " product_id=:id AND version=:version")
                            .param("id", productId)
                            .param("quantity", available)
                            .param("version", version)
                            .update();
            if (changed != 1)
                throw error(
                        HttpStatus.CONFLICT, "VERSION_CONFLICT", "Stock changed; reload before editing");
        }
        return stock(productId);
    }

    @Transactional
    public Reservation reserve(UUID orderId, ReserveInventory request, String correlation) {
        lockOrder(orderId);
        // Canonical UUID text order matches PostgreSQL UUID ordering; signed Java UUID order differs.
        var items =
                request.items().stream()
                        .sorted(Comparator.comparing(item -> item.productId().toString()))
                        .toList();
        String hash = RequestHash.sha256(items.toString());
        var existing =
                jdbc
                        .sql("SELECT status,request_hash FROM reservation_orders WHERE order_id=:id")
                        .param("id", orderId)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst();
        // An order retry reuses its stored reservation outcome; a different item set with that order ID
        // is a conflict.
        if (existing.isPresent()) {
            var oldHash = existing.get().get("request_hash");
            if (oldHash != null && !hash.equals(oldHash))
                throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Reservation contents differ");
            return new Reservation(orderId, existing.get().get("status").toString());
        }
        // Lock products in deterministic order. No partial reservation survives an insufficient-stock
        // result.
        boolean sufficient = true;
        for (var item : items) {
            var quantity =
                    jdbc.sql("SELECT available_quantity FROM inventory WHERE product_id=:id FOR UPDATE")
                            .param("id", item.productId())
                            .query(Integer.class)
                            .optional();
            if (quantity.isEmpty() || quantity.get() < item.quantity())
                sufficient = false;
        }
        String status = sufficient ? "RESERVED" : "REJECTED";
        BusinessMetrics.afterCommit(
                () -> metrics.counter("commerceflow.inventory.reservations", "result", status).increment());
        // Expiry requests reconciliation; it does not authorize this service to release potentially
        // paid stock.
        Instant expiry = Instant.now().plus(ttl);
        jdbc.sql(
                        "INSERT INTO reservation_orders(order_id,request_hash,status,expires_at,correlation_id)"
                                + " VALUES (:id,:hash,:status,:expiry,:correlation)")
                .param("id", orderId)
                .param("hash", hash)
                .param("status", status)
                .param("expiry", Timestamp.from(expiry))
                .param("correlation", correlation)
                .update();
        if (sufficient) {
            for (var item : items) {
                int changed =
                        jdbc.sql(
                                        """
                                                UPDATE inventory SET available_quantity=available_quantity-:quantity,
                                                reserved_quantity=reserved_quantity+:quantity,version=version+1
                                                WHERE product_id=:id AND available_quantity>=:quantity
                                                """)
                                .param("id", item.productId())
                                .param("quantity", item.quantity())
                                .update();
                // The guarded decrement is the final no-oversell check; any failure rolls back the
                // whole multi-item reservation.
                if (changed != 1)
                    throw new IllegalStateException("Inventory invariant violated");
                jdbc.sql(
                                "INSERT INTO reservations(id,order_id,product_id,quantity,status,expires_at) VALUES"
                                        + " (:id,:order,:product,:quantity,'RESERVED',:expiry)")
                        .param("id", UUID.randomUUID())
                        .param("order", orderId)
                        .param("product", item.productId())
                        .param("quantity", item.quantity())
                        .param("expiry", Timestamp.from(expiry))
                        .update();
            }
        }
        outbox.append(
                "InventoryResult",
                orderId,
                correlation,
                new InventoryResult(status, sufficient ? null : "INSUFFICIENT_STOCK"));
        return new Reservation(orderId, status);
    }

    @Transactional
    public Reservation commit(UUID orderId, String correlation) {
        return finish(orderId, "COMMITTED", correlation);
    }

    @Transactional
    public Reservation release(UUID orderId, String correlation) {
        return finish(orderId, "RELEASED", correlation);
    }

    private Reservation finish(UUID orderId, String target, String correlation) {
        lockOrder(orderId);
        var current =
                jdbc.sql("SELECT status FROM reservation_orders WHERE order_id=:id")
                        .param("id", orderId)
                        .query(String.class)
                        .optional();
        if (current.isEmpty()) {
            if (!target.equals("RELEASED"))
                throw error(
                        HttpStatus.CONFLICT, "RESERVATION_MISSING", "Cannot commit a missing reservation");
            // A cancellation tombstone prevents a late ReserveInventory from taking stock.
            jdbc.sql(
                            "INSERT INTO reservation_orders(order_id,status,expires_at,correlation_id) VALUES"
                                    + " (:id,'RELEASED',now(),:correlation)")
                    .param("id", orderId)
                    .param("correlation", correlation)
                    .update();
        } else if (current.get().equals(target)) {
            outbox.append("InventoryResult", orderId, correlation, new InventoryResult(target, null));
            return new Reservation(orderId, target);
        } else if (current.get().equals("RESERVED")) {
            var rows =
                    jdbc.sql(
                                    "SELECT product_id,quantity FROM reservations WHERE order_id=:id ORDER BY"
                                            + " product_id")
                            .param("id", orderId)
                            .query((rs, n) -> new StockItem(rs.getObject(1, UUID.class), rs.getInt(2)))
                            .list();
            for (var item : rows) {
                int changed =
                        jdbc.sql(
                                        "UPDATE inventory SET"
                                                + " reserved_quantity=reserved_quantity-:quantity,available_quantity=available_quantity+:released,version=version+1"
                                                + " WHERE product_id=:id AND reserved_quantity>=:quantity")
                                .param("quantity", item.quantity())
                                .param("released", target.equals("RELEASED") ? item.quantity() : 0)
                                .param("id", item.productId())
                                .update();
                if (changed != 1)
                    throw new IllegalStateException("Reservation accounting invariant violated");
            }
            jdbc.sql("UPDATE reservations SET status=:status WHERE order_id=:id")
                    .param("status", target)
                    .param("id", orderId)
                    .update();
            jdbc.sql("UPDATE reservation_orders SET status=:status WHERE order_id=:id")
                    .param("status", target)
                    .param("id", orderId)
                    .update();
        } else if (current.get().equals("REJECTED") && target.equals("RELEASED")) {
            jdbc.sql("UPDATE reservation_orders SET status='RELEASED' WHERE order_id=:id")
                    .param("id", orderId)
                    .update();
        } else
            throw error(
                    HttpStatus.CONFLICT,
                    "ILLEGAL_RESERVATION_TRANSITION",
                    "Cannot transition " + current.get() + " to " + target);
        outbox.append("InventoryResult", orderId, correlation, new InventoryResult(target, null));
        return new Reservation(orderId, target);
    }

    @Transactional
    public int signalExpired() {
        var expired =
                jdbc.sql(
                                "SELECT order_id,correlation_id FROM reservation_orders WHERE status='RESERVED' AND"
                                        + " expires_at<now() AND expiry_notified_at IS NULL FOR UPDATE SKIP LOCKED")
                        .query((rs, row) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)))
                        .list();
        for (var entry : expired) {
            // Payment may still authorize: only the orchestrator can safely request release after
            // reconciliation.
            outbox.append(
                    "InventoryExpiring",
                    entry.getKey(),
                    entry.getValue(),
                    new Command("RESERVATION_EXPIRED"));
            jdbc.sql("UPDATE reservation_orders SET expiry_notified_at=now() WHERE order_id=:id")
                    .param("id", entry.getKey())
                    .update();
        }
        return expired.size();
    }

    private void lockOrder(UUID orderId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", orderId.toString())
                .query(Object.class)
                .single();
    }

    private static BusinessException error(HttpStatus status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}

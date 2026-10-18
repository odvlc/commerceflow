package com.commerceflow.order;

import com.commerceflow.contracts.CheckoutEvents.PaymentScenario;
import com.commerceflow.order.OrderModels.CreateOrder;
import com.commerceflow.order.OrderModels.Line;
import com.commerceflow.order.OrderModels.OrderExplanation;
import com.commerceflow.order.OrderModels.OrderPage;
import com.commerceflow.order.OrderModels.OrderView;
import com.commerceflow.order.OrderModels.StatusHistory;
import com.commerceflow.support.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OrderRepository {

    public record Row(
            UUID id,
            UUID customerId,
            String requestHash,
            OrderState status,
            java.math.BigDecimal total,
            String currency,
            PaymentScenario scenario,
            String correlation,
            long version,
            Instant created,
            Instant updated) {

    }

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("request_hash"),
                OrderState.valueOf(rs.getString("status")),
                rs.getBigDecimal("total_amount"),
                rs.getString("currency"),
                PaymentScenario.valueOf(rs.getString("payment_scenario")),
                rs.getString("correlation_id"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public void lockKey(UUID customer, String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", customer + ":" + key)
                .query(Object.class)
                .single();
    }

    public Optional<Row> byKey(UUID customer, String key) {
        return jdbc.sql("SELECT * FROM orders WHERE customer_id=:customer AND idempotency_key=:key")
                .param("customer", customer)
                .param("key", key)
                .query(this::row)
                .optional();
    }

    public Row required(UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM orders WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .query(this::row)
                .optional()
                .orElseThrow(
                        () ->
                                new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found"));
    }

    public void create(
            UUID id,
            UUID customer,
            String key,
            String hash,
            CreateOrder request,
            List<Line> lines,
            java.math.BigDecimal total,
            String currency,
            String correlation,
            Instant deadline) {
        jdbc.sql(
                        """
                                INSERT INTO orders(id,customer_id,idempotency_key,request_hash,status,total_amount,currency,payment_scenario,correlation_id,deadline_at)
                                VALUES (:id,:customer,:key,:hash,'PENDING_INVENTORY',:total,:currency,:scenario,:correlation,:deadline)
                                """)
                .param("id", id)
                .param("customer", customer)
                .param("key", key)
                .param("hash", hash)
                .param("total", total)
                .param("currency", currency)
                .param("scenario", request.paymentScenario().name())
                .param("correlation", correlation)
                .param("deadline", Timestamp.from(deadline))
                .update();
        for (var line : lines)
            jdbc.sql(
                            "INSERT INTO order_items(id,order_id,product_id,quantity,unit_price) VALUES"
                                    + " (:id,:order,:product,:quantity,:price)")
                    .param("id", UUID.randomUUID())
                    .param("order", id)
                    .param("product", line.productId())
                    .param("quantity", line.quantity())
                    .param("price", line.unitPrice())
                    .update();
    }

    public OrderView view(Row order) {
        var items =
                jdbc.sql(
                                "SELECT product_id,quantity,unit_price FROM order_items WHERE order_id=:id ORDER BY"
                                        + " product_id")
                        .param("id", order.id())
                        .query(
                                (rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBigDecimal(3)))
                        .list();
        return new OrderView(
                order.id(),
                order.customerId(),
                order.status(),
                order.total(),
                order.currency(),
                order.version(),
                order.created(),
                order.updated(),
                items);
    }

    public OrderPage list(UUID customer, int page, int size) {
        String where =
                customer == null
                        ? " WHERE :customer IS NULL"
                        : " WHERE customer_id=CAST(:customer AS uuid)";
        // Explicit cast permits a typed null parameter for the administrator query.
        where = customer == null ? " WHERE CAST(:customer AS uuid) IS NULL" : where;
        var total =
                jdbc.sql("SELECT count(*) FROM orders" + where)
                        .param("customer", customer)
                        .query(Long.class)
                        .single();
        var rows =
                jdbc.sql(
                                "SELECT * FROM orders"
                                        + where
                                        + " ORDER BY created_at DESC,id LIMIT :size OFFSET :offset")
                        .param("customer", customer)
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(this::row)
                        .list();
        return new OrderPage(rows.stream().map(this::view).toList(), page, size, total);
    }

    public void move(Row order, OrderState target, Instant deadline) {
        order.status().requireTransition(target);
        int changed =
                jdbc.sql(
                                "UPDATE orders SET"
                                        + " status=:target,deadline_at=:deadline,version=version+1,updated_at=now()"
                                        + " WHERE id=:id AND version=:version")
                        .param("target", target.name())
                        .param("deadline", Timestamp.from(deadline))
                        .param("id", order.id())
                        .param("version", order.version())
                        .update();
        if (changed != 1) throw new IllegalStateException("Order changed without its row lock");
    }

    public void step(UUID order, String step, String status, String error) {
        jdbc.sql(
                        """
                                INSERT INTO order_saga_steps(id,order_id,step,status,last_error) VALUES (:id,:order,:step,:status,:error)
                                ON CONFLICT(order_id,step) DO UPDATE SET status=excluded.status,last_error=excluded.last_error,
                                attempts=order_saga_steps.attempts+CASE WHEN excluded.status='PENDING' THEN 1 ELSE 0 END,updated_at=now()
                                """)
                .param("id", UUID.randomUUID())
                .param("order", order)
                .param("step", step)
                .param("status", status)
                .param("error", error)
                .update();
    }

    public void recordReason(UUID id, String reason) {
        jdbc.sql("UPDATE orders SET public_reason_code=:reason WHERE id=:id AND public_reason_code IS NULL")
                .param("id", id).param("reason", reason).update();
    }

    public OrderExplanation explanation(Row order) {
        String reason = jdbc.sql("SELECT public_reason_code FROM orders WHERE id=:id").param("id", order.id()).query(String.class).optional().orElse(null);
        var history = jdbc.sql("SELECT * FROM order_status_history WHERE order_id=:id ORDER BY version LIMIT 100")
                .param("id", order.id()).query((rs, n) -> new StatusHistory(rs.getLong("version"), OrderState.valueOf(rs.getString("status")), rs.getString("public_reason_code"), rs.getTimestamp("recorded_at").toInstant())).list();
        return new OrderExplanation(order.id(), order.status(), order.version(), reason, reason != null, Instant.now(), history);
    }
}

package com.commerceflow.payment;

import com.commerceflow.contracts.CheckoutEvents.*;
import com.commerceflow.support.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
  public record Attempt(
      int attemptNumber, String result, String failureReason, Instant createdAt) {}

  public record PaymentView(
      UUID id,
      UUID orderId,
      BigDecimal amount,
      String currency,
      String status,
      List<Attempt> attempts) {}

  private final JdbcClient jdbc;
  private final OutboxStore outbox;
  private final Duration delay;
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public PaymentService(
      JdbcClient jdbc,
      OutboxStore outbox,
      @Value("${payment.delay:PT90S}") Duration delay,
      io.micrometer.core.instrument.MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.delay = delay;
    this.metrics = metrics;
  }

  @Transactional
  public PaymentView authorize(UUID orderId, AuthorizePayment request, String correlation) {
    lock(orderId);
    String hash =
        RequestHash.sha256(
            request.amount().stripTrailingZeros().toPlainString()
                + ":"
                + request.currency()
                + ":"
                + request.scenario());
    var existing =
        jdbc.sql("SELECT request_hash FROM payments WHERE order_id=:id")
            .param("id", orderId)
            .query()
            .listOfRows();
    if (!existing.isEmpty()) {
      Object previous = existing.getFirst().get("request_hash");
      if (previous != null && !hash.equals(previous))
        throw new BusinessException(
            HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Payment request contents differ");
      var payment = required(orderId);
      if (!payment.status().equals("PENDING")) emit(orderId, payment.status(), correlation);
      return payment;
    }
    var id = UUID.randomUUID();
    String status =
        switch (request.scenario()) {
          case APPROVE -> "AUTHORIZED";
          case DECLINE -> "DECLINED";
          case TIMEOUT, DELAY -> "PENDING";
        };
    jdbc.sql(
            """
            INSERT INTO payments(id,order_id,request_hash,amount,currency,status,scenario,due_at,correlation_id)
            VALUES (:id,:order,:hash,:amount,:currency,:status,:scenario,:due,:correlation)
            """)
        .param("id", id)
        .param("order", orderId)
        .param("hash", hash)
        .param("amount", request.amount())
        .param("currency", request.currency())
        .param("status", status)
        .param("scenario", request.scenario().name())
        .param(
            "due",
            request.scenario() == PaymentScenario.DELAY
                ? Timestamp.from(Instant.now().plus(delay))
                : null)
        .param("correlation", correlation)
        .update();
    attempt(id, 1, status, status.equals("DECLINED") ? "SIMULATED_DECLINE" : null);
    outcome(status);
    if (!status.equals("PENDING")) emit(orderId, status, correlation);
    return required(orderId);
  }

  @Transactional
  public PaymentView cancel(UUID orderId, String correlation) {
    lock(orderId);
    var current =
        jdbc.sql("SELECT id,status FROM payments WHERE order_id=:id")
            .param("id", orderId)
            .query()
            .listOfRows();
    if (current.isEmpty()) {
      var id = UUID.randomUUID();
      // Cancellation tombstone has no amount: no authorization has been received or attempted.
      jdbc.sql(
              "INSERT INTO payments(id,order_id,status,correlation_id) VALUES"
                  + " (:id,:order,'CANCELLED',:correlation)")
          .param("id", id)
          .param("order", orderId)
          .param("correlation", correlation)
          .update();
    } else {
      var row = current.getFirst();
      var id = (UUID) row.get("id");
      String status = row.get("status").toString();
      if (status.equals("PENDING") || status.equals("AUTHORIZED")) {
        String target = status.equals("AUTHORIZED") ? "REFUNDED" : "CANCELLED";
        jdbc.sql("UPDATE payments SET status=:status,updated_at=now() WHERE id=:id")
            .param("id", id)
            .param("status", target)
            .update();
        attempt(id, 2, target, null);
        outcome(target);
      }
    }
    var payment = required(orderId);
    emit(orderId, payment.status(), correlation);
    return payment;
  }

  @Transactional
  public int settleDue() {
    // Use the same per-order lock order as authorize/cancel; lock neither payment rows nor other
    // aggregates first.
    var candidates =
        jdbc.sql(
                "SELECT order_id FROM payments WHERE status='PENDING' AND scenario='DELAY' AND"
                    + " due_at<=now() ORDER BY order_id LIMIT 100")
            .query(UUID.class)
            .list();
    int settled = 0;
    for (var order : candidates) {
      lock(order);
      var payment = required(order);
      if (!payment.status().equals("PENDING")) continue;
      jdbc.sql(
              "UPDATE payments SET status='AUTHORIZED',updated_at=now() WHERE order_id=:id AND"
                  + " status='PENDING'")
          .param("id", order)
          .update();
      jdbc.sql(
              "UPDATE payment_attempts SET result='AUTHORIZED',updated_at=now() WHERE"
                  + " payment_id=:id AND attempt_number=1")
          .param("id", payment.id())
          .update();
      var correlation =
          jdbc.sql("SELECT correlation_id FROM payments WHERE order_id=:id")
              .param("id", order)
              .query(String.class)
              .single();
      emit(order, "AUTHORIZED", correlation);
      settled++;
      outcome("AUTHORIZED");
    }
    return settled;
  }

  @Transactional(readOnly = true)
  public PaymentView get(UUID order) {
    return required(order);
  }

  private PaymentView required(UUID order) {
    var row =
        jdbc.sql("SELECT id,amount,currency,status FROM payments WHERE order_id=:order")
            .param("order", order)
            .query()
            .listOfRows();
    if (row.isEmpty())
      throw new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found");
    var data = row.getFirst();
    var id = (UUID) data.get("id");
    var attempts =
        jdbc.sql(
                "SELECT attempt_number,result,failure_reason,created_at FROM payment_attempts WHERE"
                    + " payment_id=:id ORDER BY attempt_number")
            .param("id", id)
            .query(
                (rs, n) ->
                    new Attempt(
                        rs.getInt(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getTimestamp(4).toInstant()))
            .list();
    return new PaymentView(
        id,
        order,
        (BigDecimal) data.get("amount"),
        (String) data.get("currency"),
        data.get("status").toString(),
        attempts);
  }

  private void attempt(UUID payment, int number, String result, String reason) {
    jdbc.sql(
            "INSERT INTO payment_attempts(id,payment_id,attempt_number,result,failure_reason)"
                + " VALUES (:id,:payment,:number,:result,:reason)")
        .param("id", UUID.randomUUID())
        .param("payment", payment)
        .param("number", number)
        .param("result", result)
        .param("reason", reason)
        .update();
  }

  private void emit(UUID order, String status, String correlation) {
    outbox.append(
        "PaymentResult",
        order,
        correlation,
        new PaymentResult(status, status.equals("DECLINED") ? "SIMULATED_DECLINE" : null));
  }

  private void lock(UUID order) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key", order.toString())
        .query(Object.class)
        .single();
  }

  private void outcome(String status) {
    BusinessMetrics.afterCommit(
        () -> metrics.counter("commerceflow.payment.outcomes", "result", status).increment());
  }
}

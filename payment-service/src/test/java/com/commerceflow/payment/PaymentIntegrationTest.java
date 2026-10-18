package com.commerceflow.payment;

import com.commerceflow.contracts.CheckoutEvents.AuthorizePayment;
import com.commerceflow.contracts.CheckoutEvents.PaymentScenario;
import com.commerceflow.support.BusinessException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "commerceflow.messaging.enabled=false")
class PaymentIntegrationTest {

    static final EmbeddedPostgres POSTGRES = start();

    static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void stop() throws IOException {
        POSTGRES.close();
    }

    @Autowired
    PaymentService payments;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clear() {
        jdbc.sql("TRUNCATE payments,payment_attempts,outbox_events,processed_events CASCADE").update();
    }

    AuthorizePayment request(PaymentScenario scenario) {
        return new AuthorizePayment(new BigDecimal("25.00"), "USD", scenario);
    }

    @Test
    void twentyConcurrentAuthorizationsAndRepeatedRefundsHaveOneChargeAndRefundAttempt()
            throws Exception {
        var order = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<PaymentService.PaymentView>>();
            for (int i = 0; i < 20; i++)
                tasks.add(() -> payments.authorize(order, request(PaymentScenario.APPROVE), "retry"));
            for (var future : executor.invokeAll(tasks))
                assertThat(future.get().status()).isEqualTo("AUTHORIZED");
        }
        for (int i = 0; i < 5; i++)
            assertThat(payments.cancel(order, "refund").status()).isEqualTo("REFUNDED");
        var payment = payments.get(order);
        assertThat(payment.attempts()).hasSize(2);
        assertThat(payment.attempts())
                .extracting(PaymentService.Attempt::result)
                .containsExactly("AUTHORIZED", "REFUNDED");
        assertThat(payments.authorize(order, request(PaymentScenario.APPROVE), "late").status())
                .isEqualTo("REFUNDED");
        assertThat(payments.get(order).attempts()).hasSize(2);
        assertThatThrownBy(
                () ->
                        payments.authorize(
                                order,
                                new AuthorizePayment(new BigDecimal("26.00"), "USD", PaymentScenario.APPROVE),
                                "conflict"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancelBeforeAuthorizationCreatesTombstoneThatPreventsCharging() {
        var order = UUID.randomUUID();
        payments.cancel(order, "cancel");
        assertThat(payments.authorize(order, request(PaymentScenario.APPROVE), "late").status())
                .isEqualTo("CANCELLED");
        assertThat(payments.get(order).attempts()).isEmpty();
        assertThat(payments.get(order).amount()).isNull();
    }

    @Test
    void cancellationRacingWithDelayedSettlementNeverLeavesAuthorizationUnrefunded()
            throws Exception {
        for (int i = 0; i < 20; i++) {
            var order = UUID.randomUUID();
            payments.authorize(order, request(PaymentScenario.DELAY), "race");
            jdbc.sql("UPDATE payments SET due_at=now()-interval '1 minute' WHERE order_id=:id")
                    .param("id", order)
                    .update();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var gate = new CountDownLatch(1);
                var settle =
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return payments.settleDue();
                                });
                var cancel =
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return payments.cancel(order, "race");
                                });
                gate.countDown();
                settle.get();
                cancel.get();
            }
            assertThat(payments.get(order).status()).isIn("CANCELLED", "REFUNDED");
            assertThat(
                    payments.get(order).attempts().stream()
                            .filter(a -> a.result().equals("AUTHORIZED"))
                            .count())
                    .isLessThanOrEqualTo(1);
        }
    }
}

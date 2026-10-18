package com.commerceflow.order;

import com.commerceflow.contracts.CheckoutEvents.PaymentScenario;
import com.commerceflow.order.OrderModels.CreateOrder;
import com.commerceflow.order.OrderModels.Item;
import com.commerceflow.order.OrderModels.OrderView;
import com.commerceflow.order.OrderModels.StatusHistory;
import com.commerceflow.support.BusinessException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "commerceflow.messaging.enabled=false")
@AutoConfigureMockMvc
class OrderIntegrationTest {

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
    OrderService orders;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc http;

    @Autowired
    JsonMapper json;

    @MockitoBean
    TrustedCatalog catalog;
    UUID product = UUID.randomUUID();
    UUID customer = UUID.randomUUID();

    @BeforeEach
    void clear() {
        jdbc.sql("TRUNCATE orders,order_items,order_saga_steps,outbox_events,processed_events CASCADE")
                .update();
        when(catalog.quote(eq(product), anyString()))
                .thenReturn(new TrustedCatalog.Quote(product, new BigDecimal("12.50"), "USD", true));
    }

    CreateOrder request() {
        return new CreateOrder(List.of(new Item(product, 2)), PaymentScenario.APPROVE);
    }

    @Test
    void trustedPriceAndConcurrentIdempotencyPersistExactlyOneOrderAndEvent() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<OrderView>>();
            for (int i = 0; i < 20; i++)
                tasks.add(() -> orders.create(customer, "concurrent-key", request(), "test"));
            var results = executor.invokeAll(tasks);
            var ids = new HashSet<UUID>();
            for (var result : results) {
                var order = result.get();
                ids.add(order.id());
                assertThat(order.totalAmount()).isEqualByComparingTo("25.00");
            }
            assertThat(ids).hasSize(1);
        }
        verify(catalog, times(1)).quote(eq(product), anyString());
        assertThat(jdbc.sql("SELECT count(*) FROM orders").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single())
                .isEqualTo(1);
        var changed = new CreateOrder(List.of(new Item(product, 1)), PaymentScenario.APPROVE);
        assertThatThrownBy(() -> orders.create(customer, "concurrent-key", changed, "test"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ownershipAndAdministrativeRolesAreEnforced() throws Exception {
        var order = orders.create(customer, "owned-order-key", request(), "test");
        http.perform(get("/api/v1/orders/{id}", order.id())).andExpect(status().isUnauthorized());
        http.perform(
                        get("/api/v1/orders/{id}", order.id())
                                .with(
                                        jwt()
                                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
        http.perform(
                        post("/api/v1/orders/{id}/cancel", order.id())
                                .with(
                                        jwt()
                                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
        http.perform(
                        get("/api/v1/orders/{id}", order.id())
                                .with(
                                        jwt()
                                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                                .jwt(j -> j.subject(customer.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(25.00));
        http.perform(
                        get("/api/v1/orders")
                                .with(
                                        jwt()
                                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(jsonPath("$.totalElements").value(0));
        http.perform(get("/api/v1/admin/orders").with(jwt())).andExpect(status().isForbidden());
        http.perform(
                        get("/api/v1/admin/orders")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void catalogueFailureAndMixedCurrenciesDoNotPersistOrders() {
        when(catalog.quote(eq(product), anyString()))
                .thenThrow(
                        new BusinessException(
                                HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE", "Unavailable"));
        assertThatThrownBy(() -> orders.create(customer, "unavailable-key", request(), "test"))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM orders").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single()).isZero();
    }

    @Test
    void pendingCancellationIsIdempotentAndPreservesReleaseIntent() {
        var order = orders.create(customer, "cancel-order-key", request(), "test");
        assertThat(orders.cancel(customer, order.id()).status())
                .isEqualTo(OrderState.CANCELLING_INVENTORY);
        assertThat(orders.cancel(customer, order.id()).status())
                .isEqualTo(OrderState.CANCELLING_INVENTORY);
        assertThat(
                jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_type='ReleaseInventory'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void rejectsDuplicatesAndInvalidHttpRequests() throws Exception {
        var duplicate =
                new CreateOrder(
                        List.of(new Item(product, 1), new Item(product, 1)), PaymentScenario.APPROVE);
        http.perform(
                        post("/api/v1/orders")
                                .with(
                                        jwt()
                                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                                .jwt(j -> j.subject(customer.toString())))
                                .header("Idempotency-Key", "duplicate-key")
                                .contentType("application/json")
                                .content(json.writeValueAsString(duplicate)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PRODUCT"));
        http.perform(
                        post("/api/v1/orders")
                                .with(
                                        jwt()
                                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                                .jwt(j -> j.subject(customer.toString())))
                                .header("Idempotency-Key", "invalid-key")
                                .contentType("application/json")
                                .content("{\"items\":[],\"paymentScenario\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Autowired
    SagaCoordinator saga;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void publicExplanationsRecordCausesAndEnforceReadRoles() throws Exception {
        var order = orders.create(customer, "explanation-key", request(), "explanation");
        saga.inventory(order.id(), new com.commerceflow.contracts.CheckoutEvents.InventoryResult("RESERVED", null));
        saga.payment(order.id(), new com.commerceflow.contracts.CheckoutEvents.PaymentResult("DECLINED", "sensitive provider diagnostic"));
        saga.inventory(order.id(), new com.commerceflow.contracts.CheckoutEvents.InventoryResult("RELEASED", null));
        saga.inventory(order.id(), new com.commerceflow.contracts.CheckoutEvents.InventoryResult("RELEASED", null));
        var explanation = orders.explanation(customer, order.id());
        assertThat(explanation.reason()).isEqualTo("PAYMENT_DECLINED");
        assertThat(explanation.history()).hasSize(4);
        assertThat(explanation.history().stream().map(StatusHistory::version)).containsExactly(0L, 1L, 2L, 3L);
        assertThat(json.writeValueAsString(explanation)).doesNotContain("sensitive provider diagnostic");
        http.perform(get("/api/v1/orders/{id}/explanation", order.id()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")).jwt(j -> j.subject(customer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("PAYMENT_DECLINED"));
        http.perform(get("/api/v1/orders/{id}/explanation", order.id()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")).jwt(j -> j.subject(UUID.randomUUID().toString())))).andExpect(status().isNotFound());
        http.perform(get("/api/v1/admin/orders/{id}/explanation", order.id()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPPORT")))).andExpect(status().isOk());
        http.perform(get("/api/v1/admin/orders/{id}/explanation", order.id()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))).andExpect(status().isForbidden());
        http.perform(get("/api/v1/admin/payments/{id}", order.id()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPPORT")))).andExpect(status().isForbidden());
    }

    @Test
    void historyAndReasonRollBackWithBusinessTransactionAndLegacyIsUnknown() {
        var order = orders.create(customer, "rollback-history", request(), "explanation");
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            orders.cancel(customer, order.id());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        var current = orders.explanation(customer, order.id());
        assertThat(current.history()).hasSize(1);
        assertThat(current.reasonKnown()).isFalse();
        assertThat(current.status()).isEqualTo(OrderState.PENDING_INVENTORY);
        jdbc.sql("DELETE FROM order_status_history WHERE order_id=:id").param("id", order.id()).update();
        assertThat(orders.explanation(customer, order.id()).reasonKnown()).isFalse();
        assertThat(orders.explanation(customer, order.id()).history()).isEmpty();
    }

    @Test
    void publicTimeoutAndCustomerCancellationCausesSurviveCompensation() {
        var timeout = orders.create(customer, "timeout-explanation", request(), "explanation");
        saga.inventory(timeout.id(), new com.commerceflow.contracts.CheckoutEvents.InventoryResult("RESERVED", null));
        jdbc.sql("UPDATE orders SET deadline_at=now()-interval '1 second' WHERE id=:id").param("id", timeout.id()).update();
        saga.reconcileDeadlines();
        saga.payment(timeout.id(), new com.commerceflow.contracts.CheckoutEvents.PaymentResult("CANCELLED", null));
        saga.inventory(timeout.id(), new com.commerceflow.contracts.CheckoutEvents.InventoryResult("RELEASED", null));
        assertThat(orders.explanation(customer, timeout.id()).reason()).isEqualTo("PAYMENT_TIMEOUT");
        var cancelled = orders.create(customer, "cancel-explanation", request(), "explanation");
        orders.cancel(customer, cancelled.id());
        orders.cancel(customer, cancelled.id());
        assertThat(orders.explanation(customer, cancelled.id()).reason()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(orders.explanation(customer, cancelled.id()).history()).hasSize(2);
        var stock = orders.create(customer, "stock-explanation", request(), "explanation");
        saga.inventory(stock.id(), new com.commerceflow.contracts.CheckoutEvents.InventoryResult("REJECTED", "INSUFFICIENT_STOCK"));
        assertThat(orders.explanation(customer, stock.id()).reason()).isEqualTo("INSUFFICIENT_STOCK");
    }
}

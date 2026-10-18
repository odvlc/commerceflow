package com.commerceflow.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.commerceflow.contracts.CheckoutEvents.*;
import com.commerceflow.support.BusinessException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "commerceflow.messaging.enabled=false")
@AutoConfigureMockMvc
class InventoryIntegrationTest {

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
    InventoryService inventory;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc http;

    @BeforeEach
    void clear() {
        jdbc.sql(
                        "TRUNCATE inventory,reservation_orders,reservations,outbox_events,processed_events"
                                + " CASCADE")
                .update();
    }

    ReserveInventory request(UUID product, int quantity) {
        return new ReserveInventory(List.of(new StockItem(product, quantity)));
    }

    @Test
    void oneHundredConcurrentRequestsReserveExactlyTenWithoutOverselling() throws Exception {
        var product = UUID.randomUUID();
        inventory.setStock(product, 10, null);
        var gate = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<InventoryService.Reservation>>();
        for (int i = 0; i < 100; i++)
            tasks.add(
                    () -> {
                        gate.await();
                        return inventory.reserve(UUID.randomUUID(), request(product, 1), "concurrency");
                    });
        List<InventoryService.Reservation> reservations = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = tasks.stream().map(executor::submit).toList();
            gate.countDown();
            for (var future : futures) reservations.add(future.get(15, TimeUnit.SECONDS));
        }
        assertThat(reservations).filteredOn(r -> r.status().equals("RESERVED")).hasSize(10);
        assertThat(reservations).filteredOn(r -> r.status().equals("REJECTED")).hasSize(90);
        var stock = inventory.stock(product);
        assertThat(stock.availableQuantity()).isZero();
        assertThat(stock.reservedQuantity()).isEqualTo(10);
        assertThat(
                jdbc.sql("SELECT min(available_quantity) FROM inventory").query(Integer.class).single())
                .isZero();
    }

    @Test
    void replayAndReleaseAreIdempotentAndCommitCannotBeReleased() {
        var product = UUID.randomUUID();
        var order = UUID.randomUUID();
        inventory.setStock(product, 10, null);
        for (int i = 0; i < 5; i++) inventory.reserve(order, request(product, 3), "test");
        assertThat(inventory.stock(product).reservedQuantity()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single())
                .isEqualTo(1);
        assertThatThrownBy(() -> inventory.reserve(order, request(product, 4), "test"))
                .isInstanceOf(BusinessException.class);
        inventory.release(order, "test");
        inventory.release(order, "test");
        assertThat(inventory.stock(product).availableQuantity()).isEqualTo(10);
        assertThat(inventory.stock(product).reservedQuantity()).isZero();
        var second = UUID.randomUUID();
        inventory.reserve(second, request(product, 2), "test");
        inventory.commit(second, "test");
        inventory.commit(second, "test");
        assertThatThrownBy(() -> inventory.release(second, "test"))
                .isInstanceOf(BusinessException.class);
        assertThat(inventory.stock(product).availableQuantity()).isEqualTo(8);
        assertThat(inventory.stock(product).reservedQuantity()).isZero();
    }

    @Test
    void multiProductRejectionAndLateReserveNeverTakeStock() {
        var product = UUID.randomUUID();
        inventory.setStock(product, 5, null);
        var request =
                new ReserveInventory(
                        List.of(new StockItem(product, 2), new StockItem(UUID.randomUUID(), 1)));
        assertThat(inventory.reserve(UUID.randomUUID(), request, "test").status())
                .isEqualTo("REJECTED");
        assertThat(inventory.stock(product).availableQuantity()).isEqualTo(5);
        var cancelled = UUID.randomUUID();
        inventory.release(cancelled, "test");
        assertThat(inventory.reserve(cancelled, request(product, 2), "test").status())
                .isEqualTo("RELEASED");
        assertThat(inventory.stock(product).availableQuantity()).isEqualTo(5);
    }

    @Test
    void expirySignalsReconciliationWithoutReleasingUncertainPayments() {
        var product = UUID.randomUUID();
        var order = UUID.randomUUID();
        inventory.setStock(product, 2, null);
        inventory.reserve(order, request(product, 2), "test");
        jdbc.sql("UPDATE reservation_orders SET expires_at=now()-interval '1 minute'").update();
        assertThat(inventory.signalExpired()).isEqualTo(1);
        assertThat(inventory.signalExpired()).isZero();
        assertThat(inventory.stock(product).reservedQuantity()).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_type='InventoryExpiring'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void administrationRequiresRoleAndInternalMutationsRequireServiceScope() throws Exception {
        var product = UUID.randomUUID();
        inventory.setStock(product, 2, null);
        http.perform(get("/api/v1/admin/inventory/{id}", product)).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/admin/inventory/{id}", product).with(jwt()))
                .andExpect(status().isForbidden());
        http.perform(
                        get("/api/v1/admin/inventory/{id}", product)
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(2));
        http.perform(post("/internal/v1/reservations/{id}/release", UUID.randomUUID()).with(jwt()))
                .andExpect(status().isForbidden());
        http.perform(
                        post("/internal/v1/reservations/{id}/release", UUID.randomUUID())
                                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_commerceflow.internal"))))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentMixedProductReservationsAndReleasesConserveStock() throws Exception {
        var low = UUID.fromString("00000000-0000-4000-8000-000000000001");
        var high = UUID.fromString("ff000000-0000-4000-8000-000000000001");
        inventory.setStock(low, 100, null);
        inventory.setStock(high, 100, null);
        var request = new ReserveInventory(List.of(new StockItem(high, 1), new StockItem(low, 1)));
        var existing = new ArrayList<UUID>();
        var incoming = new ArrayList<UUID>();
        for (int i = 0; i < 20; i++) {
            var id = UUID.randomUUID();
            existing.add(id);
            incoming.add(UUID.randomUUID());
            inventory.reserve(id, request, "mixed-locks");
        }
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) {
                var release = existing.get(i);
                var reserve = incoming.get(i);
                futures.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return inventory.release(release, "mixed-locks");
                                }));
                futures.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return inventory.reserve(reserve, request, "mixed-locks");
                                }));
            }
            gate.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        for (var id : List.of(low, high)) {
            assertThat(inventory.stock(id).availableQuantity()).isEqualTo(80);
            assertThat(inventory.stock(id).reservedQuantity()).isEqualTo(20);
        }
        for (var id : incoming) inventory.release(id, "mixed-locks");
        for (var id : List.of(low, high)) {
            assertThat(inventory.stock(id).availableQuantity()).isEqualTo(100);
            assertThat(inventory.stock(id).reservedQuantity()).isZero();
        }
    }
}

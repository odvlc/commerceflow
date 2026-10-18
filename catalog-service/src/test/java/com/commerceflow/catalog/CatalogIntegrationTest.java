package com.commerceflow.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.commerceflow.catalog.api.ProductInput;
import com.commerceflow.catalog.api.ProductView;
import com.commerceflow.catalog.application.CatalogService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        properties = {
                "commerceflow.messaging.enabled=false",
                "catalog.cache.enabled=false",
                "catalog.search.enabled=false"
        })
@AutoConfigureMockMvc
class CatalogIntegrationTest {

    static final EmbeddedPostgres POSTGRES = startPostgres();

    static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Autowired
    MockMvc http;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CatalogService catalog;

    @BeforeEach
    void clear() {
        jdbc.sql("TRUNCATE products, categories, outbox_events CASCADE").update();
    }

    ProductInput input(String sku) {
        return new ProductInput(
                sku,
                "Mechanical keyboard",
                "Quiet switches",
                "Accessories",
                new BigDecimal("129.99"),
                "USD",
                true);
    }

    @Test
    void createsListsReadsAndGeneratesOpenApi() throws Exception {
        var response =
                http.perform(
                                post("/api/v1/admin/products")
                                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                        .header("Idempotency-Key", "create-001")
                                        .header("X-Correlation-ID", "test-catalog")
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(input("KEY-1"))))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("X-Correlation-ID", "test-catalog"))
                        .andExpect(header().string("ETag", "\"0\""))
                        .andReturn();
        var product = json.readValue(response.getResponse().getContentAsString(), ProductView.class);
        http.perform(get("/api/v1/products/{id}", product.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(129.99));
        http.perform(get("/api/v1/products").param("category", "Accessories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(product.id().toString()));
        http.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/products']").exists());
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single())
                .isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT payload->>'correlationId' FROM outbox_events")
                        .query(String.class)
                        .single())
                .isEqualTo("test-catalog");
    }

    @Test
    void retriesAreAtomicAndRejectDifferentPayloads() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<ProductView>>();
            for (int i = 0; i < 20; i++)
                tasks.add(() -> catalog.create("concurrent-key", input("KEY-1"), "retry-test"));
            var results = executor.invokeAll(tasks);
            var ids = new HashSet<UUID>();
            for (var result : results) ids.add(result.get().id());
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM products").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single())
                .isEqualTo(1);
        http.perform(
                        post("/api/v1/admin/products")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("Idempotency-Key", "concurrent-key")
                                .contentType("application/json")
                                .content(json.writeValueAsString(input("OTHER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void conflictingWriteRollsBackCategoryAndOutbox() throws Exception {
        catalog.create("original-key", input("KEY-1"), "test");
        var duplicate =
                new ProductInput(
                        "KEY-1", "Duplicate", "", "New category", new BigDecimal("1.00"), "USD", true);
        http.perform(
                        post("/api/v1/admin/products")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("Idempotency-Key", "duplicate-key")
                                .contentType("application/json")
                                .content(json.writeValueAsString(duplicate)))
                .andExpect(status().isConflict());
        assertThat(
                jdbc.sql("SELECT count(*) FROM categories WHERE name = 'New category'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void updateRejectsStaleVersionAndHidesDeactivatedProducts() throws Exception {
        var product = catalog.create("original-key", input("KEY-1"), "test");
        var inactive =
                new ProductInput(
                        "KEY-1", "Updated", "", "Accessories", new BigDecimal("99.00"), "USD", false);
        http.perform(
                        put("/api/v1/admin/products/{id}", product.id())
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("If-Match", "\"0\"")
                                .contentType("application/json")
                                .content(json.writeValueAsString(inactive)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));
        http.perform(
                        put("/api/v1/admin/products/{id}", product.id())
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("If-Match", "\"0\"")
                                .contentType("application/json")
                                .content(json.writeValueAsString(inactive)))
                .andExpect(status().isConflict());
        http.perform(get("/api/v1/products/{id}", product.id())).andExpect(status().isNotFound());
        http.perform(get("/api/v1/products")).andExpect(jsonPath("$.totalElements").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void rejectsInvalidInputAndBoundsPagination() throws Exception {
        http.perform(
                        post("/api/v1/admin/products")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("Idempotency-Key", "bad-data-key")
                                .contentType("application/json")
                                .content("{\"sku\":\"\",\"price\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        http.perform(get("/api/v1/products").param("size", "101")).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/products").param("page", "-1")).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/products/not-a-uuid")).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM products").query(Long.class).single()).isZero();
    }
}

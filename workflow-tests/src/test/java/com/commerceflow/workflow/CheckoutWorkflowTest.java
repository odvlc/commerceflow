package com.commerceflow.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.commerceflow.cart.CartApplication;
import com.commerceflow.cart.CartService;
import com.commerceflow.catalog.CatalogApplication;
import com.commerceflow.catalog.api.ProductView;
import com.commerceflow.catalog.application.ProductCache;
import com.commerceflow.catalog.search.ProductSearch;
import com.commerceflow.contracts.*;
import com.commerceflow.contracts.CheckoutEvents.PaymentResult;
import com.commerceflow.inventory.InventoryApplication;
import com.commerceflow.inventory.InventoryService.Stock;
import com.commerceflow.notification.NotificationApplication;
import com.commerceflow.notification.NotificationService;
import com.commerceflow.order.*;
import com.commerceflow.order.OrderModels.OrderView;
import com.commerceflow.payment.*;
import com.commerceflow.payment.PaymentService.PaymentView;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import tools.jackson.databind.json.JsonMapper;

/**
 * Four real HTTP applications, four separately owned databases, real Kafka, and signed JWT
 * validation. The local JWKS test fixture is not a Keycloak login or realm integration test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CheckoutWorkflowTest {
  static EmbeddedPostgres postgres;
  static EmbeddedKafkaKraftBroker broker;
  static HttpServer jwks;
  static RSAKey key;
  static String issuer;
  static ConfigurableApplicationContext catalog, inventory, payment, order, cart, notifications;
  static RedisTestServer redis;
  static ElasticsearchTestServer elasticsearch;
  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  static final UUID CUSTOMER = UUID.randomUUID();

  @BeforeAll
  static void start() throws Exception {
    redis = RedisTestServer.start();
    elasticsearch = ElasticsearchTestServer.start();
    postgres = EmbeddedPostgres.builder().setPort(0).start();
    try (var connection = postgres.getPostgresDatabase().getConnection();
        var sql = connection.createStatement()) {
      for (var service : List.of("catalog", "inventory", "order", "payment", "notification")) {
        sql.execute("CREATE ROLE cf_" + service + " LOGIN PASSWORD 'test-only-" + service + "'");
        sql.execute("CREATE DATABASE cf_" + service + " OWNER cf_" + service);
        sql.execute("REVOKE ALL ON DATABASE cf_" + service + " FROM PUBLIC");
      }
    }
    broker =
        new EmbeddedKafkaKraftBroker(
            1,
            3,
            Topics.ORDER,
            Topics.INVENTORY,
            Topics.PAYMENT,
            Topics.CATALOG,
            Topics.NOTIFICATION,
            Topics.DEAD_LETTER);
    broker.afterPropertiesSet();
    key = new RSAKeyGenerator(2048).keyID("workflow-test").generate();
    jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwks.createContext(
        "/jwks",
        exchange -> {
          byte[] body =
              ("{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    jwks.start();
    issuer = "http://127.0.0.1:" + jwks.getAddress().getPort() + "/realms/workflow-test";
    catalog = startApp(CatalogApplication.class, "catalog");
    inventory = startApp(InventoryApplication.class, "inventory");
    payment = startApp(PaymentApplication.class, "payment", "--payment.delay=PT8S");
    order = startOrder();
    cart = startApp(CartApplication.class, "cart", "--cart.ttl=PT60S");
    notifications = startApp(NotificationApplication.class, "notification");
  }

  static ConfigurableApplicationContext startOrder() {
    return startApp(OrderApplication.class, "order", "--catalog.base-url=" + base(catalog));
  }

  static ConfigurableApplicationContext startApp(
      Class<?> application, String service, String... extra) {
    var args =
        new ArrayList<>(
            List.of(
                "--spring.config.name=workflow-test",
                "--spring.application.name=" + service + "-service",
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.threads.virtual.enabled=true",
                "--spring.datasource.url=" + postgres.getJdbcUrl("cf_" + service, "cf_" + service),
                "--spring.datasource.username=cf_" + service,
                "--spring.datasource.password=test-only-" + service,
                "--spring.datasource.hikari.maximum-pool-size=10",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.open-in-view=false",
                "--spring.flyway.locations=classpath:db/migration/" + service,
                "--security.issuer=" + issuer,
                "--security.jwk-set-uri=http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks",
                "--commerceflow.messaging.bootstrap-servers=" + broker.getBrokersAsString(),
                "--commerceflow.outbox.poll-delay=50",
                "--saga.reconcile-delay=200",
                "--saga.step-timeout=PT5S",
                "--payment.settle-delay=100",
                "--inventory.expiry-check-delay=1000",
                "--spring.data.redis.host=" + redis.host(),
                "--spring.data.redis.port=" + redis.port(),
                "--spring.data.redis.connect-timeout=500ms",
                "--spring.data.redis.timeout=500ms",
                "--spring.elasticsearch.uris=" + elasticsearch.url(),
                "--spring.elasticsearch.connection-timeout=1s",
                "--spring.elasticsearch.socket-timeout=2s",
                "--logging.level.root=WARN"));
    args.addAll(List.of(extra));
    return new SpringApplicationBuilder(application).run(args.toArray(String[]::new));
  }

  static String base(ConfigurableApplicationContext app) {
    return "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
  }

  @AfterAll
  static void stop() throws Exception {
    for (var app : Arrays.asList(notifications, cart, order, payment, inventory, catalog))
      if (app != null) app.close();
    if (jwks != null) jwks.stop(0);
    if (broker != null) broker.destroy();
    if (postgres != null) postgres.close();
    if (redis != null) redis.close();
    if (elasticsearch != null) elasticsearch.close();
  }

  static String token(UUID subject, String role) throws Exception {
    return token(subject, role, issuer, "commerceflow-api");
  }

  static String token(UUID subject, String role, String tokenIssuer, String audience)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .subject(subject.toString())
            .issuer(tokenIssuer)
            .audience(audience)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(600)))
            .claim("realm_access", Map.of("roles", List.of(role)))
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  static HttpResponse<String> call(
      ConfigurableApplicationContext app,
      String method,
      String path,
      String auth,
      Object body,
      Map<String, String> headers)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base(app) + path)).timeout(Duration.ofSeconds(10));
    if (auth != null) request.header("Authorization", "Bearer " + auth);
    headers.forEach(request::header);
    request.header("Content-Type", "application/json");
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  static <T> T expect(HttpResponse<String> response, int status, Class<T> type) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    return JSON.readValue(response.body(), type);
  }

  static ProductView product(int stock) throws Exception {
    var input =
        Map.of(
            "sku",
            "SKU-" + UUID.randomUUID(),
            "name",
            "Workflow keyboard",
            "description",
            "Real integration product",
            "category",
            "Accessories",
            "price",
            new BigDecimal("19.95"),
            "currency",
            "USD",
            "active",
            true);
    var product =
        expect(
            call(
                catalog,
                "POST",
                "/api/v1/admin/products",
                token(CUSTOMER, "ADMIN"),
                input,
                Map.of("Idempotency-Key", UUID.randomUUID().toString())),
            201,
            ProductView.class);
    expect(
        call(
            inventory,
            "PUT",
            "/api/v1/admin/inventory/" + product.id(),
            token(CUSTOMER, "ADMIN"),
            Map.of("availableQuantity", stock),
            Map.of()),
        200,
        Stock.class);
    return product;
  }

  static Map<String, Object> checkout(UUID product, int quantity, String scenario) {
    return Map.of(
        "items",
        List.of(Map.of("productId", product, "quantity", quantity)),
        "paymentScenario",
        scenario);
  }

  static OrderView create(ProductView product, int quantity, String scenario, String idempotency)
      throws Exception {
    return expect(
        call(
            order,
            "POST",
            "/api/v1/orders",
            token(CUSTOMER, "CUSTOMER"),
            checkout(product.id(), quantity, scenario),
            Map.of("Idempotency-Key", idempotency, "X-Correlation-ID", "workflow-test")),
        201,
        OrderView.class);
  }

  static OrderView getOrder(UUID id) throws Exception {
    return expect(
        call(order, "GET", "/api/v1/orders/" + id, token(CUSTOMER, "CUSTOMER"), null, Map.of()),
        200,
        OrderView.class);
  }

  static void terminal(UUID id, OrderState expected) {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(getOrder(id).status()).isEqualTo(expected));
  }

  static Stock stock(UUID product) throws Exception {
    return expect(
        call(
            inventory,
            "GET",
            "/api/v1/admin/inventory/" + product,
            token(CUSTOMER, "ADMIN"),
            null,
            Map.of()),
        200,
        Stock.class);
  }

  static PaymentView payment(UUID id) throws Exception {
    return expect(
        call(
            payment,
            "GET",
            "/api/v1/admin/payments/" + id,
            token(CUSTOMER, "ADMIN"),
            null,
            Map.of()),
        200,
        PaymentView.class);
  }

  @Test
  @org.junit.jupiter.api.Order(1)
  void successfulPurchaseUsesActualCatalogHttpKafkaAndSeparateDatabases() throws Exception {
    var product = product(5);
    String key = UUID.randomUUID().toString();
    var placed = create(product, 2, "APPROVE", key);
    terminal(placed.id(), OrderState.CONFIRMED);
    assertThat(getOrder(placed.id()).totalAmount()).isEqualByComparingTo("39.90");
    assertThat(stock(product.id()).availableQuantity()).isEqualTo(3);
    assertThat(stock(product.id()).reservedQuantity()).isZero();
    assertThat(payment(placed.id()).status()).isEqualTo("AUTHORIZED");
    assertThat(payment(placed.id()).attempts()).hasSize(1);
    assertThat(
            order
                .getBean(JdbcClient.class)
                .sql("SELECT max(attempts) FROM order_saga_steps WHERE order_id=:id")
                .param("id", placed.id())
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(create(product, 2, "APPROVE", key).id()).isEqualTo(placed.id());
  }

  @Test
  @org.junit.jupiter.api.Order(2)
  void declinedPaymentCompensatesInventory() throws Exception {
    var product = product(5);
    var placed = create(product, 2, "DECLINE", UUID.randomUUID().toString());
    terminal(placed.id(), OrderState.CANCELLED);
    assertThat(stock(product.id()).availableQuantity()).isEqualTo(5);
    assertThat(stock(product.id()).reservedQuantity()).isZero();
    assertThat(payment(placed.id()).status()).isEqualTo("DECLINED");
  }

  @Test
  @org.junit.jupiter.api.Order(3)
  void insufficientStockCancelsWithoutPayment() throws Exception {
    var product = product(1);
    var placed = create(product, 2, "APPROVE", UUID.randomUUID().toString());
    terminal(placed.id(), OrderState.CANCELLED);
    assertThat(stock(product.id()).availableQuantity()).isEqualTo(1);
    assertThat(
            call(
                    payment,
                    "GET",
                    "/api/v1/admin/payments/" + placed.id(),
                    token(CUSTOMER, "ADMIN"),
                    null,
                    Map.of())
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  @org.junit.jupiter.api.Order(4)
  void timeoutReconcilesPaymentBeforeReleasingStockAndSurvivesOrderRestart() throws Exception {
    var product = product(5);
    String key = UUID.randomUUID().toString();
    var placed = create(product, 2, "TIMEOUT", key);
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(getOrder(placed.id()).status()).isEqualTo(OrderState.AWAITING_PAYMENT));
    order.close();
    order = startOrder();
    terminal(placed.id(), OrderState.CANCELLED);
    assertThat(payment(placed.id()).status()).isEqualTo("CANCELLED");
    assertThat(stock(product.id()).availableQuantity()).isEqualTo(5);
    assertThat(stock(product.id()).reservedQuantity()).isZero();
    assertThat(create(product, 2, "TIMEOUT", key).id()).isEqualTo(placed.id());
  }

  @Test
  @org.junit.jupiter.api.Order(5)
  void delayedAuthorizationCannotResurrectCancelledOrderOrDoubleCharge() throws Exception {
    var product = product(5);
    var placed = create(product, 2, "DELAY", UUID.randomUUID().toString());
    terminal(placed.id(), OrderState.CANCELLED);
    var paymentJdbc = payment.getBean(JdbcClient.class);
    paymentJdbc
        .sql("UPDATE payments SET due_at=now()-interval '1 minute' WHERE order_id=:id")
        .param("id", placed.id())
        .update();
    assertThat(payment.getBean(PaymentService.class).settleDue()).isZero();
    var stale =
        EventEnvelope.create(
            "PaymentResult", placed.id(), "late-result", new PaymentResult("AUTHORIZED", null));
    @SuppressWarnings("unchecked")
    var kafka = (KafkaTemplate<String, String>) order.getBean(KafkaTemplate.class);
    kafka.send(Topics.PAYMENT, placed.id().toString(), JSON.writeValueAsString(stale)).get();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        order
                            .getBean(JdbcClient.class)
                            .sql("SELECT count(*) FROM processed_events WHERE event_id=:id")
                            .param("id", stale.eventId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1));
    assertThat(getOrder(placed.id()).status()).isEqualTo(OrderState.CANCELLED);
    assertThat(payment(placed.id()).status()).isEqualTo("CANCELLED");
    assertThat(payment(placed.id()).attempts()).noneMatch(a -> a.result().equals("AUTHORIZED"));
    assertThat(stock(product.id()).availableQuantity()).isEqualTo(5);
  }

  @Test
  @org.junit.jupiter.api.Order(6)
  void validDelayedPaymentCanCompleteAndReplayedCommandsDoNotChargeTwice() throws Exception {
    var product = product(5);
    var placed = create(product, 2, "DELAY", UUID.randomUUID().toString());
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(payment(placed.id()).status()).isEqualTo("PENDING"));
    payment
        .getBean(JdbcClient.class)
        .sql("UPDATE payments SET due_at=now()-interval '1 minute' WHERE order_id=:id")
        .param("id", placed.id())
        .update();
    payment.getBean(PaymentService.class).settleDue();
    terminal(placed.id(), OrderState.CONFIRMED);
    var replay =
        EventEnvelope.create(
            "AuthorizePayment",
            placed.id(),
            "payment-retry",
            new com.commerceflow.contracts.CheckoutEvents.AuthorizePayment(
                new BigDecimal("39.90"),
                "USD",
                com.commerceflow.contracts.CheckoutEvents.PaymentScenario.DELAY));
    @SuppressWarnings("unchecked")
    var kafka = (KafkaTemplate<String, String>) order.getBean(KafkaTemplate.class);
    kafka.send(Topics.ORDER, placed.id().toString(), JSON.writeValueAsString(replay)).get();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        payment
                            .getBean(JdbcClient.class)
                            .sql("SELECT count(*) FROM processed_events WHERE event_id=:id")
                            .param("id", replay.eventId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1));
    assertThat(payment(placed.id()).attempts()).hasSize(1);
    assertThat(payment(placed.id()).status()).isEqualTo("AUTHORIZED");
    assertThat(stock(product.id()).availableQuantity()).isEqualTo(3);
  }

  @Test
  @org.junit.jupiter.api.Order(7)
  void realJwtValidationRejectsWrongIssuerAudienceRoleAndCustomer() throws Exception {
    var product = product(2);
    var placed = create(product, 1, "APPROVE", UUID.randomUUID().toString());
    assertThat(
            call(order, "GET", "/api/v1/orders/" + placed.id(), null, null, Map.of()).statusCode())
        .isEqualTo(401);
    assertThat(
            call(
                    order,
                    "GET",
                    "/api/v1/orders/" + placed.id(),
                    token(CUSTOMER, "CUSTOMER", "https://wrong.example", "commerceflow-api"),
                    null,
                    Map.of())
                .statusCode())
        .isEqualTo(401);
    assertThat(
            call(
                    order,
                    "GET",
                    "/api/v1/orders/" + placed.id(),
                    token(CUSTOMER, "CUSTOMER", issuer, "wrong-audience"),
                    null,
                    Map.of())
                .statusCode())
        .isEqualTo(401);
    assertThat(
            call(
                    order,
                    "GET",
                    "/api/v1/orders/" + placed.id(),
                    token(UUID.randomUUID(), "CUSTOMER"),
                    null,
                    Map.of())
                .statusCode())
        .isEqualTo(404);
    assertThat(
            call(
                    catalog,
                    "POST",
                    "/api/v1/admin/products",
                    token(CUSTOMER, "CUSTOMER"),
                    Map.of(),
                    Map.of())
                .statusCode())
        .isEqualTo(403);
    terminal(placed.id(), OrderState.CONFIRMED);
  }

  @Test
  @org.junit.jupiter.api.Order(8)
  void redisCartIsAtomicScopedIdempotentAndConvertedToTrustedCheckout() throws Exception {
    var product = product(200);
    var carts = cart.getBean(CartService.class);
    var customer = UUID.randomUUID();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<java.util.concurrent.Callable<CartService.CartView>>();
      for (int i = 0; i < 100; i++) {
        String key = "add-item-" + i;
        tasks.add(() -> carts.mutate(customer, "ADD", product.id(), 1, key));
      }
      for (var future : executor.invokeAll(tasks)) future.get();
    }
    assertThat(carts.get(customer).items().getFirst().quantity()).isEqualTo(100);
    var snapshot = carts.mutate(customer, "ADD", product.id(), 1, "dedup-cart-key");
    assertThat(carts.mutate(customer, "ADD", product.id(), 1, "dedup-cart-key").version())
        .isEqualTo(snapshot.version());
    assertThat(carts.get(customer).items().getFirst().quantity()).isEqualTo(101);
    assertThat(carts.get(UUID.randomUUID()).items()).isEmpty();
    assertThat(carts.get(customer).ttlSeconds()).isBetween(1L, 60L);
    var response =
        call(
            cart,
            "POST",
            "/api/v1/cart/items",
            token(CUSTOMER, "CUSTOMER"),
            Map.of("productId", product.id(), "quantity", 2),
            Map.of("Idempotency-Key", "http-cart-add"));
    var basket = expect(response, 200, CartService.CartView.class);
    var checkout = Map.of("items", basket.items(), "paymentScenario", "APPROVE");
    var placed =
        expect(
            call(
                order,
                "POST",
                "/api/v1/orders",
                token(CUSTOMER, "CUSTOMER"),
                checkout,
                Map.of("Idempotency-Key", "from-redis-cart")),
            201,
            OrderView.class);
    terminal(placed.id(), OrderState.CONFIRMED);
    assertThat(placed.totalAmount()).isEqualByComparingTo("39.90");
    expect(
        call(
            cart,
            "PATCH",
            "/api/v1/cart/items/" + product.id(),
            token(CUSTOMER, "CUSTOMER"),
            Map.of("quantity", 3),
            Map.of("Idempotency-Key", "http-cart-set")),
        200,
        CartService.CartView.class);
    var deleted =
        expect(
            call(
                cart,
                "DELETE",
                "/api/v1/cart/items/" + product.id(),
                token(CUSTOMER, "CUSTOMER"),
                null,
                Map.of("Idempotency-Key", "http-cart-del")),
            200,
            CartService.CartView.class);
    assertThat(deleted.items()).isEmpty();
    assertThat(call(cart, "GET", "/api/v1/cart", null, null, Map.of()).statusCode()).isEqualTo(401);
    var template = cart.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
    template.expire("cf:cart:{" + customer + "}:items", Duration.ofMillis(100));
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> assertThat(carts.get(customer).items()).isEmpty());
  }

  @Test
  @org.junit.jupiter.api.Order(9)
  void catalogCacheHitsInvalidatesAndRejectsStaleFills() throws Exception {
    var product = product(3);
    var cache = catalog.getBean(ProductCache.class);
    cache.invalidate(product.id(), product.version());
    var first =
        expect(
            call(catalog, "GET", "/api/v1/products/" + product.id(), null, null, Map.of()),
            200,
            ProductView.class);
    expect(
        call(catalog, "GET", "/api/v1/products/" + product.id(), null, null, Map.of()),
        200,
        ProductView.class);
    var registry = catalog.getBean(io.micrometer.core.instrument.MeterRegistry.class);
    assertThat(registry.get("commerceflow.cache.hits").counter().count()).isGreaterThan(0);
    var updated =
        expect(
            call(
                catalog,
                "PUT",
                "/api/v1/admin/products/" + product.id(),
                token(CUSTOMER, "ADMIN"),
                Map.of(
                    "sku",
                    product.sku(),
                    "name",
                    "Updated product",
                    "description",
                    "Changed",
                    "category",
                    "Accessories",
                    "price",
                    new BigDecimal("29.95"),
                    "currency",
                    "USD",
                    "active",
                    true),
                Map.of("If-Match", "\"0\"")),
            200,
            ProductView.class);
    cache.put(first);
    assertThat(cache.get(product.id())).isEmpty();
    var current =
        expect(
            call(catalog, "GET", "/api/v1/products/" + product.id(), null, null, Map.of()),
            200,
            ProductView.class);
    assertThat(current.price()).isEqualByComparingTo("29.95");
    assertThat(current.version()).isEqualTo(updated.version());
    cache.put(first);
    assertThat(cache.get(product.id()).orElseThrow().price()).isEqualByComparingTo("29.95");
  }

  @Test
  @org.junit.jupiter.api.Order(11)
  void redisOutageFailsCartClearlyWhileCatalogAndAuthoritativeCheckoutContinue() throws Exception {
    var product = product(3);
    redis.close();
    elasticsearch.close();
    assertThat(
            call(catalog, "GET", "/api/v1/products/search?q=keyboard", null, null, Map.of())
                .statusCode())
        .isEqualTo(503);
    expect(
        call(catalog, "GET", "/api/v1/products/" + product.id(), null, null, Map.of()),
        200,
        ProductView.class);
    var failed = call(cart, "GET", "/api/v1/cart", token(CUSTOMER, "CUSTOMER"), null, Map.of());
    assertThat(failed.statusCode()).isEqualTo(503);
    assertThat(failed.body()).contains("CART_UNAVAILABLE");
    var placed = create(product, 1, "APPROVE", UUID.randomUUID().toString());
    terminal(placed.id(), OrderState.CONFIRMED);
    assertThat(stock(product.id()).availableQuantity()).isEqualTo(2);
    assertThat(
            catalog
                .getBean(io.micrometer.core.instrument.MeterRegistry.class)
                .get("commerceflow.cache.failures")
                .counter()
                .count())
        .isGreaterThan(0);
  }

  @Test
  @org.junit.jupiter.api.Order(10)
  void elasticsearchIndexesUpdatesAndDeactivationRejectsStaleEventsAndRebuilds() throws Exception {
    String category = "Search-" + UUID.randomUUID();
    var input =
        new HashMap<String, Object>(
            Map.of(
                "sku",
                "SEARCH-" + UUID.randomUUID(),
                "name",
                "Nebula keyboard",
                "description",
                "Quasar switches",
                "category",
                category,
                "price",
                new BigDecimal("19.95"),
                "currency",
                "USD",
                "active",
                true));
    var created =
        expect(
            call(
                catalog,
                "POST",
                "/api/v1/admin/products",
                token(CUSTOMER, "ADMIN"),
                input,
                Map.of("Idempotency-Key", UUID.randomUUID().toString())),
            201,
            ProductView.class);
    String query =
        "/api/v1/products/search?q=nebula&category="
            + category
            + "&minPrice=10.00&maxPrice=30.00&sort=priceAsc&size=1";
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              var found =
                  expect(
                      call(catalog, "GET", query, null, null, Map.of()),
                      200,
                      ProductSearch.SearchPage.class);
              assertThat(found.totalElements()).isEqualTo(1);
              assertThat(found.items().getFirst().id()).isEqualTo(created.id());
            });
    input.put("price", new BigDecimal("29.95"));
    input.put("description", "Updated quasar switches");
    var updated =
        expect(
            call(
                catalog,
                "PUT",
                "/api/v1/admin/products/" + created.id(),
                token(CUSTOMER, "ADMIN"),
                input,
                Map.of("If-Match", "\"0\"")),
            200,
            ProductView.class);
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(
                        expect(
                                call(catalog, "GET", query, null, null, Map.of()),
                                200,
                                ProductSearch.SearchPage.class)
                            .items()
                            .getFirst()
                            .price())
                    .isEqualByComparingTo("29.95"));
    var search = catalog.getBean(ProductSearch.class);
    search.index(
        new com.commerceflow.contracts.CheckoutEvents.ProductChanged(
            created.id(),
            created.sku(),
            created.name(),
            created.description(),
            created.category(),
            created.price(),
            created.currency(),
            true,
            created.version(),
            created.updatedAt()));
    assertThat(
            expect(
                    call(catalog, "GET", query, null, null, Map.of()),
                    200,
                    ProductSearch.SearchPage.class)
                .items()
                .getFirst()
                .price())
        .isEqualByComparingTo("29.95");
    input.put("active", false);
    expect(
        call(
            catalog,
            "PUT",
            "/api/v1/admin/products/" + created.id(),
            token(CUSTOMER, "ADMIN"),
            input,
            Map.of("If-Match", "\"" + updated.version() + "\"")),
        200,
        ProductView.class);
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(
                        expect(
                                call(catalog, "GET", query, null, null, Map.of()),
                                200,
                                ProductSearch.SearchPage.class)
                            .totalElements())
                    .isZero());
    var rebuilt =
        expect(
            call(
                catalog,
                "POST",
                "/api/v1/admin/search/rebuild",
                token(CUSTOMER, "ADMIN"),
                null,
                Map.of()),
            200,
            ProductSearch.RebuildResult.class);
    assertThat(rebuilt.documents()).isGreaterThan(0);
    assertThat(rebuilt.index()).startsWith("cf-products-v1-");
    assertThat(
            expect(
                    call(catalog, "GET", query, null, null, Map.of()),
                    200,
                    ProductSearch.SearchPage.class)
                .totalElements())
        .isZero();
  }

  @Test
  @org.junit.jupiter.api.Order(12)
  void notificationsAreDurableOwnedAndReplaySafe() throws Exception {
    var service = notifications.getBean(NotificationService.class);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(service.list(CUSTOMER, 0, 100).items())
                    .anyMatch(n -> n.status().equals("CONFIRMED")));
    var notice =
        service.list(CUSTOMER, 0, 100).items().stream()
            .filter(n -> n.status().equals("CONFIRMED"))
            .findFirst()
            .orElseThrow();
    var existing = getOrder(notice.orderId());
    var event =
        EventEnvelope.create(
            "OrderStatusChanged",
            notice.orderId(),
            "notification-replay",
            new com.commerceflow.contracts.CheckoutEvents.OrderStatus(
                CUSTOMER, "CONFIRMED", existing.totalAmount(), existing.currency()));
    @SuppressWarnings("unchecked")
    var kafka = (KafkaTemplate<String, String>) order.getBean(KafkaTemplate.class);
    kafka.send(Topics.ORDER, notice.orderId().toString(), JSON.writeValueAsString(event)).get();
    kafka.send(Topics.ORDER, notice.orderId().toString(), JSON.writeValueAsString(event)).get();
    var jdbc = notifications.getBean(JdbcClient.class);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id=:id")
                            .param("id", event.eventId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1));
    assertThat(
            jdbc.sql("SELECT count(*) FROM notifications WHERE order_id=:id AND status='CONFIRMED'")
                .param("id", notice.orderId())
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "SELECT payload->'payload'->>'customerId' FROM outbox_events WHERE"
                        + " aggregate_id=:id AND event_type='NotificationCreated' LIMIT 1")
                .param("id", notice.orderId())
                .query(String.class)
                .single())
        .isEqualTo(CUSTOMER.toString());
    var other =
        expect(
            call(
                notifications,
                "GET",
                "/api/v1/notifications",
                token(UUID.randomUUID(), "CUSTOMER"),
                null,
                Map.of()),
            200,
            NotificationService.NotificationPage.class);
    assertThat(other.items()).isEmpty();
    assertThat(
            call(notifications, "GET", "/api/v1/notifications", null, null, Map.of()).statusCode())
        .isEqualTo(401);
  }
}

package com.commerceflow.workflow;

import com.commerceflow.catalog.CatalogApplication;
import com.commerceflow.catalog.api.ProductView;
import com.commerceflow.gateway.GatewayApplication;
import com.commerceflow.inventory.InventoryApplication;
import com.commerceflow.order.OrderApplication;
import com.commerceflow.order.OrderModels.OrderView;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakSecurityTest {

    static EmbeddedPostgres postgres;
    static KeycloakTestServer keycloak;
    static ConfigurableApplicationContext catalog, orders, inventory, gateway;
    static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    static String alice, bob, admin;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        try (var c = postgres.getPostgresDatabase().getConnection();
             var sql = c.createStatement()) {
            for (var service : List.of("keycloak", "catalog", "order", "inventory")) {
                sql.execute("CREATE ROLE cf_" + service + " LOGIN PASSWORD 'test-only-" + service + "'");
                sql.execute("CREATE DATABASE cf_" + service + " OWNER cf_" + service);
                sql.execute("REVOKE ALL ON DATABASE cf_" + service + " FROM PUBLIC");
            }
        }
        keycloak = KeycloakTestServer.start(postgres);
        catalog = startApp(CatalogApplication.class, "catalog");
        orders = startApp(OrderApplication.class, "order", "--catalog.base-url=" + base(catalog));
        inventory = startApp(InventoryApplication.class, "inventory");
        gateway =
                new SpringApplicationBuilder(GatewayApplication.class)
                        .run(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--security.issuer=" + keycloak.issuer(),
                                "--security.jwk-set-uri=" + keycloak.issuer() + "/protocol/openid-connect/certs",
                                "--CATALOG_URL=" + base(catalog),
                                "--ORDER_URL=" + base(orders),
                                "--INVENTORY_URL=" + base(inventory),
                                "--logging.level.root=WARN");
        alice = login("alice");
        bob = login("bob");
        admin = login("admin");
    }

    static ConfigurableApplicationContext startApp(Class<?> source, String service, String... extra) {
        var args =
                new ArrayList<>(
                        List.of(
                                "--spring.config.name=identity-test",
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--spring.datasource.url=" + postgres.getJdbcUrl("cf_" + service, "cf_" + service),
                                "--spring.datasource.username=cf_" + service,
                                "--spring.datasource.password=test-only-" + service,
                                "--spring.flyway.locations=classpath:db/migration/" + service,
                                "--spring.jpa.hibernate.ddl-auto=validate",
                                "--spring.jpa.open-in-view=false",
                                "--commerceflow.messaging.enabled=false",
                                "--catalog.cache.enabled=false",
                                "--catalog.search.enabled=false",
                                "--security.issuer=" + keycloak.issuer(),
                                "--security.jwk-set-uri=" + keycloak.issuer() + "/protocol/openid-connect/certs",
                                "--logging.level.root=WARN"));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(source).run(args.toArray(String[]::new));
    }

    static String base(ConfigurableApplicationContext app) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterAll
    static void stop() throws Exception {
        for (var app : Arrays.asList(gateway, inventory, orders, catalog)) if (app != null) app.close();
        if (keycloak != null) keycloak.close();
        if (postgres != null) postgres.close();
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    static HttpResponse<String> post(HttpClient client, String url, Map<String, String> body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String login(String user) throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String verifier = UUID.randomUUID().toString() + UUID.randomUUID();
        String challenge =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String redirect = "http://localhost:5173/";
        String uri =
                keycloak.issuer()
                        + "/protocol/openid-connect/auth?"
                        + form(
                        Map.of(
                                "client_id",
                                "commerceflow-web",
                                "redirect_uri",
                                redirect,
                                "response_type",
                                "code",
                                "scope",
                                "openid",
                                "state",
                                UUID.randomUUID().toString(),
                                "nonce",
                                UUID.randomUUID().toString(),
                                "code_challenge",
                                challenge,
                                "code_challenge_method",
                                "S256"));
        var page =
                http.send(
                        HttpRequest.newBuilder(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofString());
        var action = Pattern.compile("<form[^>]+action=\"([^\"]+)\"").matcher(page.body());
        assertThat(action.find()).as("Keycloak login form exists").isTrue();
        String cookie =
                page.headers().allValues("set-cookie").stream()
                        .map(value -> value.split(";", 2)[0])
                        .collect(java.util.stream.Collectors.joining("; "));
        assertThat(cookie).as("Keycloak auth-session cookie").contains("AUTH_SESSION_ID");
        var logged =
                http.send(
                        HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&")))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("Cookie", cookie)
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                form(
                                                        Map.of(
                                                                "username",
                                                                user,
                                                                "password",
                                                                "local-" + user + "-only",
                                                                "credentialId",
                                                                ""))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(logged.statusCode()).as("Keycloak login redirect").isEqualTo(302);
        String location = logged.headers().firstValue("location").orElseThrow();
        String code =
                Arrays.stream(URI.create(location).getRawQuery().split("&"))
                        .filter(p -> p.startsWith("code="))
                        .map(p -> URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8))
                        .findFirst()
                        .orElseThrow();
        var token =
                post(
                        http,
                        keycloak.issuer() + "/protocol/openid-connect/token",
                        Map.of(
                                "grant_type",
                                "authorization_code",
                                "client_id",
                                "commerceflow-web",
                                "redirect_uri",
                                redirect,
                                "code",
                                code,
                                "code_verifier",
                                verifier));
        assertThat(token.statusCode()).isEqualTo(200);
        return JSON.readTree(token.body()).get("access_token").asText();
    }

    static HttpResponse<String> call(
            ConfigurableApplicationContext app,
            String method,
            String path,
            String token,
            Object body,
            Map<String, String> headers)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(base(app) + path))
                        .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void keycloakPkceTokensFlowThroughGatewayAndBackendRoleAndOwnershipChecks() throws Exception {
        var input =
                Map.of(
                        "sku",
                        "OIDC-" + UUID.randomUUID(),
                        "name",
                        "Secured product",
                        "description",
                        "Real Keycloak test",
                        "category",
                        "Security",
                        "price",
                        new BigDecimal("12.00"),
                        "currency",
                        "USD",
                        "active",
                        true);
        var created =
                call(
                        gateway,
                        "POST",
                        "/api/v1/admin/products",
                        admin,
                        input,
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var product = JSON.readValue(created.body(), ProductView.class);
        assertThat(
                call(gateway, "GET", "/api/v1/products/" + product.id(), null, null, Map.of())
                        .statusCode())
                .isEqualTo(200);
        assertThat(
                call(
                        gateway,
                        "POST",
                        "/api/v1/admin/products",
                        alice,
                        input,
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()))
                        .statusCode())
                .isEqualTo(403);
        var checkout =
                Map.of(
                        "items",
                        List.of(Map.of("productId", product.id(), "quantity", 1)),
                        "paymentScenario",
                        "APPROVE");
        var placed =
                call(
                        gateway,
                        "POST",
                        "/api/v1/orders",
                        alice,
                        checkout,
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
        var order = JSON.readValue(placed.body(), OrderView.class);
        assertThat(
                call(gateway, "GET", "/api/v1/orders/" + order.id(), bob, null, Map.of()).statusCode())
                .isEqualTo(404);
        assertThat(
                call(orders, "GET", "/api/v1/orders/" + order.id(), bob, null, Map.of()).statusCode())
                .isEqualTo(404);
        assertThat(
                call(gateway, "GET", "/api/v1/orders/" + order.id(), alice, null, Map.of())
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void serviceAccountScopeAllowsInternalMutationAndCustomerDoesNot() throws Exception {
        var response =
                post(
                        HttpClient.newHttpClient(),
                        keycloak.issuer() + "/protocol/openid-connect/token",
                        Map.of(
                                "grant_type",
                                "client_credentials",
                                "client_id",
                                "commerceflow-service",
                                "client_secret",
                                KeycloakTestServer.SECRET));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String service = JSON.readTree(response.body()).get("access_token").asText();
        String path = "/internal/v1/reservations/" + UUID.randomUUID() + "/release";
        assertThat(call(inventory, "POST", path, alice, null, Map.of()).statusCode()).isEqualTo(403);
        assertThat(call(inventory, "POST", path, service, null, Map.of()).statusCode()).isEqualTo(200);
    }

    @Test
    void publicClientRejectsPasswordGrantAndMissingPkce() throws Exception {
        var response =
                post(
                        HttpClient.newHttpClient(),
                        keycloak.issuer() + "/protocol/openid-connect/token",
                        Map.of(
                                "grant_type",
                                "password",
                                "client_id",
                                "commerceflow-web",
                                "username",
                                "alice",
                                "password",
                                "local-alice-only"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("unauthorized_client");
        String auth =
                keycloak.issuer()
                        + "/protocol/openid-connect/auth?"
                        + form(
                        Map.of(
                                "client_id",
                                "commerceflow-web",
                                "redirect_uri",
                                "http://localhost:5173/",
                                "response_type",
                                "code",
                                "scope",
                                "openid"));
        var rejected =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(auth)).build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(302);
        assertThat(rejected.headers().firstValue("location").orElseThrow())
                .contains("error=invalid_request")
                .contains("code_challenge");
    }
}

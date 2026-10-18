package com.commerceflow.workflow;

import com.commerceflow.cart.CartApplication;
import com.commerceflow.catalog.CatalogApplication;
import com.commerceflow.catalog.api.ProductInput;
import com.commerceflow.catalog.application.CatalogService;
import com.commerceflow.contracts.Topics;
import com.commerceflow.gateway.GatewayApplication;
import com.commerceflow.inventory.*;
import com.commerceflow.notification.NotificationApplication;
import com.commerceflow.order.OrderApplication;
import com.commerceflow.payment.PaymentApplication;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * Ephemeral browser-test infrastructure. All runtime infrastructure is real; use Compose for
 * persistent development.
 */
public class BrowserTestStack {
  static final List<ConfigurableApplicationContext> APPS = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    System.setProperty("commerceflow.root", System.getProperty("user.dir"));
    System.setProperty(
        "redis.server.path",
        System.getProperty("user.dir") + "/.cache/tools/redis-8.10.2/src/redis-server");
    System.setProperty(
        "elasticsearch.home.path",
        System.getProperty("user.dir") + "/.cache/tools/elasticsearch-9.4.5");
    System.setProperty(
        "keycloak.home.path", System.getProperty("user.dir") + "/.cache/tools/keycloak-26.8.1");
    var postgres = EmbeddedPostgres.builder().setPort(0).start();
    var redis = RedisTestServer.start();
    var search = ElasticsearchTestServer.start();
    try (var c = postgres.getPostgresDatabase().getConnection();
        var sql = c.createStatement()) {
      for (var service :
          List.of("keycloak", "catalog", "order", "inventory", "payment", "notification")) {
        sql.execute("CREATE ROLE cf_" + service + " LOGIN PASSWORD 'test-only-" + service + "'");
        sql.execute("CREATE DATABASE cf_" + service + " OWNER cf_" + service);
        sql.execute("REVOKE ALL ON DATABASE cf_" + service + " FROM PUBLIC");
      }
    }
    var keycloak = KeycloakTestServer.start(postgres, 8180);
    var broker =
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
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  var reversed = new ArrayList<>(APPS);
                  Collections.reverse(reversed);
                  reversed.forEach(ConfigurableApplicationContext::close);
                  broker.destroy();
                  try {
                    keycloak.close();
                    search.close();
                    redis.close();
                    postgres.close();
                  } catch (Exception error) {
                    error.printStackTrace();
                  }
                }));
    var definitions = new LinkedHashMap<String, Class<?>>();
    definitions.put("catalog", CatalogApplication.class);
    definitions.put("order", OrderApplication.class);
    definitions.put("inventory", InventoryApplication.class);
    definitions.put("payment", PaymentApplication.class);
    definitions.put("cart", CartApplication.class);
    definitions.put("notification", NotificationApplication.class);
    definitions.put("gateway", GatewayApplication.class);
    var ports =
        Map.of(
            "catalog",
            8081,
            "order",
            8082,
            "inventory",
            8083,
            "payment",
            8084,
            "cart",
            8085,
            "notification",
            8086,
            "gateway",
            8080);
    CatalogService catalog = null;
    InventoryService inventory = null;
    for (var entry : definitions.entrySet()) {
      String service = entry.getKey();
      var parameters =
          new ArrayList<>(
              List.of(
                  "--server.port=" + ports.get(service),
                  "--server.address=127.0.0.1",
                  "--spring.application.name=" + service + "-service",
                  "--spring.threads.virtual.enabled="
                      + System.getenv().getOrDefault("VIRTUAL_THREADS", "true"),
                  "--management.endpoints.web.exposure.include=health,info,prometheus",
                  "--management.endpoint.health.probes.enabled=true",
                  "--management.metrics.distribution.percentiles-histogram.http.server.requests=true",
                  "--security.issuer=" + keycloak.issuer(),
                  "--security.jwk-set-uri=" + keycloak.issuer() + "/protocol/openid-connect/certs",
                  "--commerceflow.messaging.bootstrap-servers=" + broker.getBrokersAsString(),
                  "--commerceflow.outbox.poll-delay=100",
                  "--saga.step-timeout=PT5S",
                  "--saga.reconcile-delay=500",
                  "--payment.delay=PT8S",
                  "--payment.settle-delay=200",
                  "--spring.data.redis.host=" + redis.host(),
                  "--spring.data.redis.port=" + redis.port(),
                  "--spring.data.redis.timeout=500ms",
                  "--spring.data.redis.connect-timeout=500ms",
                  "--spring.elasticsearch.uris=" + search.url(),
                  "--logging.level.root=WARN"));
      if (!service.equals("gateway")) parameters.add("--spring.config.name=browser-test");
      if (!Set.of("cart", "gateway").contains(service))
        parameters.addAll(
            List.of(
                "--spring.datasource.url=" + postgres.getJdbcUrl("cf_" + service, "cf_" + service),
                "--spring.datasource.username=cf_" + service,
                "--spring.datasource.password=test-only-" + service,
                "--spring.flyway.locations=classpath:db/migration/" + service,
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.open-in-view=false"));
      var app =
          new SpringApplicationBuilder(entry.getValue()).run(parameters.toArray(String[]::new));
      APPS.add(app);
      if (service.equals("catalog")) catalog = app.getBean(CatalogService.class);
      if (service.equals("inventory")) inventory = app.getBean(InventoryService.class);
    }
    int number = 0;
    for (var item :
        List.of(
            new ProductInput(
                "KEYBOARD-01",
                "Studio mechanical keyboard",
                "A quiet companion for your most focused work. Tactile keys, considered design.",
                "Workspace",
                new BigDecimal("129.00"),
                "USD",
                true),
            new ProductInput(
                "HEADPHONES-01",
                "Everyday headphones",
                "Immersive sound, soft cushions, and room for your favorite playlist.",
                "Audio",
                new BigDecimal("89.00"),
                "USD",
                true),
            new ProductInput(
                "MUG-01",
                "Morning ceramic mug",
                "A warm start. Hand-finished stoneware for everyday rituals.",
                "Home",
                new BigDecimal("24.00"),
                "USD",
                true),
            new ProductInput(
                "LAMP-01",
                "Arc desk lamp",
                "A softer light for big ideas. An adjustable shade in a timeless silhouette.",
                "Workspace",
                new BigDecimal("65.00"),
                "USD",
                true),
            new ProductInput(
                "BAG-01",
                "Daily canvas tote",
                "Room for the essentials. Durable cotton canvas made for the long way home.",
                "Everyday",
                new BigDecimal("32.00"),
                "USD",
                true),
            new ProductInput(
                "NOTEBOOK-01",
                "Pocket notebook",
                "A good place for a fresh idea. Smooth paper, lay-flat binding.",
                "Workspace",
                new BigDecimal("12.00"),
                "USD",
                true))) {
      var product = catalog.create("browser-seed-" + (++number), item, "browser-seed");
      inventory.setStock(product.id(), 25, null);
    }
    System.out.println(
        "BROWSER_STACK_READY gateway=http://localhost:8080 keycloak=http://localhost:8180");
    new CountDownLatch(1).await();
  }
}

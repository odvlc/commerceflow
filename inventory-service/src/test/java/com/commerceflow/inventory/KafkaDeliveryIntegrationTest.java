package com.commerceflow.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.commerceflow.contracts.*;
import com.commerceflow.contracts.CheckoutEvents.*;
import com.commerceflow.support.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.*;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "commerceflow.outbox.scheduling-enabled=false",
      "inventory.expiry-check-delay=600000"
    })
@EmbeddedKafka(
    partitions = 3,
    topics = {
      Topics.ORDER,
      Topics.INVENTORY,
      Topics.PAYMENT,
      Topics.CATALOG,
      Topics.NOTIFICATION,
      Topics.DEAD_LETTER
    },
    bootstrapServersProperty = "commerceflow.messaging.bootstrap-servers")
@DirtiesContext
class KafkaDeliveryIntegrationTest {
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

  @Autowired InventoryService inventory;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ProducerFactory<String, String> producers;
  @Autowired OutboxStore outbox;
  @Autowired OutboxPublisher publisher;
  @Autowired ProcessedEvents processed;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JsonMapper json;
  @Autowired EmbeddedKafkaBroker broker;

  @Test
  void actualKafkaReplaysHaveOneTransactionalBusinessEffect() throws Exception {
    var product = UUID.randomUUID();
    var order = UUID.randomUUID();
    inventory.setStock(product, 10, null);
    var event =
        EventEnvelope.create(
            "ReserveInventory",
            order,
            "kafka-replay",
            new ReserveInventory(List.of(new StockItem(product, 3))));
    for (int i = 0; i < 5; i++)
      kafka.send(Topics.ORDER, order.toString(), json.writeValueAsString(event)).get();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(inventory.stock(product).reservedQuantity()).isEqualTo(3));
    assertThat(
            jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id=:id")
                .param("id", event.eventId())
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id=:id")
                .param("id", order)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void failedBusinessEffectRollsBackDedupMarker() {
    var event = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                processed.once(
                    event,
                    () -> {
                      throw new IllegalStateException("crash");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(processed.once(event, () -> {})).isTrue();
    assertThat(
            processed.once(
                event,
                () -> {
                  throw new AssertionError("must not replay");
                }))
        .isFalse();
  }

  @Test
  void brokerOutageRetainsOutboxAndRecoveryPreservesAggregateOrder() {
    var order = UUID.randomUUID();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              outbox.append(
                  "PaymentResult", order, "outage", new PaymentResult("AUTHORIZED", null));
              outbox.append("PaymentResult", order, "outage", new PaymentResult("REFUNDED", null));
            });
    var config = new HashMap<String, Object>(producers.getConfigurationProperties());
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");
    var unavailable = new DefaultKafkaProducerFactory<String, String>(config);
    try {
      var failing =
          new OutboxPublisher(jdbc, new KafkaTemplate<>(unavailable), new SimpleMeterRegistry());
      int delivered = new TransactionTemplate(transactions).execute(tx -> failing.publishBatch());
      assertThat(delivered).isZero();
    } finally {
      unavailable.destroy();
    }
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM outbox_events WHERE aggregate_id=:id AND published_at IS"
                        + " NULL")
                .param("id", order)
                .query(Long.class)
                .single())
        .isEqualTo(2);
    assertThat(
            jdbc.sql("SELECT max(attempts) FROM outbox_events WHERE aggregate_id=:id")
                .param("id", order)
                .query(Integer.class)
                .single())
        .isGreaterThan(0);
    jdbc.sql("UPDATE outbox_events SET next_attempt_at=now() WHERE aggregate_id=:id")
        .param("id", order)
        .update();
    publisher.publishBatch();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM outbox_events WHERE aggregate_id=:id AND published_at IS"
                        + " NOT NULL")
                .param("id", order)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    publisher.publishBatch();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM outbox_events WHERE aggregate_id=:id AND published_at IS"
                        + " NOT NULL")
                .param("id", order)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void brokerRestartRetainsItsLogAndRecoversPendingOutboxWithoutRepeatingReservation()
      throws Exception {
    var product = UUID.randomUUID();
    var order = UUID.randomUUID();
    inventory.setStock(product, 10, null);
    var reserve =
        EventEnvelope.create(
            "ReserveInventory",
            order,
            "broker-restart",
            new ReserveInventory(List.of(new StockItem(product, 2))));
    kafka.send(Topics.ORDER, order.toString(), json.writeValueAsString(reserve)).get();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(inventory.stock(product).reservedQuantity()).isEqualTo(2));
    // Deliver the reservation result before interrupting the actual broker lifecycle.
    publisher.publishBatch();
    var cluster = Objects.requireNonNull(((EmbeddedKafkaKraftBroker) broker).getCluster());
    var server = cluster.brokers().values().iterator().next();
    server.shutdown(Duration.ofSeconds(5));
    server.awaitShutdown();
    assertThat(server.isShutdown()).isTrue();
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              tx ->
                  outbox.append(
                      "CommitInventory",
                      order,
                      "broker-restart",
                      new Command("PAYMENT_AUTHORIZED")));
      assertThat(publisher.publishBatch()).isZero();
      assertThat(
              jdbc.sql(
                      "SELECT count(*) FROM outbox_events WHERE aggregate_id=:id AND published_at"
                          + " IS NULL")
                  .param("id", order)
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
    } finally {
      // Restart the same server with the same log directories and listener address.
      server.startup();
      cluster.waitForReadyBrokers();
    }
    jdbc.sql("UPDATE outbox_events SET next_attempt_at=now() WHERE aggregate_id=:id")
        .param("id", order)
        .update();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              publisher.publishBatch();
              assertThat(inventory.stock(product).reservedQuantity()).isZero();
              assertThat(inventory.stock(product).availableQuantity()).isEqualTo(8);
              assertThat(
                      jdbc.sql(
                              "SELECT count(*) FROM outbox_events WHERE aggregate_id=:id AND"
                                  + " event_type='CommitInventory' AND published_at IS NULL")
                          .param("id", order)
                          .query(Long.class)
                          .single())
                  .isZero();
            });
    kafka.send(Topics.ORDER, order.toString(), json.writeValueAsString(reserve)).get();
    // Read from the beginning after restart: the acknowledged pre-restart record must remain.
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "restart-log-" + order,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false))) {
      var partitions =
          List.of(
              new TopicPartition(Topics.ORDER, 0),
              new TopicPartition(Topics.ORDER, 1),
              new TopicPartition(Topics.ORDER, 2));
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      var seen = new HashSet<String>();
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                for (var record : consumer.poll(Duration.ofMillis(250)))
                  if (order.toString().equals(record.key()))
                    seen.add(json.readTree(record.value()).path("eventType").asText());
                return seen.containsAll(Set.of("ReserveInventory", "CommitInventory"));
              });
    }
    assertThat(
            jdbc.sql("SELECT count(*) FROM reservations WHERE order_id=:id AND status='COMMITTED'")
                .param("id", order)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(inventory.stock(product).availableQuantity()).isEqualTo(8);
  }

  @Test
  void malformedMessagesReachDeadLetterTopicAfterBoundedRetries() throws Exception {
    String key = UUID.randomUUID().toString();
    String poison = "{\"invalid\":true}";
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "dlq-test-" + key,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false))) {
      var partitions =
          List.of(
              new TopicPartition(Topics.DEAD_LETTER, 0),
              new TopicPartition(Topics.DEAD_LETTER, 1),
              new TopicPartition(Topics.DEAD_LETTER, 2));
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      kafka.send(Topics.ORDER, key, poison).get();
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                for (var record : consumer.poll(Duration.ofMillis(250)))
                  if (key.equals(record.key()) && poison.equals(record.value())) return true;
                return false;
              });
    }
  }
}

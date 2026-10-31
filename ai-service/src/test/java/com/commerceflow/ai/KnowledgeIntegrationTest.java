/*
 * Verifies versioned knowledge persistence, retrieval/index synchronization and authorization
 * against real infrastructure.
 */
package com.commerceflow.ai;

import com.commerceflow.ai.indexing.KnowledgeIndexer;
import com.commerceflow.ai.retrieval.KnowledgeIndex;
import com.commerceflow.ai.retrieval.KnowledgeModels.ImportDocument;
import com.commerceflow.ai.retrieval.KnowledgeModels.ImportResult;
import com.commerceflow.ai.retrieval.KnowledgeSearch;
import com.commerceflow.ai.retrieval.KnowledgeStore;
import com.commerceflow.contracts.EventEnvelope;
import com.commerceflow.contracts.KnowledgeEvents.KnowledgeChanged;
import com.commerceflow.contracts.Topics;
import com.commerceflow.support.OutboxPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        properties = {
                "commerceflow.outbox.scheduling-enabled=false",
                "ai.worker-enabled=false",
                "ai.index-delay=600000",
                "ai.embeddings-enabled=false"
        })
@EmbeddedKafka(
        partitions = 3,
        topics = {Topics.AI_KNOWLEDGE, Topics.DEAD_LETTER},
        bootstrapServersProperty = "commerceflow.messaging.bootstrap-servers")
@DirtiesContext
@TestExecutionListeners(
        listeners = KnowledgeIntegrationTest.Cleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class KnowledgeIntegrationTest {

    static final EmbeddedPostgres PG = startPg();
    static final ElasticsearchTestServer SEARCH = startSearch();

    static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static ElasticsearchTestServer startSearch() {
        try {
            return ElasticsearchTestServer.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("ai.search-url", SEARCH::url);
    }

    @Autowired
    KnowledgeStore store;

    @Autowired
    KnowledgeSearch search;

    @Autowired
    KnowledgeIndexer indexer;

    @Autowired
    KnowledgeIndex index;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource data;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;
    UUID owner = UUID.randomUUID();

    @BeforeEach
    void clear() {
        jdbc.sql("TRUNCATE"
                        + " knowledge_requests,knowledge_documents,outbox_events,processed_events,knowledge_rebuilds"
                        + " CASCADE")
                .update();
    }

    ImportDocument doc(String source, long version, String content, boolean active) {
        return new ImportDocument(source, version, "Demo returns policy", "PUBLIC", content, active);
    }

    void index(ImportResult result) {
        await().atMost(Duration.ofSeconds(40))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    publisher.publishBatch();
                    indexer.poll();
                    assertThat(store.job(result.jobId()).state()).isEqualTo("SUCCEEDED");
                });
    }

    @Test
    void concurrentImportsPublishOnceAndRealKafkaElasticsearchProduceCitations() throws Exception {
        // Exercise the actual delivery/index path so a stored import row alone cannot satisfy
        // retrieval acceptance.
        var input = doc(
                "return-policy",
                1,
                "# Return window\n\n"
                        + "Unused items in original packaging have a 30 calendar day return window.",
                true);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<ImportResult>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++)
                calls.add(() -> store.ingest(owner, "same-import-key", input, "ingest"));
            var results = pool.invokeAll(calls);
            Set<UUID> ids = new HashSet<>();
            for (var r : results)
                ids.add(r.get().jobId());
            assertThat(ids).hasSize(1);
            index(results.getFirst().get());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single())
                .isEqualTo(1);
        var found = search.search("return window", "CUSTOMER", 6).items();
        assertThat(found).isNotEmpty();
        assertThat(found.getFirst().sourceId()).isEqualTo("return-policy");
        assertThat(found.getFirst().version()).isEqualTo(1);
        assertThat(found.getFirst().id()).startsWith("k:");
        assertThatThrownBy(() -> store.ingest(
                owner,
                "same-import-key",
                doc("return-policy",
                        2,
                        "changed",
                        true), "ingest"))
                .hasMessageContaining("different contents");
    }

    @Test
    void sourceVersionsAudienceTombstonesAndAliasRebuildAreVerified() {
        var first = store.ingest(
                owner,
                "import-v1",
                doc("policy", 1, "Original obsolete return rule.\n\nSecond obsolete paragraph.", true),
                "v1");
        index(first);
        var updated = store.ingest(owner, "import-v2", doc("policy", 2, "Updated replacement rule.", true), "v2");
        assertThat(search.search("obsolete", "CUSTOMER", 6).items()).isEmpty();
        index(updated);
        assertThat(search.search("replacement", "CUSTOMER", 6).items()).isNotEmpty();
        assertThat(search.search("obsolete", "CUSTOMER", 6).items()).isEmpty();
        assertThatThrownBy(() -> store.ingest(owner, "stale-v1-key", doc("policy", 1, "stale", true), "stale"))
                .hasMessageContaining("stale");
        var support = store.ingest(
                owner,
                "support-key",
                new ImportDocument(
                        "runbook",
                        1,
                        "Internal reconciliation",
                        "SUPPORT",
                        "Investigate reconciliation using authorized explanation.",
                        true),
                "support");
        index(support);
        assertThat(search.search("reconciliation", "CUSTOMER", 6).items()).isEmpty();
        assertThat(search.search("reconciliation", "SUPPORT", 6).items()).isNotEmpty();
        var rebuild = store.rebuild(owner, "rebuild-key");
        indexer.poll();
        assertThat(store.job(rebuild.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(store.job(rebuild.id()).documents()).isEqualTo(2);
        assertThat(store.rebuild(owner, "rebuild-key").id()).isEqualTo(rebuild.id());
        assertThat(search.search("replacement", "CUSTOMER", 6).items()).isNotEmpty();

        var inactive = store.ingest(
                owner,
                "deactivate-v3",
                doc("policy", 3, "Updated replacement rule.", false),
                "deactivate");
        index(inactive);
        assertThat(search.search("replacement", "CUSTOMER", 6).items()).isEmpty();
    }

    @Test
    void abandonedRebuildResumesOnceSessionLockIsReleasedWithoutWaitingForDiagnosticLease()
            throws Exception {
        // A released session lock is the recovery authority; the diagnostic lease must not delay a
        // safe replay.
        var imported = store.ingest(
                owner,
                "recovery-source",
                doc("recovery", 1, "Recoverable rebuild evidence.", true),
                "recovery");
        index(imported);
        var job = store.rebuild(owner, "recovery-rebuild");
        store.rebuildState(job.id(), "RUNNING", 0, null);
        try (var connection = data.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(hashtextextended('commerceflow-ai-knowledge-index',0))");
            try {
                indexer.poll();
                assertThat(store.job(job.id()).state()).isEqualTo("RUNNING");
            } finally {
                statement.execute("SELECT pg_advisory_unlock(hashtextextended('commerceflow-ai-knowledge-index',0))");
            }
        }
        assertThat(jdbc.sql("SELECT lease_until>now()+interval '9 minutes' FROM knowledge_rebuilds WHERE"
                        + " id=:id")
                .param("id", job.id())
                .query(Boolean.class)
                .single())
                .isTrue();
        indexer.poll();
        assertThat(store.job(job.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(store.job(job.id()).documents()).isEqualTo(1);
        // Alias swap may have committed before a worker's last database checkpoint.
        store.rebuildState(job.id(), "RUNNING", 0, null);
        indexer.poll();
        assertThat(store.job(job.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(search.search("Recoverable", "CUSTOMER", 5).items()).isNotEmpty();
    }

    @Test
    void projectionOutageRetainsRecoverableJobsAndSearchFailsExplicitly() {
        var pending = store.ingest(
                owner,
                "outage-import",
                doc("outage-policy", 1, "Recoverable policy evidence.", true),
                "outage");
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> {
                    publisher.publishBatch();
                    assertThat(
                            jdbc.sql("SELECT ready FROM knowledge_index_jobs WHERE id=:id")
                                    .param("id", pending.jobId())
                                    .query(Boolean.class)
                                    .single())
                            .isTrue();
                });
        var broken = new KnowledgeIndex("http://127.0.0.1:1", json);
        new KnowledgeIndexer(data, store, broken, new SimpleMeterRegistry()).poll();
        assertThat(store.job(pending.jobId()).state()).isEqualTo("PENDING");
        assertThatThrownBy(() ->
                new KnowledgeSearch(broken, store, new SimpleMeterRegistry())
                        .search("policy", "CUSTOMER", 6))
                .hasMessageContaining("temporarily unavailable");
        jdbc.sql("UPDATE knowledge_index_jobs SET next_attempt_at=now()").update();
        index(pending);
        assertThat(search.search("Recoverable", "CUSTOMER", 6).items()).isNotEmpty();
    }

    @Test
    void replayedKnowledgeEventsHaveOneReadyJobAndOneMarker() throws Exception {
        var imported = store.ingest(
                owner,
                "replay-import",
                doc("replay-policy", 1, "Unique replay proof.", true),
                "replay");
        var event = EventEnvelope.create(
                "KnowledgeChanged",
                imported.documentId(),
                "replay",
                new KnowledgeChanged(imported.documentId(), 1));
        for (int i = 0; i < 5; i++)
            kafka.send(Topics.AI_KNOWLEDGE, imported.documentId().toString(), json.writeValueAsString(event))
                    .get();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(
                        jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id=:id")
                                .param("id", event.eventId())
                                .query(Long.class)
                                .single())
                        .isEqualTo(1));
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_index_jobs WHERE document_id=:id")
                .param("id", imported.documentId())
                .query(Long.class)
                .single())
                .isEqualTo(1);
        indexer.poll();
        assertThat(store.job(imported.jobId()).state()).isEqualTo("SUCCEEDED");
    }

    public static class Cleanup extends AbstractTestExecutionListener {

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public void afterTestClass(TestContext context) throws Exception {
            SEARCH.close();
            PG.close();
        }
    }
}

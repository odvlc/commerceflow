package com.commerceflow.ai;

import com.commerceflow.ai.domain.Actor;
import com.commerceflow.ai.persistence.AiStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = {
                "commerceflow.messaging.enabled=false",
                "ai.worker-enabled=false",
                "ai.queue-limit=8"
        })
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext
@org.springframework.test.context.TestExecutionListeners(
        listeners = AiFoundationTest.Cleanup.class,
        mergeMode =
                org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class AiFoundationTest {

    static final EmbeddedPostgres PG = start();

    static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
    }

    @Autowired
    AiStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc http;
    Actor alice =
            new Actor(
                    UUID.randomUUID(), "not-persisted", Set.of("CUSTOMER"), Instant.now().plusSeconds(300));
    Actor bob =
            new Actor(
                    UUID.randomUUID(), "not-persisted", Set.of("CUSTOMER"), Instant.now().plusSeconds(300));

    @BeforeEach
    void clear() {
        jdbc.sql("TRUNCATE ai_conversations CASCADE").update();
    }

    @Test
    void twentyConcurrentRetriesCreateOneConversationAndOneRun() throws Exception {
        try (var pool = Executors.newFixedThreadPool(10)) {
            List<Callable<UUID>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++)
                calls.add(() -> store.create(alice, "create-key", "CUSTOMER").id());
            var ids =
                    pool.invokeAll(calls).stream()
                            .map(f -> {
                                try {
                                    return f.get();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .distinct()
                            .toList();
            assertThat(ids).hasSize(1);
            calls.clear();
            for (int i = 0; i < 20; i++)
                calls.add(() -> store.send(ids.getFirst(), alice, "message-key", "Explain the demo.").id());
            var runs =
                    pool.invokeAll(calls).stream()
                            .map(f -> {
                                try {
                                    return f.get();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .distinct()
                            .toList();
            assertThat(runs).hasSize(1);
            assertThat(store.messages(ids.getFirst(), alice, 0, 20).totalElements()).isEqualTo(1);
            assertThatThrownBy(() -> store.send(ids.getFirst(), alice, "message-key", "different"))
                    .hasMessageContaining("different contents");
        }
    }

    @Test
    void ownershipAndCurrentRolesAreCheckedForEveryResource() {
        var c = store.create(alice, "conversation-a", "CUSTOMER");
        var run = store.send(c.id(), alice, "message-a", "Hello");
        assertThatThrownBy(() -> store.run(run.id(), bob)).hasMessage("Resource not found");
        assertThatThrownBy(() -> store.messages(c.id(), bob, 0, 20)).hasMessage("Resource not found");
        var downgraded = new Actor(alice.subject(), "token", Set.of("SUPPORT"), alice.expiresAt());
        assertThatThrownBy(() -> store.run(run.id(), downgraded)).hasMessageContaining("not permitted");
        assertThatThrownBy(() -> store.create(alice, "support-mode", "SUPPORT"))
                .hasMessageContaining("not permitted");
    }

    @Test
    void activeRunAndQueueLimitsRollbackRejectedMessages() {
        var c = store.create(alice, "conversation-a", "CUSTOMER");
        store.send(c.id(), alice, "message-a", "one");
        assertThatThrownBy(() -> store.send(c.id(), alice, "message-b", "two"))
                .hasMessageContaining("current run");
        assertThat(store.messages(c.id(), alice, 0, 20).totalElements()).isEqualTo(1);
        for (int i = 0; i < 7; i++) {
            var next = store.create(alice, "conversation-" + i, "CUSTOMER");
            store.send(next.id(), alice, "message-" + i, "hello");
        }
        var next = store.create(alice, "conversation-overflow", "CUSTOMER");
        assertThatThrownBy(() -> store.send(next.id(), alice, "overflow-message", "hello"))
                .hasMessageContaining("queue is full");
        assertThat(store.messages(next.id(), alice, 0, 20).totalElements()).isZero();
    }

    @Test
    void cancellingIsIdempotentAndReleasesConversation() {
        var c = store.create(alice, "conversation-a", "CUSTOMER");
        var run = store.send(c.id(), alice, "message-a", "one");
        assertThat(store.cancel(run.id(), alice).state()).isEqualTo("CANCELLED");
        assertThat(store.cancel(run.id(), alice).state()).isEqualTo("CANCELLED");
        assertThat(store.send(c.id(), alice, "message-b", "two").state()).isEqualTo("QUEUED");
        assertThat(store.events(run.id(), alice, 0)).isNotEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_runs WHERE to_jsonb(ai_runs)::text LIKE"
                        + " '%not-persisted%'")
                .query(Long.class)
                .single())
                .isZero();
    }

    @Test
    void httpAuthenticationValidationAndOpenApi() throws Exception {
        http.perform(get("/api/v1/ai/conversations")).andExpect(status().isUnauthorized());
        var auth = jwt().jwt(j -> j.subject(alice.subject().toString())
                        .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        http.perform(post("/api/v1/ai/conversations")
                        .with(auth)
                        .header("Idempotency-Key", "http-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"CUSTOMER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("CUSTOMER"));
        http.perform(post("/api/v1/ai/conversations")
                        .with(auth)
                        .header("Idempotency-Key", "http-invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/v1/ai/conversations?size=101").with(auth))
                .andExpect(status().isBadRequest());
        http.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/ai/conversations']").exists());
    }

    @Autowired
    com.commerceflow.ai.tools.ToolRegistry tools;

    @Test
    void toolSchemasRejectUnregisteredAuthorityAndTargetsBeforeAnyDispatch() {
        JsonMapper mapper = JsonMapper.builder().build();
        var run = UUID.randomUUID();
        assertThatThrownBy(() -> tools.invoke(
                run, alice, "CUSTOMER", "shell", mapper.valueToTree(Map.of("command", "ls"))))
                .hasMessageContaining("not available");
        assertThatThrownBy(() -> tools.invoke(
                run,
                alice,
                "CUSTOMER",
                "getProduct",
                mapper.valueToTree(
                        Map.of("productId", UUID.randomUUID(), "url", "http://169.254.169.254"))))
                .hasMessageContaining("registered schema");
        assertThatThrownBy(() -> tools.invoke(
                run,
                alice,
                "CUSTOMER",
                "getMyOrderExplanation",
                mapper.valueToTree(
                        Map.of("orderId", UUID.randomUUID(), "customerId", bob.subject()))))
                .hasMessageContaining("registered schema");
        assertThatThrownBy(() -> tools.invoke(
                run,
                alice,
                "CUSTOMER",
                "getProduct",
                mapper.valueToTree(Map.of("productId", "invalid"))))
                .hasMessageContaining("registered schema");
        assertThatThrownBy(() -> tools.invoke(
                run,
                alice,
                "CUSTOMER",
                "getSupportOrderExplanation",
                mapper.valueToTree(Map.of("orderId", UUID.randomUUID()))))
                .hasMessageContaining("not available");
        var expired =
                new Actor(alice.subject(), "expired", alice.roles(), Instant.now().minusSeconds(1));
        assertThatThrownBy(
                () -> tools.invoke(run, expired, "CUSTOMER", "getMyCart", mapper.valueToTree(Map.of())))
                .hasMessageContaining("Fresh authentication");
    }

    @Autowired
    com.commerceflow.ai.persistence.RunStore runs;

    @Test
    void competingClaimsFencesCancellationAndExpiredLeases() throws Exception {
        var c = store.create(alice, "lease-conversation", "CUSTOMER");
        var r = store.send(c.id(), alice, "lease-message", "Explain returns");
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<java.util.Optional<com.commerceflow.ai.domain.AiModels.Claimed>>> calls =
                    new ArrayList<>();
            for (int i = 0; i < 20; i++) calls.add(runs::claim);
            var claimed =
                    pool.invokeAll(calls).stream()
                            .map(f -> {
                                try {
                                    return f.get();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .flatMap(Optional::stream)
                            .toList();
            assertThat(claimed).hasSize(1);
            var claim = claimed.getFirst();
            var step = runs.begin(claim, "TOOL", "getMyCart", Map.of());
            store.cancel(r.id(), alice);
            assertThatThrownBy(() -> runs.complete(claim, step, Map.of(), 1, true))
                    .isInstanceOf(com.commerceflow.ai.persistence.RunStore.LostLease.class);
            assertThatThrownBy(() -> runs.finish(claim, "late answer", List.of(), 1))
                    .isInstanceOf(com.commerceflow.ai.persistence.RunStore.LostLease.class);
        }
        var next = store.send(c.id(), alice, "lease-message-next", "again");
        var claim = runs.claim().orElseThrow();
        jdbc.sql("UPDATE ai_runs SET lease_until=now()-interval '1 second' WHERE id=:id")
                .param("id", next.id())
                .update();
        runs.recover();
        assertThat(store.run(next.id(), alice).state()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(runs.active(claim)).isFalse();
        store.resume(next.id(), alice);
        var resumed = runs.claim().orElseThrow();
        assertThat(resumed.fence()).isGreaterThan(claim.fence());
        assertThatThrownBy(() -> runs.begin(claim, "MODEL", "late", Map.of()))
                .isInstanceOf(com.commerceflow.ai.persistence.RunStore.LostLease.class);
        runs.stop(resumed, "AUTHENTICATION_REQUIRED", "AI_AUTHENTICATION_REQUIRED", 1);
        jdbc.sql("UPDATE ai_runs SET updated_at=now()-interval '16 minutes' WHERE id=:id")
                .param("id", next.id())
                .update();
        runs.recover();
        assertThat(store.run(next.id(), alice).state()).isEqualTo("FAILED");
    }

    @Test
    void iterationToolRetryAndEventReplayBudgetsAreDurable() {
        var c = store.create(alice, "budget-conversation", "CUSTOMER");
        var r = store.send(c.id(), alice, "budget-message", "hello");
        var claim = runs.claim().orElseThrow();
        for (int i = 0; i < 4; i++) {
            var step = runs.begin(claim, "MODEL", "investigate", Map.of("i", i));
            runs.complete(claim, step, Map.of("public", "response"), 1, true);
        }
        assertThatThrownBy(() -> runs.begin(claim, "MODEL", "again", Map.of()))
                .isInstanceOf(com.commerceflow.ai.persistence.RunStore.BudgetExceeded.class);
        for (int i = 0; i < 8; i++) runs.begin(claim, "TOOL", "getMyCart", Map.of("i", i));
        assertThatThrownBy(() -> runs.begin(claim, "TOOL", "again", Map.of()))
                .isInstanceOf(com.commerceflow.ai.persistence.RunStore.BudgetExceeded.class);
        runs.retry(claim, "AI_TOOL_UNAVAILABLE", 1);
        assertThat(store.run(r.id(), alice).state()).isEqualTo("RETRY_WAIT");
        for (int i = 0; i < 2; i++) {
            jdbc.sql("UPDATE ai_runs SET next_attempt_at=now() WHERE id=:id")
                    .param("id", r.id())
                    .update();
            var retry = runs.claim().orElseThrow();
            runs.retry(retry, "AI_TOOL_UNAVAILABLE", 1);
        }
        assertThat(store.run(r.id(), alice).state()).isEqualTo("FAILED");
        assertThat(store.run(r.id(), alice).rounds()).isEqualTo(4);
        var events = store.events(r.id(), alice, 0);
        assertThat(events).isNotEmpty();
        long cursor = events.get(2).sequence();
        assertThat(store.events(r.id(), alice, cursor)).allMatch(e -> e.sequence() > cursor);
        assertThatThrownBy(() -> store.events(r.id(), bob, cursor)).hasMessage("Resource not found");
    }

    @Test
    void completedFinalMessageAndEventCommitTogether() {
        var c = store.create(alice, "final-conversation", "CUSTOMER");
        var r = store.send(c.id(), alice, "final-message", "hello");
        var claim = runs.claim().orElseThrow();
        runs.finish(claim, "Evidence unavailable", List.of(), 1);
        assertThat(store.run(r.id(), alice).state()).isEqualTo("SUCCEEDED");
        assertThat(store.messages(c.id(), alice, 0, 20).items()).hasSize(2);
        assertThat(store.events(r.id(), alice, 0)).anyMatch(e -> e.type().equals("final"));
        assertThatThrownBy(() -> runs.finish(claim, "duplicate", List.of(), 1))
                .isInstanceOf(com.commerceflow.ai.persistence.RunStore.LostLease.class);
        assertThat(store.messages(c.id(), alice, 0, 20).items()).hasSize(2);
    }

    @Test
    void streamReplaysCommittedEventsWithoutCreatingAnotherRunAndChecksExpiry() throws Exception {
        var c = store.create(alice, "stream-conversation", "CUSTOMER");
        var r = store.send(c.id(), alice, "stream-message", "hello");
        var claim = runs.claim().orElseThrow();
        runs.finish(claim, "Evidence unavailable", List.of(), 1);
        var events = store.events(r.id(), alice, 0);
        long cursor = events.getFirst().sequence();
        var auth = jwt().jwt(j -> j.subject(alice.subject().toString())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
        var result =
                http.perform(get("/api/v1/ai/runs/" + r.id() + "/events")
                                .with(auth)
                                .header("Last-Event-ID", cursor))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        result.getAsyncResult(5000);
        http.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:final")));
        assertThat(jdbc.sql("SELECT count(*) FROM ai_runs").query(Long.class).single()).isEqualTo(1);
        var other = jwt().jwt(j -> j.subject(bob.subject().toString())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
        http.perform(get("/api/v1/ai/runs/" + r.id() + "/events").with(other))
                .andExpect(status().isNotFound());
        var expired = jwt().jwt(j -> j.subject(alice.subject().toString())
                .expiresAt(Instant.now().minusSeconds(1))
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
        http.perform(get("/api/v1/ai/runs/" + r.id() + "/events").with(expired))
                .andExpect(status().isUnauthorized());
    }

    @Autowired
    com.commerceflow.ai.persistence.ActionStore actions;

    @Test
    void proposalVersionOwnershipRejectionExpiryAndRecoveryDeadlineAreEnforced() {
        JsonMapper mapper = JsonMapper.builder().build();
        var c = store.create(alice, "proposal-conversation", "CUSTOMER");
        var r = store.send(c.id(), alice, "proposal-message", "prepare only");
        var claim = runs.claim().orElseThrow();
        var p = actions.propose(
                r.id(),
                claim.fence(),
                alice,
                "ADD",
                UUID.randomUUID(),
                2,
                UUID.randomUUID() + ":0",
                mapper.valueToTree(Map.of("recoverySeconds", 50)));
        runs.stop(claim, "WAITING_CONFIRMATION", null, 0);
        assertThatThrownBy(() -> actions.confirm(
                p.id(),
                bob,
                new com.commerceflow.ai.domain.AiModels.Confirmation(0, p.argumentsHash())))
                .hasMessage("Resource not found");
        assertThatThrownBy(() -> actions.confirm(
                p.id(),
                alice,
                new com.commerceflow.ai.domain.AiModels.Confirmation(1, p.argumentsHash())))
                .hasMessageContaining("expired or changed");
        var expired = new Actor(alice.subject(), "expired", alice.roles(), Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> actions.confirm(
                p.id(),
                expired,
                new com.commerceflow.ai.domain.AiModels.Confirmation(0, p.argumentsHash())))
                .hasMessageContaining("Fresh authentication");
        var denied = new Actor(alice.subject(), "support", Set.of("SUPPORT"), alice.expiresAt());
        assertThatThrownBy(() -> actions.confirm(
                p.id(),
                denied,
                new com.commerceflow.ai.domain.AiModels.Confirmation(0, p.argumentsHash())))
                .hasMessageContaining("not permitted");
        var e = actions.confirm(
                p.id(),
                alice,
                new com.commerceflow.ai.domain.AiModels.Confirmation(0, p.argumentsHash()));
        assertThat(e.recoveryUntil()).isBefore(Instant.now().plusSeconds(51));
        assertThat(e.attempts()).isZero();
        assertThat(actions.confirm(
                        p.id(),
                        alice,
                        new com.commerceflow.ai.domain.AiModels.Confirmation(0, p.argumentsHash()))
                .id())
                .isEqualTo(e.id());
        store.cancel(r.id(), alice);
        var next = store.send(c.id(), alice, "expiry-message", "prepare again");
        var nextClaim = runs.claim().orElseThrow();
        var expiring = actions.propose(
                next.id(),
                nextClaim.fence(),
                alice,
                "SET",
                UUID.randomUUID(),
                1,
                UUID.randomUUID() + ":0",
                mapper.valueToTree(Map.of("recoverySeconds", 50)));
        runs.stop(nextClaim, "WAITING_CONFIRMATION", null, 0);
        jdbc.sql("UPDATE ai_action_proposals SET expires_at=now()-interval '1 second' WHERE id=:id")
                .param("id", expiring.id())
                .update();
        actions.expire();
        assertThat(actions.owned(expiring.id(), alice).state()).isEqualTo("EXPIRED");
        assertThat(store.run(next.id(), alice).state()).isEqualTo("CANCELLED");
        var last = store.send(c.id(), alice, "reject-message", "prepare again");
        var lastClaim = runs.claim().orElseThrow();
        var rejecting = actions.propose(
                last.id(),
                lastClaim.fence(),
                alice,
                "DELETE",
                UUID.randomUUID(),
                0,
                UUID.randomUUID() + ":0",
                mapper.valueToTree(Map.of("recoverySeconds", 50)));
        runs.stop(lastClaim, "WAITING_CONFIRMATION", null, 0);
        assertThat(actions.reject(rejecting.id(), alice, 0).state()).isEqualTo("REJECTED");
        assertThat(actions.reject(rejecting.id(), alice, 0).state()).isEqualTo("REJECTED");
        assertThat(actions.execution(rejecting.id())).isEmpty();
    }

    @Test
    void knowledgeQueriesRejectPersonalIdentifiersAndUnauthorizedAudienceBeforeRetrieval()
            throws Exception {
        var auth = jwt().jwt(j -> j.subject(alice.subject().toString())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
        for (String query :
                List.of("Order " + UUID.randomUUID(), "Contact alice@example.test", "bearer confidential"))
            http.perform(get("/api/v1/ai/knowledge/search").param("query", query).with(auth))
                    .andExpect(status().isBadRequest());
        http.perform(get("/api/v1/ai/knowledge/search")
                        .param("query", "operations")
                        .param("mode", "SUPPORT")
                        .with(auth))
                .andExpect(status().isForbidden());
    }

    @Autowired
    com.commerceflow.ai.application.CartActions cartActions;

    @Test
    void recoveryKeepsAnAlreadyCommittedProposalWithoutRepreparingIt() {
        var c = store.create(alice, "proposal-crash-conversation", "CUSTOMER");
        var r = store.send(c.id(), alice, "proposal-crash-message", "prepare only");
        var claim = runs.claim().orElseThrow();
        var mapper = JsonMapper.builder().build();
        var product = UUID.randomUUID();
        String revision = UUID.randomUUID() + ":0";
        var proposal = actions.propose(
                r.id(),
                claim.fence(),
                alice,
                "ADD",
                product,
                2,
                revision,
                mapper.readTree("{\"recoverySeconds\":50,\"unitPrice\":\"19.95\"}"));
        assertThat(actions.propose(
                        r.id(),
                        claim.fence(),
                        alice,
                        "ADD",
                        product,
                        2,
                        revision,
                        mapper.readTree("{\"unitPrice\":\"19.95\",\"recoverySeconds\":50}"))
                .id())
                .isEqualTo(proposal.id());
        // Intent committed, but the worker died before recording tool completion/WAITING_CONFIRMATION.
        jdbc.sql("UPDATE ai_runs SET lease_until=now()-interval '1 second' WHERE id=:id")
                .param("id", r.id())
                .update();
        runs.recover();
        store.resume(r.id(), alice);
        assertThat(cartActions.continueAction(runs.claim().orElseThrow(), alice)).isTrue();
        assertThat(store.run(r.id(), alice).state()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(actions.owned(proposal.id(), alice).argumentsHash())
                .isEqualTo(proposal.argumentsHash());
        assertThat(actions.execution(proposal.id())).isEmpty();
    }

    @Autowired
    com.commerceflow.ai.application.Credentials credentials;

    @Autowired
    com.commerceflow.ai.model.LocalModels models;

    @Test
    void aCredentialAttachmentCannotChangeTheClaimedOwner() throws Exception {
        var c = store.create(alice, "owner-context-conversation", "CUSTOMER");
        var run = store.send(c.id(), alice, "owner-context-message", "private question");
        credentials.attach(run.id(), bob);
        try (var worker = new com.commerceflow.ai.application.RunWorker(
                runs, credentials, models, tools, JsonMapper.builder().build(), cartActions)) {
            worker.poll();
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .until(() -> store.run(run.id(), alice).state().equals("AUTHENTICATION_REQUIRED"));
            assertThat(store.steps(run.id())).isEmpty();
            assertThat(credentials.get(run.id())).isEmpty();
        }
    }

    public static class Cleanup
            extends org.springframework.test.context.support.AbstractTestExecutionListener {

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public void afterTestClass(org.springframework.test.context.TestContext context)
                throws Exception {
            PG.close();
        }
    }
}

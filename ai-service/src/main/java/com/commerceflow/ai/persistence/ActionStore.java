package com.commerceflow.ai.persistence;

import com.commerceflow.ai.domain.Actor;
import com.commerceflow.ai.domain.AiModels.Claimed;
import com.commerceflow.ai.domain.AiModels.Confirmation;
import com.commerceflow.ai.domain.AiModels.Execution;
import com.commerceflow.ai.domain.AiModels.Proposal;
import com.commerceflow.support.RequestHash;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class ActionStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AiStore views;

    public ActionStore(JdbcClient jdbc, JsonMapper json, AiStore views) {
        this.jdbc = jdbc;
        this.json = json;
        this.views = views;
    }

    private void lockedRun(UUID id, Actor actor) {
        views.run(id, actor);
        actor.requireMode("CUSTOMER");
        if (!actor.valid())
            throw new com.commerceflow.support.BusinessException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "AI_AUTHENTICATION_REQUIRED",
                    "Fresh authentication required");
        jdbc.sql("SELECT id FROM ai_runs WHERE id=:id FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .single();
    }

    @Transactional
    public Proposal propose(
            UUID run,
            long fence,
            Actor actor,
            String operation,
            UUID product,
            int quantity,
            String revision,
            JsonNode preview) {
        lockedRun(run, actor);
        var current = views.runInternal(run);
        if (!current.state().equals("RUNNING") || current.fence() != fence)
            throw new RunStore.LostLease();
        String hash =
                RequestHash.sha256(
                        json.writeValueAsString(
                                List.of(
                                        operation,
                                        product.toString(),
                                        quantity,
                                        revision,
                                        json.convertValue(preview, TreeMap.class))));
        var existing = views.proposals(run);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().argumentsHash().equals(hash))
                throw AiStore.conflict("AI_PROPOSAL_EXISTS", "Only one exact proposal is allowed per run");
            return existing.getFirst();
        }
        jdbc.sql(
                        "INSERT INTO"
                                + " ai_action_proposals(id,run_id,owner_id,operation,product_id,quantity,arguments_hash,cart_revision,state,expires_at,preview)"
                                + " VALUES(:id,:run,:owner,:operation,:product,:quantity,:hash,:revision,'PROPOSED',now()+interval"
                                + " '5 minutes',CAST(:preview AS jsonb))")
                .param("id", UUID.randomUUID())
                .param("run", run)
                .param("owner", actor.subject())
                .param("operation", operation)
                .param("product", product)
                .param("quantity", quantity)
                .param("hash", hash)
                .param("revision", revision)
                .param("preview", json.writeValueAsString(preview))
                .update();
        var p = views.proposals(run).getFirst();
        views.event(run, "proposal", p);
        return p;
    }

    public Proposal owned(UUID id, Actor actor) {
        var run =
                jdbc.sql("SELECT run_id FROM ai_action_proposals WHERE id=:id AND owner_id=:owner")
                        .param("id", id)
                        .param("owner", actor.subject())
                        .query(UUID.class)
                        .optional()
                        .orElseThrow(AiStore::missing);
        views.run(run, actor);
        actor.requireMode("CUSTOMER");
        return views.proposals(run).stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(AiStore::missing);
    }

    @Transactional
    public Execution confirm(UUID id, Actor actor, Confirmation input) {
        var proposal = owned(id, actor);
        lockedRun(proposal.runId(), actor);
        proposal = owned(id, actor);
        if (!proposal.argumentsHash().equals(input.argumentsHash()))
            throw AiStore.conflict("AI_APPROVAL_CHANGED", "Proposal arguments do not match");
        var existing = execution(id);
        if (existing.isPresent()) return existing.get();
        if (!proposal.state().equals("PROPOSED")
                || proposal.version() != input.version()
                || !proposal.expiresAt().isAfter(Instant.now())
                || !views.runInternal(proposal.runId()).state().equals("WAITING_CONFIRMATION"))
            throw AiStore.conflict(
                    "AI_APPROVAL_STALE", "Proposal expired or changed; review a new proposal");
        views.admit();
        var executionId = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO"
                                + " ai_action_executions(id,proposal_id,request_key,request_hash,state,recovery_until)"
                                + " VALUES(:id,:proposal,:key,:hash,'PENDING',now()+(:seconds * interval '1"
                                + " second'))")
                .param("id", executionId)
                .param("proposal", id)
                .param("key", "ai-cart-" + executionId)
                .param("hash", proposal.argumentsHash())
                .param("seconds", proposal.preview().path("recoverySeconds").asLong())
                .update();
        jdbc.sql(
                        "UPDATE ai_action_proposals SET state='CONFIRMED',version=version+1,confirmed_at=now()"
                                + " WHERE id=:id")
                .param("id", id)
                .update();
        jdbc.sql(
                        "UPDATE ai_runs SET"
                                + " state='QUEUED',fence=fence+1,error_code=NULL,next_attempt_at=now(),updated_at=now()"
                                + " WHERE id=:id")
                .param("id", proposal.runId())
                .update();
        views.event(proposal.runId(), "state", Map.of("state", "QUEUED", "executionId", executionId));
        return execution(id).orElseThrow();
    }

    public Optional<Execution> execution(UUID proposal) {
        return jdbc.sql("SELECT * FROM ai_action_executions WHERE proposal_id=:id")
                .param("id", proposal)
                .query(
                        (r, n) ->
                                new Execution(
                                        r.getObject("id", UUID.class),
                                        proposal,
                                        r.getString("request_key"),
                                        r.getString("state"),
                                        r.getInt("attempts"),
                                        r.getString("result") == null ? null : json.readTree(r.getString("result")),
                                        r.getString("error_code"),
                                        r.getTimestamp("recovery_until").toInstant()))
                .optional();
    }

    @Transactional
    public Execution start(Claimed c, UUID proposal) {
        var run =
                jdbc.sql(
                                "SELECT id FROM ai_runs WHERE id=:id AND state='RUNNING' AND fence=:fence AND"
                                        + " lease_until>now() FOR UPDATE")
                        .param("id", c.id())
                        .param("fence", c.fence())
                        .query(UUID.class)
                        .optional();
        if (run.isEmpty()) throw new RunStore.LostLease();
        var value = execution(proposal).orElseThrow();
        if (value.state().equals("SUCCEEDED") || value.state().equals("FAILED")) return value;
        if (!value.recoveryUntil().isAfter(Instant.now()) || value.attempts() >= 3) {
            record(proposal, "OUTCOME_UNKNOWN", null, "AI_RECOVERY_WINDOW_EXPIRED");
            return execution(proposal).orElseThrow();
        }
        jdbc.sql(
                        "UPDATE ai_action_executions SET attempts=attempts+1,updated_at=now() WHERE"
                                + " proposal_id=:id")
                .param("id", proposal)
                .update();
        return execution(proposal).orElseThrow();
    }

    /**
     * Positive acknowledgments remain auditable even if cancellation fenced the requesting worker.
     */
    @Transactional
    public void record(UUID proposal, String state, JsonNode result, String code) {
        jdbc.sql(
                        "UPDATE ai_action_executions SET state=:state,result=CAST(:result AS"
                                + " jsonb),error_code=:code,updated_at=now() WHERE proposal_id=:id AND"
                                + " state<>'SUCCEEDED'")
                .param("state", state)
                .param("result", result == null ? null : json.writeValueAsString(result))
                .param("code", code)
                .param("id", proposal)
                .update();
        jdbc.sql(
                        "UPDATE ai_action_proposals SET state=:state,version=version+1 WHERE id=:id AND"
                                + " state<>'SUCCEEDED'")
                .param("id", proposal)
                .param("state", state)
                .update();
    }

    @Transactional
    public Proposal reject(UUID id, Actor actor, long version) {
        var p = owned(id, actor);
        lockedRun(p.runId(), actor);
        if (jdbc.sql(
                        "UPDATE ai_action_proposals SET state='REJECTED',version=version+1 WHERE id=:id AND"
                                + " state='PROPOSED' AND version=:version")
                .param("id", id)
                .param("version", version)
                .update()
                == 1) {
            jdbc.sql(
                            "UPDATE ai_runs SET state='CANCELLED',fence=fence+1,updated_at=now() WHERE id=:id AND"
                                    + " state='WAITING_CONFIRMATION'")
                    .param("id", p.runId())
                    .update();
            views.event(p.runId(), "state", Map.of("state", "CANCELLED"));
        }
        return owned(id, actor);
    }

    @Transactional
    public void expire() {
        var candidates =
                jdbc.sql(
                                "SELECT run_id FROM ai_action_proposals WHERE state='PROPOSED' AND"
                                        + " expires_at<=now() ORDER BY run_id")
                        .query(UUID.class)
                        .list();
        for (var id : candidates) {
            jdbc.sql("SELECT id FROM ai_runs WHERE id=:id FOR UPDATE")
                    .param("id", id)
                    .query(UUID.class)
                    .single();
            if (jdbc.sql(
                            "UPDATE ai_action_proposals SET state='EXPIRED',version=version+1 WHERE"
                                    + " run_id=:id AND state='PROPOSED' AND expires_at<=now()")
                    .param("id", id)
                    .update()
                    == 1) {
                jdbc.sql(
                                "UPDATE ai_runs SET"
                                        + " state='CANCELLED',fence=fence+1,error_code='AI_PROPOSAL_EXPIRED',updated_at=now()"
                                        + " WHERE id=:id AND state='WAITING_CONFIRMATION'")
                        .param("id", id)
                        .update();
                views.event(id, "state", Map.of("state", "CANCELLED", "errorCode", "AI_PROPOSAL_EXPIRED"));
            }
        }
    }
}

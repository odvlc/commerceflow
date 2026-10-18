package com.commerceflow.ai.persistence;

import com.commerceflow.ai.domain.AiModels.Claimed;
import com.commerceflow.ai.retrieval.KnowledgeModels.Evidence;
import com.commerceflow.support.RequestHash;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Short transactions around checkpoints. No model, broker or service calls belong here.
 */
@Repository
@Transactional(readOnly = true)
public class RunStore {

    public static final class LostLease extends RuntimeException {

    }

    public static final class BudgetExceeded extends RuntimeException {

    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AiStore views;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public RunStore(
            JdbcClient jdbc,
            JsonMapper json,
            AiStore views,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.json = json;
        this.views = views;
        this.metrics = metrics;
    }

    public void invalidCitation() {
        metrics.counter("commerceflow.ai.citation.failures").increment();
    }

    @Transactional
    public Optional<Claimed> claim() {
        var id =
                jdbc.sql(
                                "SELECT id FROM ai_runs WHERE state IN ('QUEUED','RETRY_WAIT') AND"
                                        + " next_attempt_at<=now() ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT"
                                        + " 1")
                        .query(UUID.class)
                        .optional();
        if (id.isEmpty()) return Optional.empty();
        jdbc.sql(
                        "UPDATE ai_runs SET state='RUNNING',fence=fence+1,lease_until=now()+interval '15"
                                + " seconds',accounted_at=now(),updated_at=now() WHERE id=:id")
                .param("id", id.get())
                .update();
        var claim =
                jdbc.sql(
                                "SELECT r.*,c.mode FROM ai_runs r JOIN ai_conversations c ON c.id=r.conversation_id"
                                        + " WHERE r.id=:id")
                        .param("id", id.get())
                        .query(
                                (r, n) ->
                                        new Claimed(
                                                r.getObject("id", UUID.class),
                                                r.getObject("conversation_id", UUID.class),
                                                r.getObject("owner_id", UUID.class),
                                                r.getString("mode"),
                                                r.getLong("fence"),
                                                r.getString("model_name"),
                                                r.getInt("rounds"),
                                                r.getLong("active_millis")))
                        .single();
        views.event(claim.id(), "state", Map.of("state", "RUNNING", "attempt", claim.fence()));
        return Optional.of(claim);
    }

    private void lock(Claimed c) {
        // A lease alone is insufficient: a delayed worker must also match the latest claim's fence.
        if (jdbc.sql(
                        "SELECT id FROM ai_runs WHERE id=:id AND fence=:fence AND state='RUNNING' AND"
                                + " lease_until>now() FOR UPDATE")
                .param("id", c.id())
                .param("fence", c.fence())
                .query(UUID.class)
                .optional()
                .isEmpty()) throw new LostLease();
    }

    public boolean active(Claimed c) {
        return jdbc.sql(
                        "SELECT count(*) FROM ai_runs WHERE id=:id AND fence=:fence AND state='RUNNING' AND"
                                + " lease_until>now()")
                .param("id", c.id())
                .param("fence", c.fence())
                .query(Long.class)
                .single()
                == 1;
    }

    public record History(String role, String content) {

    }

    public List<History> history(Claimed c) {
        return jdbc.sql(
                        "SELECT role,content FROM (SELECT role,content,sequence FROM ai_messages WHERE"
                                + " conversation_id=:id ORDER BY sequence DESC LIMIT 6) m ORDER BY sequence")
                .param("id", c.conversationId())
                .query((r, n) -> new History(r.getString("role"), r.getString("content")))
                .list();
    }

    @Transactional
    public UUID begin(Claimed c, String kind, String name, Object input) {
        lock(c);
        // Charge before dispatch so crashes and retries cannot reset the durable work budget.
        if (kind.equals("MODEL")) {
            if (jdbc.sql(
                            "UPDATE ai_runs SET rounds=rounds+1 WHERE id=:id AND rounds<4 AND"
                                    + " active_millis<120000")
                    .param("id", c.id())
                    .update()
                    != 1) throw new BudgetExceeded();
        } else if (jdbc.sql("SELECT count(*) FROM ai_run_steps WHERE run_id=:id AND kind='TOOL'")
                .param("id", c.id())
                .query(Long.class)
                .single()
                >= 8) throw new BudgetExceeded();
        var id = UUID.randomUUID();
        String encoded = json.writeValueAsString(input);
        jdbc.sql(
                        "INSERT INTO ai_run_steps(id,run_id,kind,name,request_hash,input,state)"
                                + " VALUES(:id,:run,:kind,:name,:hash,CAST(:input AS jsonb),'PENDING')")
                .param("id", id)
                .param("run", c.id())
                .param("kind", kind)
                .param("name", name)
                .param("hash", RequestHash.sha256(encoded))
                .param("input", encoded)
                .update();
        views.event(
                c.id(), "step", Map.of("stepId", id, "kind", kind, "name", name, "state", "PENDING"));
        return id;
    }

    @Transactional
    public void complete(Claimed c, UUID step, Object result, long duration, boolean success) {
        lock(c);
        jdbc.sql(
                        "UPDATE ai_run_steps SET state=:state,result=CAST(:result AS"
                                + " jsonb),duration_millis=:duration WHERE id=:id AND run_id=:run AND"
                                + " state='PENDING'")
                .param("state", success ? "SUCCEEDED" : "FAILED")
                .param("result", json.writeValueAsString(result))
                .param("duration", duration)
                .param("id", step)
                .param("run", c.id())
                .update();
        metrics
                .timer("commerceflow.ai.step", "outcome", success ? "success" : "failure")
                .record(java.time.Duration.ofMillis(Math.max(0, duration)));
        views.event(c.id(), "step", Map.of("stepId", step, "state", success ? "SUCCEEDED" : "FAILED"));
    }

    public int rounds(Claimed c) {
        return jdbc.sql("SELECT rounds FROM ai_runs WHERE id=:id")
                .param("id", c.id())
                .query(Integer.class)
                .single();
    }

    @Transactional
    public void finish(Claimed c, String answer, List<Evidence> citations, long elapsed) {
        lock(c);
        jdbc.sql(
                        "INSERT INTO ai_messages(id,conversation_id,run_id,role,content,citations)"
                                + " VALUES(:id,:conversation,:run,'ASSISTANT',:content,CAST(:citations AS jsonb))")
                .param("id", UUID.randomUUID())
                .param("conversation", c.conversationId())
                .param("run", c.id())
                .param("content", answer)
                .param("citations", json.writeValueAsString(citations))
                .update();
        transition(c, "SUCCEEDED", null, elapsed);
        views.event(
                c.id(), "final", Map.of("state", "SUCCEEDED", "content", answer, "citations", citations));
    }

    @Transactional
    public void stop(Claimed c, String state, String code, long elapsed) {
        lock(c);
        transition(c, state, code, elapsed);
    }

    private void transition(Claimed c, String state, String code, long elapsed) {
        metrics.counter("commerceflow.ai.transitions", "state", state).increment();
        long activeMillis =
                jdbc.sql(
                                "UPDATE ai_runs SET"
                                        + " state=:state,error_code=:code,active_millis=active_millis+GREATEST(0,(extract(epoch"
                                        + " FROM (now()-accounted_at))*1000)::bigint),lease_until=NULL,updated_at=now()"
                                        + " WHERE id=:id RETURNING active_millis")
                        .param("state", state)
                        .param("code", code)
                        .param("id", c.id())
                        .query(Long.class)
                        .single();
        if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state))
            metrics
                    .timer("commerceflow.ai.run", "state", state)
                    .record(java.time.Duration.ofMillis(Math.max(0, activeMillis)));
        jdbc.sql(
                        "UPDATE ai_run_steps SET state='FAILED',result='{"
                                + "\"errorCode\":\"AI_INTERRUPTED\"}'::jsonb WHERE run_id=:id AND state='PENDING'")
                .param("id", c.id())
                .update();
        views.event(c.id(), "state", Map.of("state", state, "errorCode", code == null ? "" : code));
    }

    @Transactional
    public void retry(Claimed c, String code, long elapsed) {
        lock(c);
        int attempts =
                jdbc.sql("SELECT transient_attempts FROM ai_runs WHERE id=:id")
                        .param("id", c.id())
                        .query(Integer.class)
                        .single();
        if (attempts >= 2 || c.activeMillis() + elapsed >= 120000) {
            transition(c, "FAILED", code, elapsed);
            return;
        }
        jdbc.sql(
                        "UPDATE ai_runs SET"
                                + " transient_attempts=transient_attempts+1,next_attempt_at=now()+interval '2"
                                + " seconds' WHERE id=:id")
                .param("id", c.id())
                .update();
        transition(c, "RETRY_WAIT", code, elapsed);
    }

    @Transactional
    public boolean renew(Claimed c) {
        return jdbc.sql(
                        "UPDATE ai_runs SET active_millis=active_millis+GREATEST(0,(extract(epoch FROM"
                                + " (now()-accounted_at))*1000)::bigint),accounted_at=now(),lease_until=now()+interval"
                                + " '15 seconds' WHERE id=:id AND fence=:fence AND state='RUNNING' AND"
                                + " lease_until>now() AND active_millis<120000")
                .param("id", c.id())
                .param("fence", c.fence())
                .update()
                == 1;
    }

    @Transactional
    public void recover() {
        // Every restart/expired lease invalidates old credentials and the previous worker's fence.
        var expired =
                jdbc.sql(
                                "UPDATE ai_runs SET"
                                        + " state='AUTHENTICATION_REQUIRED',fence=fence+1,lease_until=NULL,active_millis=LEAST(120000,active_millis+GREATEST(0,(extract(epoch"
                                        + " FROM"
                                        + " (lease_until-accounted_at))*1000)::bigint)),error_code='AI_WORKER_INTERRUPTED',updated_at=now()"
                                        + " WHERE state='RUNNING' AND lease_until<=now() RETURNING id")
                        .query(UUID.class)
                        .list();
        for (var id : expired) views.event(id, "state", Map.of("state", "AUTHENTICATION_REQUIRED"));
        var abandoned =
                jdbc.sql(
                                "UPDATE ai_runs SET"
                                        + " state='FAILED',fence=fence+1,error_code='AI_AUTHENTICATION_EXPIRED',updated_at=now()"
                                        + " WHERE state='AUTHENTICATION_REQUIRED' AND updated_at<now()-interval '15"
                                        + " minutes' RETURNING id")
                        .query(UUID.class)
                        .list();
        for (var id : abandoned)
            views.event(id, "state", Map.of("state", "FAILED", "errorCode", "AI_AUTHENTICATION_EXPIRED"));
    }
}

package com.commerceflow.ai.persistence;

import com.commerceflow.ai.domain.Actor;
import com.commerceflow.ai.domain.AiModels.Conversation;
import com.commerceflow.ai.domain.AiModels.MessageView;
import com.commerceflow.ai.domain.AiModels.Page;
import com.commerceflow.ai.domain.AiModels.Proposal;
import com.commerceflow.ai.domain.AiModels.RunEvent;
import com.commerceflow.ai.domain.AiModels.RunView;
import com.commerceflow.ai.domain.AiModels.StepView;
import com.commerceflow.support.BusinessException;
import com.commerceflow.support.RequestHash;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class AiStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String model;
    private final int queueLimit;

    public AiStore(
            JdbcClient jdbc,
            JsonMapper json,
            @Value("${ai.chat-model:llama3.1:8b}") String model,
            @Value("${ai.queue-limit:8}") int queueLimit) {
        this.jdbc = jdbc;
        this.json = json;
        this.model = model;
        this.queueLimit = queueLimit;
    }

    public static BusinessException missing() {
        return new BusinessException(HttpStatus.NOT_FOUND, "AI_NOT_FOUND", "Resource not found");
    }

    public static BusinessException conflict(String code, String text) {
        return new BusinessException(HttpStatus.CONFLICT, code, text);
    }

    private void lock(String value) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:value,0))")
                .param("value", value)
                .query(Object.class)
                .single();
    }

    private Conversation conversation(ResultSet r, int n) throws SQLException {
        return new Conversation(
                r.getObject("id", UUID.class),
                r.getObject("owner_id", UUID.class),
                r.getString("mode"),
                r.getLong("version"),
                r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("updated_at").toInstant());
    }

    public Conversation owned(UUID id, Actor actor) {
        var c =
                jdbc.sql("SELECT * FROM ai_conversations WHERE id=:id AND owner_id=:owner")
                        .param("id", id)
                        .param("owner", actor.subject())
                        .query(this::conversation)
                        .optional()
                        .orElseThrow(AiStore::missing);
        actor.requireMode(c.mode());
        return c;
    }

    @Transactional
    public Conversation create(Actor actor, String key, String mode) {
        actor.requireMode(mode);
        lock("conversation:" + actor.subject() + ":" + key);
        var existing =
                jdbc.sql("SELECT * FROM ai_conversations WHERE owner_id=:owner AND request_key=:key")
                        .param("owner", actor.subject())
                        .param("key", key)
                        .query(this::conversation)
                        .optional();
        if (existing.isPresent()) {
            if (!existing.get().mode().equals(mode))
                throw conflict("IDEMPOTENCY_CONFLICT", "Key has different contents");
            return existing.get();
        }
        var id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO ai_conversations(id,owner_id,mode,request_key)"
                                + " VALUES(:id,:owner,:mode,:key)")
                .param("id", id)
                .param("owner", actor.subject())
                .param("mode", mode)
                .param("key", key)
                .update();
        return owned(id, actor);
    }

    public Page<Conversation> conversations(Actor actor, int page, int size) {
        var items =
                jdbc.sql(
                                "SELECT * FROM ai_conversations WHERE owner_id=:owner ORDER BY updated_at DESC,id"
                                        + " LIMIT :size OFFSET :offset")
                        .param("owner", actor.subject())
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(this::conversation)
                        .list();
        var count =
                jdbc.sql("SELECT count(*) FROM ai_conversations WHERE owner_id=:owner")
                        .param("owner", actor.subject())
                        .query(Long.class)
                        .single();
        return new Page<>(items, page, size, count);
    }

    @Transactional
    public void admit() {
        lock("ai-admission");
        if (jdbc.sql("SELECT count(*) FROM ai_runs WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')")
                .query(Long.class)
                .single()
                >= queueLimit)
            throw new BusinessException(
                    HttpStatus.TOO_MANY_REQUESTS, "AI_CAPACITY_EXCEEDED", "Copilot queue is full");
    }

    @Transactional
    public RunView send(UUID conversation, Actor actor, String key, String content) {
        owned(conversation, actor);
        lock("run:" + actor.subject() + ":" + key);
        String hash = RequestHash.sha256(conversation + ":" + content);
        var existing =
                jdbc.sql("SELECT id,request_hash FROM ai_runs WHERE owner_id=:owner AND request_key=:key")
                        .param("owner", actor.subject())
                        .param("key", key)
                        .query((r, n) -> Map.entry(r.getObject(1, UUID.class), r.getString(2)))
                        .optional();
        if (existing.isPresent()) {
            if (!existing.get().getValue().equals(hash))
                throw conflict("IDEMPOTENCY_CONFLICT", "Key has different contents");
            return run(existing.get().getKey(), actor);
        }
        jdbc.sql("SELECT id FROM ai_conversations WHERE id=:id FOR UPDATE")
                .param("id", conversation)
                .query(UUID.class)
                .single();
        if (jdbc.sql(
                        "SELECT count(*) FROM ai_runs WHERE conversation_id=:id AND state NOT IN"
                                + " ('SUCCEEDED','FAILED','CANCELLED')")
                .param("id", conversation)
                .query(Long.class)
                .single()
                > 0) throw conflict("AI_RUN_ACTIVE", "Finish or cancel the current run first");
        admit();
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO"
                                + " ai_runs(id,conversation_id,owner_id,request_key,request_hash,state,model_name)"
                                + " VALUES(:id,:conversation,:owner,:key,:hash,'QUEUED',:model)")
                .param("id", id)
                .param("conversation", conversation)
                .param("owner", actor.subject())
                .param("key", key)
                .param("hash", hash)
                .param("model", model)
                .update();
        message(conversation, id, "USER", content, "[]");
        jdbc.sql("UPDATE ai_conversations SET updated_at=now(),version=version+1 WHERE id=:id")
                .param("id", conversation)
                .update();
        event(id, "queued", Map.of("state", "QUEUED"));
        return run(id, actor);
    }

    public Page<MessageView> messages(UUID id, Actor actor, int page, int size) {
        owned(id, actor);
        var items =
                jdbc.sql(
                                "SELECT * FROM ai_messages WHERE conversation_id=:id ORDER BY sequence LIMIT :size"
                                        + " OFFSET :offset")
                        .param("id", id)
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(
                                (r, n) ->
                                        new MessageView(
                                                r.getObject("id", UUID.class),
                                                r.getObject("run_id", UUID.class),
                                                r.getString("role"),
                                                r.getString("content"),
                                                json.readTree(r.getString("citations")),
                                                r.getLong("sequence"),
                                                r.getTimestamp("created_at").toInstant()))
                        .list();
        long count =
                jdbc.sql("SELECT count(*) FROM ai_messages WHERE conversation_id=:id")
                        .param("id", id)
                        .query(Long.class)
                        .single();
        return new Page<>(items, page, size, count);
    }

    public RunView run(UUID id, Actor actor) {
        var conversation =
                jdbc.sql("SELECT conversation_id FROM ai_runs WHERE id=:id AND owner_id=:owner")
                        .param("id", id)
                        .param("owner", actor.subject())
                        .query(UUID.class)
                        .optional()
                        .orElseThrow(AiStore::missing);
        owned(conversation, actor);
        return runInternal(id);
    }

    public RunView runInternal(UUID id) {
        return jdbc.sql("SELECT * FROM ai_runs WHERE id=:id")
                .param("id", id)
                .query(
                        (r, n) ->
                                new RunView(
                                        id,
                                        r.getObject("conversation_id", UUID.class),
                                        r.getString("state"),
                                        r.getString("model_name"),
                                        r.getLong("fence"),
                                        r.getString("error_code"),
                                        r.getInt("rounds"),
                                        r.getLong("active_millis"),
                                        r.getTimestamp("created_at").toInstant(),
                                        r.getTimestamp("updated_at").toInstant(),
                                        steps(id),
                                        proposals(id)))
                .optional()
                .orElseThrow(AiStore::missing);
    }

    public List<StepView> steps(UUID id) {
        return jdbc.sql("SELECT * FROM ai_run_steps WHERE run_id=:id ORDER BY sequence")
                .param("id", id)
                .query(
                        (r, n) ->
                                new StepView(
                                        r.getObject("id", UUID.class),
                                        r.getLong("sequence"),
                                        r.getString("kind"),
                                        r.getString("name"),
                                        r.getString("state"),
                                        json.readTree(r.getString("input")),
                                        r.getString("result") == null ? null : json.readTree(r.getString("result")),
                                        (Long) r.getObject("duration_millis")))
                .list();
    }

    public List<Proposal> proposals(UUID id) {
        return jdbc.sql("SELECT * FROM ai_action_proposals WHERE run_id=:id")
                .param("id", id)
                .query(
                        (r, n) ->
                                new Proposal(
                                        r.getObject("id", UUID.class),
                                        id,
                                        r.getString("operation"),
                                        r.getObject("product_id", UUID.class),
                                        r.getInt("quantity"),
                                        r.getString("arguments_hash"),
                                        r.getString("cart_revision"),
                                        r.getString("state"),
                                        r.getLong("version"),
                                        r.getTimestamp("expires_at").toInstant(),
                                        json.readTree(r.getString("preview"))))
                .list();
    }

    public List<RunEvent> events(UUID id, Actor actor, long after) {
        run(id, actor);
        return jdbc.sql(
                        "SELECT * FROM ai_run_events WHERE run_id=:id AND sequence>:after ORDER BY sequence"
                                + " LIMIT 100")
                .param("id", id)
                .param("after", after)
                .query(
                        (r, n) ->
                                new RunEvent(
                                        r.getLong("sequence"),
                                        r.getString("type"),
                                        json.readTree(r.getString("payload"))))
                .list();
    }

    @Transactional
    public RunView cancel(UUID id, Actor actor) {
        run(id, actor);
        jdbc.sql(
                        "UPDATE ai_runs SET state='CANCELLED',fence=fence+1,lease_until=NULL,updated_at=now()"
                                + " WHERE id=:id AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')")
                .param("id", id)
                .update();
        jdbc.sql(
                        "UPDATE ai_action_proposals SET state='REJECTED',version=version+1 WHERE run_id=:id AND"
                                + " state='PROPOSED'")
                .param("id", id)
                .update();
        event(id, "state", Map.of("state", runInternal(id).state()));
        return run(id, actor);
    }

    @Transactional
    public RunView resume(UUID id, Actor actor) {
        if (run(id, actor).state().equals("AUTHENTICATION_REQUIRED")) admit();
        jdbc.sql(
                        "UPDATE ai_runs SET state='QUEUED',error_code=NULL,updated_at=now() WHERE id=:id AND"
                                + " state='AUTHENTICATION_REQUIRED'")
                .param("id", id)
                .update();
        return run(id, actor);
    }

    public void event(UUID id, String type, Object payload) {
        jdbc.sql(
                        "INSERT INTO ai_run_events(run_id,type,payload) VALUES(:id,:type,CAST(:payload AS"
                                + " jsonb))")
                .param("id", id)
                .param("type", type)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }

    private void message(UUID conversation, UUID run, String role, String content, String citations) {
        jdbc.sql(
                        "INSERT INTO ai_messages(id,conversation_id,run_id,role,content,citations)"
                                + " VALUES(:id,:conversation,:run,:role,:content,CAST(:citations AS jsonb))")
                .param("id", UUID.randomUUID())
                .param("conversation", conversation)
                .param("run", run)
                .param("role", role)
                .param("content", content)
                .param("citations", citations)
                .update();
    }
}

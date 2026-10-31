/*
 * Commits curated document versions, chunks and outgoing changes together in the AI-owned
 * database.
 */
package com.commerceflow.ai.retrieval;

import com.commerceflow.ai.persistence.AiStore;
import com.commerceflow.ai.retrieval.KnowledgeModels.Chunk;
import com.commerceflow.ai.retrieval.KnowledgeModels.Document;
import com.commerceflow.ai.retrieval.KnowledgeModels.ImportDocument;
import com.commerceflow.ai.retrieval.KnowledgeModels.ImportResult;
import com.commerceflow.ai.retrieval.KnowledgeModels.IndexJob;
import com.commerceflow.ai.retrieval.KnowledgeModels.JobView;
import com.commerceflow.contracts.KnowledgeEvents.KnowledgeChanged;
import com.commerceflow.support.OutboxStore;
import com.commerceflow.support.RequestHash;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class KnowledgeStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final OutboxStore outbox;

    public KnowledgeStore(JdbcClient jdbc, JsonMapper json, OutboxStore outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
    }

    private void lock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", key)
                .query(Object.class)
                .single();
    }

    private Document map(ResultSet r, int n) throws SQLException {
        return new Document(
                r.getObject("id", UUID.class),
                r.getString("source_id"),
                r.getLong("version"),
                r.getString("title"),
                r.getString("audience"),
                r.getString("content"),
                r.getBoolean("active"),
                r.getTimestamp("effective_at").toInstant());
    }

    @Transactional
    public ImportResult ingest(UUID owner, String key, ImportDocument input, String correlation) {
        String hash = RequestHash.sha256(json.writeValueAsString(input));
        lock("knowledge-request:" + owner + ":" + key);
        var old =
                jdbc.sql(
                                "SELECT request_hash,result::text FROM knowledge_requests WHERE owner_id=:owner AND"
                                        + " request_key=:key")
                        .param("owner", owner)
                        .param("key", key)
                        .query((r, n) -> Map.entry(r.getString(1), r.getString(2)))
                        .optional();
        if (old.isPresent()) {
            if (!old.get().getKey().equals(hash))
                throw AiStore.conflict("IDEMPOTENCY_CONFLICT", "Import key has different contents");
            return json.readValue(old.get().getValue(), ImportResult.class);
        }
        lock("knowledge-source:" + input.sourceId());
        var existing =
                jdbc.sql("SELECT * FROM knowledge_documents WHERE source_id=:source")
                        .param("source", input.sourceId())
                        .query(this::map)
                        .optional();
        UUID id = existing.map(Document::id).orElseGet(UUID::randomUUID);
        ImportResult result;
        // Reject stale source revisions; importing an identical version is allowed only when its
        // content fingerprint matches.
        if (existing.isPresent() && existing.get().version() >= input.version()) {
            String previous =
                    jdbc.sql("SELECT content_hash FROM knowledge_documents WHERE id=:id")
                            .param("id", id)
                            .query(String.class)
                            .single();
            if (existing.get().version() != input.version() || !previous.equals(hash))
                throw AiStore.conflict(
                        "KNOWLEDGE_VERSION_CONFLICT", "Source version is stale or has changed contents");
            result =
                    jdbc.sql(
                                    "SELECT id,state FROM knowledge_index_jobs WHERE document_id=:id AND"
                                            + " source_version=:version AND generation='current'")
                            .param("id", id)
                            .param("version", input.version())
                            .query(
                                    (r, n) ->
                                            new ImportResult(
                                                    id, input.version(), r.getObject(1, UUID.class), r.getString(2)))
                            .single();
        } else {
            jdbc.sql(
                            "INSERT INTO"
                                    + " knowledge_documents(id,source_id,version,content_hash,title,audience,content,active)"
                                    + " VALUES(:id,:source,:version,:hash,:title,:audience,:content,:active) ON"
                                    + " CONFLICT(id) DO UPDATE SET"
                                    + " version=excluded.version,content_hash=excluded.content_hash,title=excluded.title,audience=excluded.audience,content=excluded.content,active=excluded.active,updated_at=now()")
                    .param("id", id)
                    .param("source", input.sourceId())
                    .param("version", input.version())
                    .param("hash", hash)
                    .param("title", input.title())
                    .param("audience", input.audience())
                    .param("content", input.content())
                    .param("active", input.active())
                    .update();
            int ordinal = 0;
            String heading = input.title();
            var paragraphs = input.content().split("\\n\\s*\\n");
            for (String paragraph : paragraphs) {
                // Chunk whole nonempty paragraphs so evidence boundaries remain readable and
                // reproducible across imports.
                if (paragraph.isBlank())
                    continue;
                String text = paragraph.strip();
                if (text.startsWith("#"))
                    heading = text.replaceFirst("^#+\\s*", "").split("\n")[0];
                for (int start = 0; start < text.length(); start += 1400) {
                    String chunk = text.substring(start, Math.min(text.length(), start + 1400));
                    UUID chunkId =
                            UUID.nameUUIDFromBytes(
                                    (id + ":" + input.version() + ":" + ordinal).getBytes(StandardCharsets.UTF_8));
                    jdbc.sql(
                                    "INSERT INTO"
                                            + " knowledge_chunks(id,document_id,source_version,ordinal,heading,content,content_hash)"
                                            + " VALUES(:id,:document,:version,:ordinal,:heading,:content,:hash)")
                            .param("id", chunkId)
                            .param("document", id)
                            .param("version", input.version())
                            .param("ordinal", ordinal++)
                            .param("heading", heading.substring(0, Math.min(200, heading.length())))
                            .param("content", chunk)
                            .param("hash", RequestHash.sha256(chunk))
                            .update();
                }
            }
            UUID job = UUID.randomUUID();
            jdbc.sql(
                            "INSERT INTO knowledge_index_jobs(id,document_id,source_version)"
                                    + " VALUES(:job,:id,:version)")
                    .param("job", job)
                    .param("id", id)
                    .param("version", input.version())
                    .update();
            outbox.append("KnowledgeChanged", id, correlation, new KnowledgeChanged(id, input.version()));
            result = new ImportResult(id, input.version(), job, "PENDING");
        }
        jdbc.sql(
                        "INSERT INTO knowledge_requests(owner_id,request_key,request_hash,result)"
                                + " VALUES(:owner,:key,:hash,CAST(:result AS jsonb))")
                .param("owner", owner)
                .param("key", key)
                .param("hash", hash)
                .param("result", json.writeValueAsString(result))
                .update();
        return result;
    }

    public Document required(UUID id) {
        return jdbc.sql("SELECT * FROM knowledge_documents WHERE id=:id")
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(AiStore::missing);
    }

    public List<Document> page(UUID after, int size) {
        return jdbc.sql(
                        "SELECT * FROM knowledge_documents WHERE (CAST(:after AS uuid) IS NULL OR"
                                + " id>CAST(:after AS uuid)) ORDER BY id LIMIT :size")
                .param("after", after == null ? null : after.toString())
                .param("size", size)
                .query(this::map)
                .list();
    }

    public List<Chunk> chunks(UUID id, long version) {
        return jdbc.sql(
                        "SELECT * FROM knowledge_chunks WHERE document_id=:id AND source_version=:version ORDER"
                                + " BY ordinal")
                .param("id", id)
                .param("version", version)
                .query(
                        (r, n) ->
                                new Chunk(
                                        r.getObject("id", UUID.class),
                                        id,
                                        version,
                                        r.getInt("ordinal"),
                                        r.getString("heading"),
                                        r.getString("content")))
                .list();
    }

    public int maxOrdinal(UUID id) {
        return jdbc.sql("SELECT coalesce(max(ordinal),0) FROM knowledge_chunks WHERE document_id=:id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    @Transactional
    public void delivered(UUID id, long version) {
        jdbc.sql(
                        "UPDATE knowledge_index_jobs SET ready=true WHERE document_id=:id AND"
                                + " source_version=:version")
                .param("id", id)
                .param("version", version)
                .update();
    }

    public List<IndexJob> pending() {
        return jdbc.sql(
                        "SELECT * FROM knowledge_index_jobs WHERE state='PENDING' AND ready AND"
                                + " next_attempt_at<=now() ORDER BY next_attempt_at LIMIT 10")
                .query(
                        (r, n) ->
                                new IndexJob(
                                        r.getObject("id", UUID.class),
                                        r.getObject("document_id", UUID.class),
                                        r.getLong("source_version"),
                                        r.getString("generation"),
                                        r.getInt("attempts")))
                .list();
    }

    @Transactional
    public void completed(UUID id) {
        jdbc.sql("UPDATE knowledge_index_jobs SET state='SUCCEEDED',last_error=NULL WHERE id=:id")
                .param("id", id)
                .update();
    }

    @Transactional
    public void retry(IndexJob job, Exception error) {
        long seconds = Math.min(60, 1L << Math.min(6, job.attempts()));
        jdbc.sql(
                        "UPDATE knowledge_index_jobs SET attempts=attempts+1,next_attempt_at=now()+(:seconds *"
                                + " interval '1 second'),last_error=:error WHERE id=:id")
                .param("seconds", seconds)
                .param("error", error.getClass().getSimpleName())
                .param("id", job.id())
                .update();
    }

    public JobView job(UUID id) {
        var indexed =
                jdbc.sql("SELECT id,state,last_error FROM knowledge_index_jobs WHERE id=:id")
                        .param("id", id)
                        .query(
                                (r, n) -> new JobView(id, r.getString("state"), null, 0, r.getString("last_error")))
                        .optional();
        return indexed.orElseGet(
                () ->
                        jdbc.sql("SELECT * FROM knowledge_rebuilds WHERE id=:id")
                                .param("id", id)
                                .query(
                                        (r, n) ->
                                                new JobView(
                                                        id,
                                                        r.getString("state"),
                                                        r.getString("index_name"),
                                                        r.getLong("documents"),
                                                        r.getString("error_code")))
                                .optional()
                                .orElseThrow(AiStore::missing));
    }

    @Transactional
    public JobView rebuild(UUID owner, String key) {
        lock("knowledge-rebuild:" + owner + ":" + key);
        var existing =
                jdbc.sql("SELECT id FROM knowledge_rebuilds WHERE owner_id=:owner AND request_key=:key")
                        .param("owner", owner)
                        .param("key", key)
                        .query(UUID.class)
                        .optional();
        if (existing.isPresent())
            return job(existing.get());
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO knowledge_rebuilds(id,owner_id,request_key,index_name)"
                                + " VALUES(:id,:owner,:key,:index)")
                .param("id", id)
                .param("owner", owner)
                .param("key", key)
                .param("index", "cf-ai-knowledge-v1-" + id)
                .update();
        return job(id);
    }

    // Called only after KnowledgeIndexer holds the shared PostgreSQL session advisory lock.
    // Acquiring that lock proves an older rebuild no longer owns projection; a crashed worker's
    // diagnostic lease must not delay recovery for ten minutes.
    public Optional<JobView> pendingRebuild() {
        return jdbc.sql(
                        "SELECT id FROM knowledge_rebuilds WHERE state IN ('PENDING','RUNNING')"
                                + " ORDER BY created_at LIMIT 1")
                .query(UUID.class)
                .optional()
                .map(this::job);
    }

    @Transactional
    public void rebuildState(UUID id, String state, long count, String error) {
        jdbc.sql(
                        "UPDATE knowledge_rebuilds SET"
                                + " state=:state,documents=:count,error_code=:error,lease_until=now()+interval '10"
                                + " minutes' WHERE id=:id")
                .param("state", state)
                .param("count", count)
                .param("error", error)
                .param("id", id)
                .update();
    }

    public boolean permitted(String sourceId, long version, String mode) {
        return jdbc.sql(
                        "SELECT count(*) FROM knowledge_documents WHERE source_id=:source AND"
                                + " version=:version AND active AND effective_at<=now() AND (audience='PUBLIC'"
                                + " OR :mode='SUPPORT')")
                .param("source", sourceId)
                .param("version", version)
                .param("mode", mode)
                .query(Long.class)
                .single()
                == 1;
    }

    @Transactional
    public void embeddingRecorded(UUID document, long version, String model, int dimensions) {
        jdbc.sql(
                        "UPDATE knowledge_chunks SET embedding_model=:model,embedding_dimension=:dimensions"
                                + " WHERE document_id=:id AND source_version=:version")
                .param("model", model)
                .param("dimensions", dimensions)
                .param("id", document)
                .param("version", version)
                .update();
    }
}

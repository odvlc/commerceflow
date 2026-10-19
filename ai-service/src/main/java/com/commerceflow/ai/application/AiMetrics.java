/*
 * Samples durable queue/run state into fixed metric labels so prompts and identities never become
 * telemetry labels.
 */
package com.commerceflow.ai.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-cardinality gauges sourced from durable state; no prompt or identity labels.
 */
@Component
public class AiMetrics {

    private final JdbcClient jdbc;
    private final Map<String, AtomicLong> runs = new LinkedHashMap<>();
    private final AtomicLong pending = new AtomicLong(),
            lag = new AtomicLong(),
            approvals = new AtomicLong(),
            unknown = new AtomicLong();

    public AiMetrics(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        for (String state :
                List.of("QUEUED", "RUNNING", "RETRY_WAIT", "WAITING_CONFIRMATION", "AUTHENTICATION_REQUIRED")) {
            var gauge = new AtomicLong();
            runs.put(state, gauge);
            metrics.gauge("commerceflow.ai.runs",
                    List.of(io.micrometer.core.instrument.Tag.of("state", state)),
                    gauge);
        }
        metrics.gauge("commerceflow.ai.index.pending", pending);
        metrics.gauge("commerceflow.ai.index.lag.seconds", lag);
        metrics.gauge("commerceflow.ai.approvals", approvals);
        metrics.gauge("commerceflow.ai.outcomes.unknown", unknown);
    }

    @Scheduled(fixedDelay = 5000)
    // Sample durable state on a schedule rather than issuing a query for every metrics scrape.
    public void sample() {
        for (var value : runs.entrySet())
            value.getValue()
                    .set(jdbc.sql("SELECT count(*) FROM ai_runs WHERE state=:state")
                            .param("state", value.getKey())
                            .query(Long.class)
                            .single());
        pending.set(jdbc.sql("SELECT count(*) FROM knowledge_index_jobs WHERE state='PENDING'")
                .query(Long.class)
                .single());
        lag.set(jdbc.sql("SELECT COALESCE(extract(epoch FROM now()-min(d.updated_at)),0)::bigint FROM"
                        + " knowledge_index_jobs j JOIN knowledge_documents d ON d.id=j.document_id"
                        + " WHERE j.state='PENDING'")
                .query(Long.class)
                .single());
        approvals.set(jdbc.sql("SELECT count(*) FROM ai_action_proposals WHERE confirmed_at IS NOT NULL")
                .query(Long.class)
                .single());
        unknown.set(jdbc.sql("SELECT count(*) FROM ai_action_executions WHERE state='OUTCOME_UNKNOWN'")
                .query(Long.class)
                .single());
    }
}

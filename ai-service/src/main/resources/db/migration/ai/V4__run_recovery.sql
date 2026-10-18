ALTER TABLE ai_runs ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE ai_runs ADD COLUMN transient_attempts integer NOT NULL DEFAULT 0 CHECK(transient_attempts BETWEEN 0 AND 2);
CREATE INDEX ai_run_recovery ON ai_runs(next_attempt_at) WHERE state IN ('QUEUED','RETRY_WAIT');
ALTER TABLE ai_runs ADD COLUMN accounted_at timestamptz;

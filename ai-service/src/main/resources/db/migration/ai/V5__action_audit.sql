ALTER TABLE ai_action_proposals
    ADD COLUMN preview jsonb NOT NULL DEFAULT '{}';
ALTER TABLE ai_action_executions
    ADD CONSTRAINT ai_execution_attempts CHECK (attempts BETWEEN 0 AND 3);
CREATE INDEX ai_proposals_expiry ON ai_action_proposals (expires_at) WHERE state='PROPOSED';

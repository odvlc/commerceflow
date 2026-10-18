CREATE TABLE ai_conversations (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL, mode varchar(16) NOT NULL CHECK(mode IN ('CUSTOMER','SUPPORT')),
 request_key varchar(128) NOT NULL, version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(owner_id,request_key));
CREATE INDEX ai_conversations_owner ON ai_conversations(owner_id,updated_at DESC,id);
CREATE TABLE ai_runs (
 id uuid PRIMARY KEY, conversation_id uuid NOT NULL REFERENCES ai_conversations(id), owner_id uuid NOT NULL,
 request_key varchar(128) NOT NULL, request_hash varchar(64) NOT NULL,
 state varchar(40) NOT NULL CHECK(state IN ('QUEUED','RUNNING','WAITING_CONFIRMATION','AUTHENTICATION_REQUIRED','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED')),
 prompt_version varchar(64) NOT NULL DEFAULT 'copilot-v1', model_name varchar(120) NOT NULL,
 fence bigint NOT NULL DEFAULT 0, lease_until timestamptz, error_code varchar(80),
 active_millis bigint NOT NULL DEFAULT 0 CHECK(active_millis>=0), rounds integer NOT NULL DEFAULT 0 CHECK(rounds>=0),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(owner_id,request_key));
CREATE UNIQUE INDEX ai_one_active_run ON ai_runs(conversation_id) WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED');
CREATE INDEX ai_runs_claim ON ai_runs(state,created_at);
CREATE TABLE ai_messages (
 id uuid PRIMARY KEY, conversation_id uuid NOT NULL REFERENCES ai_conversations(id), run_id uuid NOT NULL REFERENCES ai_runs(id),
 sequence bigint GENERATED ALWAYS AS IDENTITY, role varchar(16) NOT NULL CHECK(role IN ('USER','ASSISTANT')),
 content text NOT NULL CHECK(length(content)<=16000), citations jsonb NOT NULL DEFAULT '[]', created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(conversation_id,sequence));
CREATE TABLE ai_run_steps (
 id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES ai_runs(id), sequence bigint GENERATED ALWAYS AS IDENTITY,
 kind varchar(24) NOT NULL, name varchar(80) NOT NULL, request_hash varchar(64) NOT NULL,
 input jsonb NOT NULL, result jsonb, state varchar(24) NOT NULL CHECK(state IN ('PENDING','SUCCEEDED','FAILED')),
 attempts integer NOT NULL DEFAULT 1 CHECK(attempts>0), duration_millis bigint, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ai_steps_run ON ai_run_steps(run_id,sequence);
CREATE TABLE ai_run_events (
 run_id uuid NOT NULL REFERENCES ai_runs(id), sequence bigint GENERATED ALWAYS AS IDENTITY, type varchar(40) NOT NULL,
 payload jsonb NOT NULL CHECK(octet_length(payload::text)<=32000), created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(run_id,sequence));
CREATE TABLE ai_action_proposals (
 id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES ai_runs(id), owner_id uuid NOT NULL, operation varchar(16) NOT NULL CHECK(operation IN ('ADD','SET','DELETE')),
 product_id uuid NOT NULL, quantity integer NOT NULL CHECK(quantity BETWEEN 0 AND 1000), arguments_hash varchar(64) NOT NULL,
 cart_revision varchar(100) NOT NULL, state varchar(24) NOT NULL CHECK(state IN ('PROPOSED','CONFIRMED','REJECTED','EXPIRED','SUCCEEDED','FAILED','OUTCOME_UNKNOWN')),
 version bigint NOT NULL DEFAULT 0, expires_at timestamptz NOT NULL, confirmed_at timestamptz, UNIQUE(run_id));
CREATE TABLE ai_action_executions (
 id uuid PRIMARY KEY, proposal_id uuid NOT NULL UNIQUE REFERENCES ai_action_proposals(id), request_key varchar(128) NOT NULL UNIQUE,
 request_hash varchar(64) NOT NULL, state varchar(24) NOT NULL CHECK(state IN ('PENDING','SUCCEEDED','FAILED','OUTCOME_UNKNOWN')),
 attempts integer NOT NULL DEFAULT 0, result jsonb, error_code varchar(80), recovery_until timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE knowledge_documents (
 id uuid PRIMARY KEY, source_id varchar(100) NOT NULL UNIQUE, version bigint NOT NULL CHECK(version>0), content_hash varchar(64) NOT NULL,
 title varchar(200) NOT NULL, audience varchar(16) NOT NULL CHECK(audience IN ('PUBLIC','SUPPORT')),
 content text NOT NULL CHECK(length(content)<=100000), active boolean NOT NULL DEFAULT true,
 effective_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE knowledge_chunks (
 id uuid PRIMARY KEY, document_id uuid NOT NULL REFERENCES knowledge_documents(id), source_version bigint NOT NULL,
 ordinal integer NOT NULL, heading varchar(200) NOT NULL, content text NOT NULL CHECK(length(content)<=6000), content_hash varchar(64) NOT NULL,
 embedding_model varchar(120), embedding_dimension integer, UNIQUE(document_id,source_version,ordinal));
CREATE TABLE knowledge_index_jobs (
 id uuid PRIMARY KEY, document_id uuid NOT NULL REFERENCES knowledge_documents(id), source_version bigint NOT NULL,
 generation varchar(120) NOT NULL DEFAULT 'current', state varchar(24) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','SUCCEEDED','FAILED')),
 attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(), last_error varchar(80),
 UNIQUE(document_id,source_version,generation));
CREATE TABLE outbox_events(id uuid PRIMARY KEY,aggregate_id uuid NOT NULL,event_type varchar(100) NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL,published_at timestamptz);
CREATE TABLE processed_events(event_id uuid PRIMARY KEY,processed_at timestamptz NOT NULL DEFAULT now());

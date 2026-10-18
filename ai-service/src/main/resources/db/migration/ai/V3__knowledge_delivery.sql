ALTER TABLE knowledge_index_jobs
    ADD COLUMN ready boolean NOT NULL DEFAULT false;
CREATE INDEX knowledge_jobs_pending ON knowledge_index_jobs (next_attempt_at) WHERE state='PENDING' AND ready;
CREATE TABLE knowledge_requests
(
    owner_id     uuid         NOT NULL,
    request_key  varchar(128) NOT NULL,
    request_hash varchar(64)  NOT NULL,
    result       jsonb        NOT NULL,
    PRIMARY KEY (owner_id, request_key)
);
CREATE TABLE knowledge_rebuilds
(
    id          uuid PRIMARY KEY,
    owner_id    uuid         NOT NULL,
    request_key varchar(128) NOT NULL,
    index_name  varchar(120) NOT NULL,
    state       varchar(24)  NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    lease_until timestamptz,
    documents   bigint       NOT NULL DEFAULT 0,
    error_code  varchar(80),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (owner_id, request_key)
);

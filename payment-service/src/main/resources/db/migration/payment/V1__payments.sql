CREATE TABLE payments (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    request_hash varchar(64),
    amount numeric(19,2) CHECK (amount >= 0),
    currency varchar(3) CHECK (currency ~ '^[A-Z]{3}$'),
    status varchar(20) NOT NULL CHECK (status IN ('PENDING','AUTHORIZED','DECLINED','CANCELLED','REFUNDED')),
    scenario varchar(20),
    due_at timestamptz,
    correlation_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (request_hash IS NULL OR (amount IS NOT NULL AND currency IS NOT NULL AND scenario IS NOT NULL))
);
CREATE INDEX payments_due_idx ON payments(due_at) WHERE status='PENDING' AND scenario='DELAY';
CREATE TABLE payment_attempts (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payments(id),
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    result varchar(20) NOT NULL,
    failure_reason varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(payment_id,attempt_number)
);
CREATE TABLE outbox_events (
    id uuid PRIMARY KEY, aggregate_id uuid NOT NULL, event_type varchar(100) NOT NULL,
    payload jsonb NOT NULL, created_at timestamptz NOT NULL, published_at timestamptz,
    sequence bigint GENERATED ALWAYS AS IDENTITY,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(500)
);
CREATE INDEX outbox_pending_idx ON outbox_events(created_at,id) WHERE published_at IS NULL;
CREATE INDEX outbox_sequence_idx ON outbox_events(aggregate_id,sequence) WHERE published_at IS NULL;
CREATE TABLE processed_events (event_id uuid PRIMARY KEY, processed_at timestamptz NOT NULL DEFAULT now());

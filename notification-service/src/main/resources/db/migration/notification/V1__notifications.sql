CREATE TABLE notifications
(
    id          uuid PRIMARY KEY,
    event_id    uuid         NOT NULL UNIQUE,
    customer_id uuid         NOT NULL,
    order_id    uuid         NOT NULL,
    status      varchar(40)  NOT NULL,
    message     varchar(250) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (order_id, status)
);
CREATE INDEX notifications_customer_idx ON notifications (customer_id, created_at DESC, id);
CREATE TABLE processed_events
(
    event_id     uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE outbox_events
(
    id              uuid PRIMARY KEY,
    aggregate_id    uuid         NOT NULL,
    event_type      varchar(100) NOT NULL,
    payload         jsonb        NOT NULL,
    created_at      timestamptz  NOT NULL,
    published_at    timestamptz,
    sequence        bigint GENERATED ALWAYS AS IDENTITY,
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    last_error      varchar(500)
);
CREATE INDEX outbox_pending_idx ON outbox_events (created_at, id) WHERE published_at IS NULL;
CREATE INDEX outbox_sequence_idx ON outbox_events (aggregate_id, sequence) WHERE published_at IS NULL;

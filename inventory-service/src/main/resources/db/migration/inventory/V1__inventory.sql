CREATE TABLE inventory
(
    product_id         uuid PRIMARY KEY,
    available_quantity integer NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity  integer NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    version            bigint  NOT NULL DEFAULT 0
);
CREATE TABLE reservation_orders
(
    order_id           uuid PRIMARY KEY,
    request_hash       varchar(64),
    status             varchar(20)  NOT NULL CHECK (status IN ('RESERVED', 'REJECTED', 'COMMITTED', 'RELEASED')),
    expires_at         timestamptz  NOT NULL,
    expiry_notified_at timestamptz,
    correlation_id     varchar(128) NOT NULL
);
CREATE TABLE reservations
(
    id         uuid PRIMARY KEY,
    order_id   uuid        NOT NULL REFERENCES reservation_orders (order_id),
    product_id uuid        NOT NULL REFERENCES inventory (product_id),
    quantity   integer     NOT NULL CHECK (quantity > 0),
    status     varchar(20) NOT NULL CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED')),
    expires_at timestamptz NOT NULL,
    UNIQUE (order_id, product_id)
);
CREATE INDEX reservations_expiring_idx ON reservation_orders (expires_at) WHERE status = 'RESERVED' AND expiry_notified_at IS NULL;
CREATE TABLE outbox_events
(
    id           uuid PRIMARY KEY,
    aggregate_id uuid         NOT NULL,
    event_type   varchar(100) NOT NULL,
    payload      jsonb        NOT NULL,
    created_at   timestamptz  NOT NULL,
    published_at timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox_events (created_at, id) WHERE published_at IS NULL;
CREATE TABLE processed_events
(
    event_id     uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);

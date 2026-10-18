CREATE TABLE orders (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    status varchar(40) NOT NULL CHECK (status IN ('PENDING_INVENTORY','AWAITING_PAYMENT','COMMITTING_INVENTORY','CANCELLING_PAYMENT','CANCELLING_INVENTORY','CONFIRMED','CANCELLED')),
    total_amount numeric(19,2) NOT NULL CHECK (total_amount >= 0),
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    payment_scenario varchar(20) NOT NULL CHECK (payment_scenario IN ('APPROVE','DECLINE','TIMEOUT','DELAY')),
    correlation_id varchar(128) NOT NULL,
    deadline_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(customer_id,idempotency_key)
);
CREATE INDEX orders_customer_created_idx ON orders(customer_id,created_at DESC,id);
CREATE INDEX orders_deadline_idx ON orders(deadline_at) WHERE status NOT IN ('CONFIRMED','CANCELLED');
CREATE TABLE order_items (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    product_id uuid NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0 AND quantity <= 1000),
    unit_price numeric(19,2) NOT NULL CHECK (unit_price >= 0),
    UNIQUE(order_id,product_id)
);
CREATE TABLE order_saga_steps (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    step varchar(60) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    attempts integer NOT NULL DEFAULT 1 CHECK (attempts > 0),
    last_error text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(order_id,step)
);
CREATE TABLE outbox_events (
    id uuid PRIMARY KEY, aggregate_id uuid NOT NULL, event_type varchar(100) NOT NULL,
    payload jsonb NOT NULL, created_at timestamptz NOT NULL, published_at timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox_events(created_at,id) WHERE published_at IS NULL;
CREATE TABLE processed_events (event_id uuid PRIMARY KEY, processed_at timestamptz NOT NULL DEFAULT now());

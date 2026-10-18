CREATE TABLE categories (
    name varchar(80) PRIMARY KEY,
    CHECK (length(trim(name)) > 0)
);

CREATE TABLE products (
    id uuid PRIMARY KEY,
    sku varchar(64) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    description varchar(4000) NOT NULL,
    category varchar(80) NOT NULL REFERENCES categories(name),
    price numeric(19,2) NOT NULL CHECK (price >= 0),
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    active boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    idempotency_key varchar(128) NOT NULL UNIQUE,
    request_hash varchar(64) NOT NULL,
    CHECK (length(trim(sku)) > 0),
    CHECK (length(trim(name)) > 0)
);
CREATE INDEX products_active_created_idx ON products(active, created_at DESC, id);
CREATE INDEX products_category_idx ON products(category) WHERE active;

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox_events(created_at, id) WHERE published_at IS NULL;

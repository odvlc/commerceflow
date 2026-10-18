# Owned data models

| Owner | Tables / keys / integrity |
|---|---|
| Catalog | `products` UUID PK; unique SKU and idempotency key; price numeric(19,2), currency, active, JPA version and UTC timestamps. `categories` name PK/FK. `outbox_events`. |
| Order | `orders` UUID PK; unique `(customer_id,idempotency_key)` plus canonical request hash, status, amount/currency, scenario, deadline and version. `order_items` FK order, unique order/product, positive quantity. `order_saga_steps` unique order/step, durable status/attempts/error. Outbox and processed events. |
| Inventory | `inventory` product PK with nonnegative available/reserved quantities and version. `reservation_orders` order PK with request hash, status, expiry/correlation and notification marker; cancellation tombstones may precede reserve commands. `reservations` unique order/product, positive quantity and owned FKs. Outbox and processed events. |
| Payment | `payments` UUID PK and unique order; request hash, amount/currency/status/scenario/due time/correlation. `payment_attempts` unique payment/attempt. A cancel-before-authorize tombstone has no amount because no authorization was attempted. Outbox and processed events. |
| Notification | `notifications` unique event and unique order/status, customer ownership, local message/UTC timestamp. Outbox and processed events. |
| Cart | Redis JSON items/version plus same-user request memo hash. Lua validates limits before writing both keys atomically. TTL defaults to 24h; at most 100 products, quantity 1000 and 1000 request memos per live cart. |

Outbox: UUID event/aggregate IDs, type, envelope JSONB, UTC creation/publication time, monotonically generated local sequence, attempts, retry deadline and error class. The predecessor index prevents out-of-order publication for an aggregate, including across publisher instances. Consumer markers use an event UUID primary key and commit with business effects.

Flyway migrations are service-local under `db/migration/<owner>`. Hibernate validates the catalog schema instead of generating it. JDBC implements explicit stock arithmetic and saga locking within Spring transactions. No foreign-service SQL is used. Inventory locks products in a deterministic order, then reserves with a conditional update guarded by available quantity. Reservation commitment/release conserves accounting; committed stock cannot be released by the compensation operation.

Kafka messages and database timestamps use UTC `Instant`; PostgreSQL `timestamptz` stores instants independent of display timezone. Currency and money stay explicit. Search stores integer minor units and PostgreSQL source versions; it keeps inactive documents as version tombstones.

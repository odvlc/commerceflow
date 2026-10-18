# Kafka contracts and delivery

`contracts` contains wire DTOs only. Envelope v1 fields: `eventId` (UUID), `eventType`, `schemaVersion` (1),
`aggregateId` (UUID), `correlationId`, `occurredAt` (UTC ISO instant), and typed `payload`. Kafka keys must equal
aggregate IDs. Order workflows use order IDs; catalog uses product IDs.

| Topic                        | Event types                                                                                              | Consumers                                    |
|------------------------------|----------------------------------------------------------------------------------------------------------|----------------------------------------------|
| commerceflow.order.v1        | ReserveInventory, CommitInventory, ReleaseInventory; AuthorizePayment, CancelPayment; OrderStatusChanged | Inventory, payment and notification          |
| commerceflow.inventory.v1    | InventoryResult, InventoryExpiring                                                                       | Order saga coordinator                       |
| commerceflow.payment.v1      | PaymentResult                                                                                            | Order saga coordinator                       |
| commerceflow.catalog.v1      | ProductChanged                                                                                           | Catalog search indexer and cache invalidator |
| commerceflow.notification.v1 | NotificationCreated                                                                                      | Durable notification audit events            |
| commerceflow.dead-letter.v1  | Original poison record plus Spring Kafka exception/source headers                                        | Operator recovery                            |

Exact payload fields are records in `contracts/.../CheckoutEvents.java`. Inventory results carry RESERVED, REJECTED,
COMMITTED or RELEASED. Wire changes that break old consumers require a new schema/topic version.

Outbox rows commit with business writes. A publisher locks ready rows using `FOR UPDATE SKIP LOCKED`; an unpublished
predecessor blocks later events for that aggregate. It waits for `acks=all` before recording publication. A crash
between acknowledgment and database commit deliberately causes duplicates. Producer idempotence does not remove the need
for application deduplication across restarts.

Consumers insert `processed_events` and apply business effects in one local transaction, then acknowledge Kafka.
Duplicate IDs cause no business effect. Transient listener failures receive five retries with exponential backoff;
persistent or malformed records go to the dead-letter topic, with acknowledgment only after successful dead-letter
delivery. Outbox failures retry indefinitely with backoff capped at 60 seconds; events are never silently discarded.

The local broker uses three partitions per topic and replication factor one. This is development durability, not broker
high availability. Kafka/PostgreSQL never share an atomic transaction.

## AI knowledge events

`commerceflow.ai-knowledge.v1` carries `KnowledgeChanged` versioned wire DTOs from AI's local transactional outbox.
Knowledge versions/chunks/import intent and the outbox row commit together. The consumer deduplicates its ready-job
marker in the AI database, then the index worker acknowledges external-version Elasticsearch writes before marking
projection complete. A process crash may replay those writes. Rebuild/live projection share a PostgreSQL session
advisory lock; acquiring it permits recovery of an abandoned RUNNING rebuild without its diagnostic lease delay.
No database transaction spans inference/search HTTP, and no PostgreSQL/Kafka atomic commit is claimed.

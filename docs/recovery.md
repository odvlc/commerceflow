# Failure recovery for the implemented slice

**Kafka down:** business transactions can still commit outbox intent. Inspect `SELECT id,event_type,attempts,last_error,next_attempt_at FROM outbox_events WHERE published_at IS NULL ORDER BY sequence` using the owning service's connection. Restore Kafka, check listener health and wait for capped backoff. `published_at` is set only after broker acknowledgment. Never delete pending outbox rows to clear an alert.

**Poison record:** read `commerceflow.dead-letter.v1` with a dedicated diagnostic consumer group. Inspect original topic/partition/offset and exception headers. Fix the producer/consumer defect, then replay the original value with its original key and event ID to its original topic. Do not replay blindly: a poison event can stall an order requiring investigation. Replay tooling and an executed operator demonstration remain Phase 13.

**Service crash after a business commit:** restart against the same database. Pending outbox rows are rediscovered. If a message had been applied before its offset was committed, the database marker suppresses replay. Never truncate `processed_events` during ordinary recovery.

**Inventory expiry:** expiry emits reconciliation intent. It never releases stock while payment could still authorize. The order orchestrator must obtain a terminal cancelled/refunded payment outcome before releasing an uncertain payment's reservation. Commitment cannot be reversed using the release operation.

**PostgreSQL unavailable:** reject authoritative mutations; do not substitute Redis, local maps or another service's database. Restore the volume/connection and allow Flyway validation to run. Back up before schema or major-version upgrades. Do not use `docker compose down -v` as a recovery step.

**Local infrastructure:** `docker compose -f infra/compose.yaml --profile full ps`, `logs --tail=100 SERVICE`, and health probes diagnose startup. Host Kafka clients use `localhost:9092`; Compose clients use `kafka:29092`. PostgreSQL 18 persists at `/var/lib/postgresql`, not the older `/var/lib/postgresql/data` mount.

Container/Kubernetes restart demonstrations have not run because the current machine has no container daemon or kind.

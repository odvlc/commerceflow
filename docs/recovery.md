# Failure recovery for the implemented slice

<!--
Maintenance: Explains replay, reconciliation and compensation after interruption while preserving
authoritative state.
-->

Kafka down: business transactions can still commit outbox intent. Inspect
`SELECT id,event_type,attempts,last_error,next_attempt_at FROM outbox_events WHERE published_at IS NULL ORDER BY sequence`
using the owning service's connection. Restore Kafka, check listener health and wait for capped
backoff. `published_at` is set only after broker acknowledgment. Never delete pending outbox rows to
clear an alert.

Poison record: read `commerceflow.dead-letter.v1` with a dedicated diagnostic consumer group.
Inspect original topic/partition/offset and exception headers. Fix the producer/consumer defect,
then replay the original value with its original key and event ID to its original topic. Do not
replay blindly: a poison event can stall an order requiring investigation. Replay tooling and an
executed operator demonstration remain Phase 13.

Service crash after a business commit: restart against the same database. Pending outbox rows are
rediscovered. If a message had been applied before its offset was committed, the database marker
suppresses replay. Never truncate `processed_events` during ordinary recovery.

Inventory expiry: expiry requests reconciliation. It does not release stock while payment could
still authorize. The order orchestrator must obtain a terminal cancelled/refunded payment outcome
before releasing an uncertain payment's reservation. Commitment cannot be reversed using the release
operation.

PostgreSQL unavailable: reject authoritative mutations; do not substitute Redis, local maps or
another service's database. Restore the volume/connection and allow Flyway validation to run. Back
up before schema or major-version upgrades. Do not use `docker compose down -v` as a recovery step.

Local infrastructure: `docker compose -f infra/compose.yaml --profile full ps`,
`logs --tail=100 SERVICE`, and health probes diagnose startup. Host Kafka clients use
`localhost:9092`; Compose clients use `kafka:29092`. PostgreSQL 18 persists at
`/var/lib/postgresql`, not the older `/var/lib/postgresql/data` mount.

At this point in verification, container/Kubernetes restart demonstrations had not run because the
machine had no container daemon or kind.

## Executed AI/deployment supplement (2026-10-18)

Docker and kind became available after the baseline described above. Full Compose host-model and
native-model kind browser workflows now pass. Actual SIGKILL/pod replacement tests verify waiting
proposals, fresh-authentication resume and replay of a real Cart acknowledgment lost before AI
recorded it, with the same execution/key and one business effect.
`python3 scripts/ai-recovery-smoke.py --target=compose|kind` runs those local synthetic checks and
restores endpoints.

`python3 scripts/kind-durability.py` verifies Order/PostgreSQL/Redis PVC state and interruption
during AI index rebuild. A PostgreSQL restart may temporarily invalidate Keycloak pool connections.
Wait for a successful PKCE exchange; TCP connectivity or realm metadata alone does not establish
readiness. Rebuild/live indexing are mutually exclusive under a session advisory lock; a dead
worker's lock is released by PostgreSQL and the new worker safely replays its retained generation.
Never reset a database, change an execution key or delete volumes to clear pending work. See
[AI operations](ai-operations.md) and the dated results in [AI_PLAN.md](../AI_PLAN.md) for the exact
completed checks and remaining CPU/hardware/review limits.

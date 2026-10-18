# Executed verification

Date: 2026-10-17, ARM64 macOS, Java 25.0.4.1. This document records observed results, not future CI/deployment claims.

| Check | Observed result |
|---|---|
| Final Maven `verify dependency:build-classpath` reactor | PASS: 43 tests, zero failures/errors/skips; all applications packaged. |
| Contracts | 2 tests: identifiable versioned envelopes and invalid schema/correlation rejection. |
| Catalog | 5 real PostgreSQL/HTTP tests: CRUD/OpenAPI, 20 concurrent idempotent creates, transactional rollback, optimistic version/deactivation and validation. |
| Inventory | 6 real PostgreSQL tests: 100 reservations/stock 10 → exactly 10 success; replay/commit/release; multi-item rejection/tombstone; expiry intent; security; concurrent mixed-prefix UUID reserve/release conservation. |
| Kafka delivery | 5 real KRaft/PostgreSQL tests: duplicate event causes one effect, failed effect rolls back marker, unreachable broker retains/replays ordered outbox rows, poison record dead-letters, actual broker lifecycle restart retains its log and recovers a pending outbox commitment without repeated reservation. |
| Order | 7 tests: trusted-price boundary, concurrent idempotency, ownership/admin roles, upstream failure rollback, cancellation intent, validation/legal states. |
| Payment | 3 real PostgreSQL tests: 20 concurrent authorizations and one refund attempt, cancel-before-authorize, 20 cancel/settle races ending cancelled or refunded. |
| Full workflow | 12 tests: real HTTP applications, independently owned databases, Kafka, Redis and Elasticsearch; success/decline/stock rejection, timeout, delayed result, order-process restart, JWT rejection, atomic cart, cache, search/rebuild/outages, durable notification ownership/deduplication. |
| Real Keycloak | 3 tests: login form + Authorization Code/PKCE exchange, gateway/backend roles and ownership, service client scope, disabled password grant/missing-PKCE rejection. |
| Frontend lint / TypeScript / Vite build | PASS. |
| Frontend unit tests | PASS: 2 tests, duplicate submission guard and retained/replaced checkout keys. |
| Chrome end-to-end | PASS: 4 cases covering customer purchase/decline/notifications/session refresh, real search/mobile fit, admin product/stock management and SUPPORT monitoring. Both thread modes were exercised. |
| Native five-minute demo | PASS: browser cases plus API verification of exact approved/declined stock, cached reads, authenticated metric snapshots and dashboard provisioning. |
| Prometheus/Grafana | PASS: seven authenticated targets up, four promtool-valid rules, two provisioned dashboards, datasource health OK and real outcome series queried through Grafana. |
| k6 | PASS: two 15-second catalog baselines (10 users), plus 4 approved and 4 declined authenticated checkout iterations (2 users); no failed HTTP requests/checks; actual results/raw JSON retained in `docs/load-results`. |
| Full Compose configuration | PASS with standalone Compose 5.6.0. Health-check Java program compiled and succeeded against a live readiness endpoint. |
| Kubernetes rendering/schema | PASS: Kustomize 5.8.3 and kubeconform 0.8.0; 61 valid resources, zero invalid/errors/skips. |
| Workflow validation | PASS with actionlint 1.7.12 (embedded shellcheck not installed); shell scripts separately pass `bash -n`. YAML syntax checked after quoting flow-list volume/target scalars for older parsers. |

Final Maven command:

```bash
./mvnw -B verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test
```

Native demo command, with native applications/monitoring already running:

```bash
SERVICE_CLIENT_SECRET=local-test-service-only bash scripts/demo.sh
```

## Explicitly unverified

- Docker image builds/pulls, complete container startup and container restart persistence: no Docker daemon/CLI in this environment. A standalone Compose configuration validator does not substitute for image execution.
- kind deployment, CNI enforcement, HPAs, ingress behavior and Kubernetes restart/PVC recovery: Docker, kind and kubectl are absent. Schema validation does not prove these runtime properties.
- Kafka container/OS-process restart with the deployment volume: the native test stopped/restarted the real broker server over its retained log directories inside the test JVM. It did not restart the controller, JVM or container.
- GitHub Actions execution: definitions validated locally; no Git repository/remote was initially configured and no remote workflow was triggered.
- Testcontainers fallback execution: native real servers were used here; Docker fallback code compiled but did not execute.
- Production capacity, high availability, long-duration saturation, peak memory/pool occupancy and virtual-thread population profiling. The recorded short native runs make no such claims.

Maven reports remain under each module's ignored `target/surefire-reports`. Execution logs, browser screenshot/traces, scrape evidence and local monitoring data remain under ignored `.cache/verification` and `frontend/test-results`. Synthetic test infrastructure is stopped after verification; reproduce it with the documented scripts.

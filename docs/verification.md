# Executed verification

<!--
Maintenance: Retains actual verification commands, results and limitations; keep unexecuted
deployment gates explicit.
-->

Date: 2026-10-17, ARM64 macOS, Java 25.0.4.1. This document records observed results, not future CI/deployment claims.

| Check                                                   | Observed result                                                                                                                                                                                                                                                                                               |
|---------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Final Maven `verify dependency:build-classpath` reactor | PASS: 43 tests, zero failures/errors/skips; all applications packaged.                                                                                                                                                                                                                                        |
| Contracts                                               | 2 tests: identifiable versioned envelopes and invalid schema/correlation rejection.                                                                                                                                                                                                                           |
| Catalog                                                 | 5 real PostgreSQL/HTTP tests: CRUD/OpenAPI, 20 concurrent idempotent creates, transactional rollback, optimistic version/deactivation and validation.                                                                                                                                                         |
| Inventory                                               | 6 real PostgreSQL tests: 100 reservations/stock 10 → exactly 10 success; replay/commit/release; multi-item rejection/tombstone; expiry intent; security; concurrent mixed-prefix UUID reserve/release conservation.                                                                                           |
| Kafka delivery                                          | 5 real KRaft/PostgreSQL tests: duplicate event causes one effect, failed effect rolls back marker, unreachable broker retains/replays ordered outbox rows, poison record dead-letters, actual broker lifecycle restart retains its log and recovers a pending outbox commitment without repeated reservation. |
| Order                                                   | 7 tests: trusted-price boundary, concurrent idempotency, ownership/admin roles, upstream failure rollback, cancellation intent, validation/legal states.                                                                                                                                                      |
| Payment                                                 | 3 real PostgreSQL tests: 20 concurrent authorizations and one refund attempt, cancel-before-authorize, 20 cancel/settle races ending cancelled or refunded.                                                                                                                                                   |
| Full workflow                                           | 12 tests: real HTTP applications, independently owned databases, Kafka, Redis and Elasticsearch; success/decline/stock rejection, timeout, delayed result, order-process restart, JWT rejection, atomic cart, cache, search/rebuild/outages, durable notification ownership/deduplication.                    |
| Real Keycloak                                           | 3 tests: login form + Authorization Code/PKCE exchange, gateway/backend roles and ownership, service client scope, disabled password grant/missing-PKCE rejection.                                                                                                                                            |
| Frontend lint / TypeScript / Vite build                 | PASS.                                                                                                                                                                                                                                                                                                         |
| Frontend unit tests                                     | PASS: 2 tests, duplicate submission guard and retained/replaced checkout keys.                                                                                                                                                                                                                                |
| Chrome end-to-end                                       | PASS: 4 cases covering customer purchase/decline/notifications/session refresh, real search/mobile fit, admin product/stock management and SUPPORT monitoring. Both thread modes were exercised.                                                                                                              |
| Native five-minute demo                                 | PASS: browser cases plus API verification of exact approved/declined stock, cached reads, authenticated metric snapshots and dashboard provisioning.                                                                                                                                                          |
| Prometheus/Grafana                                      | PASS: seven authenticated targets up, four promtool-valid rules, two provisioned dashboards, datasource health OK and real outcome series queried through Grafana.                                                                                                                                            |
| k6                                                      | PASS: two 15-second catalog baselines (10 users), plus 4 approved and 4 declined authenticated checkout iterations (2 users); no failed HTTP requests/checks; actual results/raw JSON retained in `docs/load-results`.                                                                                        |
| Full Compose configuration                              | PASS with standalone Compose 5.6.0. Health-check Java program compiled and succeeded against a live readiness endpoint.                                                                                                                                                                                       |
| Kubernetes rendering/schema                             | PASS: Kustomize 5.8.3 and kubeconform 0.8.0; 61 valid resources, zero invalid/errors/skips.                                                                                                                                                                                                                   |
| Workflow validation                                     | PASS with actionlint 1.7.12 (embedded shellcheck not installed); shell scripts separately pass `bash -n`. YAML syntax checked after quoting flow-list volume/target scalars for older parsers.                                                                                                                |

Final Maven command:

```bash
./mvnw -B verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test
```

Native demo command, with native applications/monitoring already running:

```bash
SERVICE_CLIENT_SECRET=local-test-service-only bash scripts/demo.sh
```

## Explicitly unverified

- Docker image builds/pulls, complete container startup and container restart persistence: no Docker daemon/CLI in this
  environment. A standalone Compose configuration validator does not substitute for image execution.
- kind deployment, CNI enforcement, HPAs, ingress behavior and Kubernetes restart/PVC recovery: Docker, kind and kubectl
  are absent. Schema validation does not prove these runtime properties.
- Kafka container/OS-process restart with the deployment volume: the native test stopped/restarted the real broker
  server over its retained log directories inside the test JVM. It did not restart the controller, JVM or container.
- GitHub Actions execution: definitions validated locally; no Git repository/remote was initially configured and no
  remote workflow was triggered.
- Testcontainers fallback execution: native real servers were used here; Docker fallback code compiled but did not
  execute.
- Production capacity, high availability, long-duration saturation, peak memory/pool occupancy and virtual-thread
  population profiling. The recorded short native runs make no such claims.

Maven reports remain under each module's ignored `target/surefire-reports`. Execution logs, browser screenshot/traces,
scrape evidence and local monitoring data remain under ignored `.cache/verification` and `frontend/test-results`.
Synthetic test infrastructure is stopped after verification; reproduce it with the documented scripts.

## AI and deployment supplement — 2026-10-18

The original absent-tool/deployment statements above describe the 2026-10-17 baseline. Docker/kind/Ollama are now
available and the following commands executed successfully on the 48 GiB M5 Pro host, Docker VM 7.75 GiB:

| Check                                                             | Actual result                                                                                                                                                                          |
|-------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `./mvnw -B -pl workflow-tests -am -Pai-real-model verify` (final) | 82 tests, no failures/errors/skips; 30 AI core, 5 actual-model tests plus all business/security suites                                                                                 |
| Frozen `-Pai-real-model,ai-evaluation` candidate v3               | 240/240 procedural cases; 60/60 semantic recall/expected citations; 120/120 positive procedural checks; zero observed unsafe effects                                                   |
| Frontend lint/unit/build                                          | PASS, 5 unit tests; actual compiled storefront served by nginx/Ingress                                                                                                                 |
| Full commerce + Copilot Chrome suite                              | Compose host mode 8/8 (41.7 s); kind built Ingress 8/8 (42.7 s)                                                                                                                        |
| Updated ready-stack AI demo                                       | Four AI browser cases include distinct-product comparison, then four actual crash/outage cases and fresh post-restart Prometheus samples; PASS                                         |
| Compose                                                           | Nine nonroot application images build; 16-service host-mode health, explicit off transition, additive database provisioning and actual lost-ack process recovery pass                  |
| CPU Docker                                                        | Both pinned digests, actual CPU generation/embedding warmup and retained-model-volume restart pass; full CPU/business co-location not certified                                        |
| kind policy                                                       | Calico 3.33.0 installed; 12 actual allow/deny probes pass, including forbidden payment/inventory/notification egress and forbidden Catalog-to-AI ingress                               |
| kind durable recovery                                             | Waiting proposals, physical container termination/read resume, same-key lost Cart acknowledgment while inference unavailable, Order/PG/Redis PVC and interrupted rebuild recovery pass |
| Monitoring                                                        | All three provisioned Grafana dashboards and eight authenticated Prometheus targets queried live through Grafana; first-text-token metric sampled after new post-restart inference     |
| Source/deployment validators                                      | All shell/Python syntax, actionlint, three Compose modes and strict base/AI/Job/policy Kubernetes schemas pass; `git diff --check` passes                                              |

`docs/ai-results/` retains safe candidate-specific JSON, capacity events, recovery/monitoring evidence and an explicit
source-review note. Final API Cart-resume admission, rebuild session-lock recovery and integer-overflow rejection have
separate final regression/operational evidence; do not relabel v3 model-quality hashes as a newer full evaluation.
The >=95% factual-assertion/independent review gate remains open despite passing procedural/citation checks.

The CPU kind attempt produced a real insufficient-memory scheduler event for a 6 GiB model request. It was suspended,
its check Job stopped and PVC retained; native-model mode was restored and actual grounded inference rechecked. CPU
kind/PVC inference, full CPU Compose co-location, the specified 32 GiB hardware target and remote GitHub Actions remain
open. No VM settings, remote resources, commits, user data resets or destructive volume/cluster teardown were performed.

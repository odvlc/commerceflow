# Executed verification

<!--
Maintenance: Retains actual verification commands, results and limitations; keep unexecuted
deployment gates explicit.
-->

Date: 2026-10-17. Environment: ARM64 macOS, Java 25.0.4.1. A passing result means the check ran in this environment;
it makes no claim about future CI or deployment runs.

| Check                                                   | Observed result                                                                                                                                                                                                                                                                                                                                   |
|---------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Final Maven `verify dependency:build-classpath` reactor | PASS: 43 tests with zero failures, errors or skips; all applications packaged.                                                                                                                                                                                                                                                                    |
| Contracts                                               | 2 tests cover identifiable, versioned envelopes and rejection of invalid schemas and correlation IDs.                                                                                                                                                                                                                                             |
| Catalog                                                 | 5 real PostgreSQL/HTTP tests cover CRUD/OpenAPI, 20 concurrent idempotent creates, transactional rollback, optimistic version checks, deactivation and validation.                                                                                                                                                                                |
| Inventory                                               | 6 real PostgreSQL tests: 100 reservations against stock 10 produce exactly 10 successes. They also cover replay, commit and release; multi-item rejection and tombstones; expiry intent; security; and stock conservation during concurrent reservations and releases with mixed-prefix UUIDs.                                                    |
| Kafka delivery                                          | 5 real KRaft/PostgreSQL tests verify that duplicate events cause one effect, a failed effect rolls back its marker, and an unreachable broker retains and replays outbox rows in order. Poison records reach dead letters. A broker lifecycle restart retains its log and recovers a pending outbox commitment without repeating the reservation. |
| Order                                                   | 7 tests cover the trusted-price boundary, concurrent idempotency, ownership and admin roles, rollback on upstream failure, cancellation intent, validation and legal states.                                                                                                                                                                      |
| Payment                                                 | 3 real PostgreSQL tests cover 20 concurrent authorizations with one refund attempt, cancellation before authorization, and 20 cancellation/settlement races ending cancelled or refunded.                                                                                                                                                         |
| Full workflow                                           | 12 tests use real HTTP applications with independently owned databases, Kafka, Redis and Elasticsearch. They cover success, decline and stock rejection; timeouts and delayed results; order-process restart; JWT rejection; atomic carts; cache and search behavior, rebuilds and outages; and durable notification ownership and deduplication. |
| Real Keycloak                                           | 3 tests cover the login form and Authorization Code/PKCE exchange, gateway/backend roles and ownership, service-client scope, and rejection of the disabled password grant and requests missing PKCE.                                                                                                                                             |
| Frontend lint / TypeScript / Vite build                 | PASS.                                                                                                                                                                                                                                                                                                                                             |
| Frontend unit tests                                     | PASS: 2 tests cover duplicate-submission protection and checkout-key retention and replacement.                                                                                                                                                                                                                                                   |
| Chrome end-to-end                                       | PASS: 4 cases cover customer purchase, decline, notifications and session refresh; real search and mobile layout fit; admin product and stock management; and SUPPORT monitoring. Both thread modes were exercised.                                                                                                                               |
| Native five-minute demo                                 | PASS: browser cases and API checks verify exact stock after approved and declined payments, cached reads, authenticated metric snapshots and dashboard provisioning.                                                                                                                                                                              |
| Prometheus/Grafana                                      | PASS: seven authenticated targets are up, four rules pass promtool validation, two dashboards are provisioned, datasource health is OK, and Grafana queries return real outcome series.                                                                                                                                                           |
| k6                                                      | PASS: two 15-second catalog baselines with 10 users, plus 4 approved and 4 declined authenticated checkout iterations with 2 users. No HTTP requests or checks failed. Measurements and raw JSON are retained in `docs/load-results`.                                                                                                             |
| Full Compose configuration                              | PASS with standalone Compose 5.6.0. The Java health-check program compiled and succeeded against a live readiness endpoint.                                                                                                                                                                                                                       |
| Kubernetes rendering/schema                             | PASS with Kustomize 5.8.3 and kubeconform 0.8.0: 61 valid resources, zero invalid resources, errors or skips.                                                                                                                                                                                                                                     |
| Workflow validation                                     | PASS with actionlint 1.7.12; embedded shellcheck was not installed. Shell scripts separately pass `bash -n`. YAML syntax passed after flow-list volume/target scalars were quoted for older parsers.                                                                                                                                              |

Run the final Maven verification with:

```bash
./mvnw -B verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test
```

With the native applications and monitoring already running, run the demo with:

```bash
SERVICE_CLIENT_SECRET=local-test-service-only bash scripts/demo.sh
```

## Unverified in the original run

- Docker image builds and pulls, full container startup, and persistence across container restarts were untested
  because the environment had no Docker daemon or CLI. Compose configuration validation covered configuration only.
- kind deployment, CNI enforcement, HPAs, ingress behavior and Kubernetes restart/PVC recovery were untested because
  Docker, kind and kubectl were absent. Schema validation could not verify runtime behavior.
- The Kafka restart test stopped and restarted the real broker server inside the test JVM, using its retained log
  directories. The controller, JVM and container were not restarted, so a container or OS-process restart with the
  deployment volume remained untested.
- GitHub Actions definitions passed local validation. No Git repository or remote was initially configured, and no
  remote workflow ran.
- Tests used real native servers. The Testcontainers fallback compiled but did not run.
- The short native runs did not measure production capacity, high availability, long-duration saturation, peak memory
  or pool occupancy, or the virtual-thread population.

Each module's Maven reports are in its ignored `target/surefire-reports` directory. Execution logs, browser screenshots
and traces, scrape evidence and local monitoring data are in ignored `.cache/verification` and `frontend/test-results`.
The synthetic test infrastructure shuts down after verification. Use the documented scripts to run it again.

## AI and deployment supplement (2026-10-18)

The missing tools and unverified deployments above describe the 2026-10-17 baseline. With Docker, kind and Ollama
available, the following checks passed on the 48 GiB M5 Pro host with a 7.75 GiB Docker VM:

| Check                                                             | Actual result                                                                                                                                                                                                                                  |
|-------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `./mvnw -B -pl workflow-tests -am -Pai-real-model verify` (final) | 82 tests with no failures, errors or skips, including 30 AI core tests, 5 actual-model tests and all business/security suites.                                                                                                                 |
| Frozen `-Pai-real-model,ai-evaluation` candidate v3               | 240/240 procedural cases, 60/60 semantic recall/expected citations and 120/120 positive procedural checks; zero observed unsafe effects.                                                                                                       |
| Frontend lint/unit/build                                          | PASS: 5 unit tests. The compiled storefront ran through nginx/Ingress.                                                                                                                                                                         |
| Full commerce + Copilot Chrome suite                              | Compose host mode passed 8/8 cases in 41.7 s; the kind deployment with built assets and Ingress passed 8/8 in 42.7 s.                                                                                                                          |
| Updated ready-stack AI demo                                       | PASS: four AI browser cases include comparison of distinct products, followed by four actual crash/outage cases and fresh Prometheus samples after restart.                                                                                    |
| Compose                                                           | Nine application images build with nonroot users. Checks pass for all 16 services in host mode, explicitly turning AI off, additive database provisioning and process recovery after a lost acknowledgment.                                    |
| CPU Docker                                                        | Both pinned model digests pass verification, CPU generation and embedding warmup succeed, and a restart retains the model volume. The full CPU and business stack running together remains uncertified.                                        |
| kind policy                                                       | Calico 3.33.0 is installed. All 12 allow/deny probes pass, including blocked payment/inventory/notification egress and blocked Catalog-to-AI ingress.                                                                                          |
| kind durable recovery                                             | Checks pass for waiting proposals, physical container termination and resuming reads, replay with the same key after a lost Cart acknowledgment while inference is unavailable, Order/PG/Redis PVC recovery, and interrupted rebuild recovery. |
| Monitoring                                                        | Grafana live queries cover all three provisioned dashboards and eight authenticated Prometheus targets. The first-text-token metric was sampled after new inference following restart.                                                         |
| Source/deployment validators                                      | Shell and Python syntax, actionlint, three Compose modes and strict Kubernetes schemas for base/AI/Job/policy resources all pass. `git diff --check` passes.                                                                                   |

`docs/ai-results/` contains safe JSON for each candidate, capacity events, recovery and monitoring evidence, and a
source-review note. Later regression and operational checks cover API admission when resuming a Cart action, rebuild
recovery after releasing the session lock, and integer-overflow rejection. Those checks are separate from the v3 model
evaluation; keep its model-quality hashes attached to that run. The procedural and citation checks passed, but the
> =95% factual-assertion requirement still needs independent review.

The CPU kind deployment could not schedule its 6 GiB model request because the node had insufficient memory. The
attempt was suspended and its check Job stopped, with the PVC retained. Native-model mode was restored and grounded
inference checked again. CPU inference in kind with its PVC, the full CPU Compose stack running together, the specified
32 GiB hardware target and remote GitHub Actions still need verification. The run left VM settings and remote resources
unchanged, made no commits, reset no user data, and deleted no volumes or clusters.

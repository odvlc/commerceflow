# CommerceFlow implementation plan

The scope and phase order come from `CODEX_PROMPT.md`. A checked box means implemented **and verified**; unchecked
includes partial work. Each phase depends on the preceding phase; additional dependencies appear below. Runtime blockers
do not count as successful validation.

**Original native baseline (2026-10-17, superseded by the update below):** all seven applications and the frontend run
against real native infrastructure. The
final reactor passes 43 tests with no failures or skips; frontend lint/build, 2 unit tests and 4 Chrome cases pass.
Phases 2–10 have native execution evidence. Phases 1, 11–13 retain deployment/remote-CI acceptance checks because
Docker, kind and kubectl are absent. [Executed verification](docs/verification.md) distinguishes these limits from
source/schema validation.

**AI extension (2026-10-18):** [AI_PLAN.md](AI_PLAN.md) tracks the ordered A0–A12 implementation. A0–A8
have executed source/native verification; A9 quality gates remain open.
The independent AI service uses local pinned `llama3.1:8b` and 768-dimensional Nomic embeddings. It implements owned
PostgreSQL state, replay-safe knowledge indexing, authorized API tools, bounded durable runs/SSE, conditional confirmed
cart changes, and a React Copilot. No cloud model or automatic checkout/payment action is enabled.

- [x] A0: actual pinned generation/tool/embedding calls, frozen 80-case corpus, model/license hashes and ADR.
- [x] A1: service/security/state APIs and additive owned Compose database provisioning pass.
- [x] A2–A4: owned order explanations, durable lexical/hybrid knowledge pipeline and actual-model generation verified.
- [x] A5–A7: typed tools, leased/fenced runs and exact Cart proposals pass, including 100 concurrent confirmations,
  real lost-acknowledgment replay, fresh-auth process/pod recovery and model-independent Cart reconciliation.
- [x] A8: 5 frontend unit tests, lint/build and 8 actual Chrome/Keycloak cases pass on Compose and built kind Ingress.
- [ ] A9 release quality: frozen hybrid corpus passes 240 procedural checks; recall/citations 60/60, positive procedural
  checks 120/120, zero observed unsafe effects. Independent factual-assertion/wording review remains open; see source
  review.
- [ ] A10 full acceptance: host/off and standalone CPU/retained-model-volume checks pass; full CPU co-location and the
  specified 32 GiB hardware target remain unverified on the 48 GiB host / 7.75 GiB VM.
- [ ] A11 full acceptance: native-model kind, twelve enforced Calico allow/deny probes, eight browser cases, actual pod
  loss/lost Cart acknowledgment and PostgreSQL/Redis/index recovery pass. CPU model pod/PVC inference is memory-blocked.
- [ ] A12 final release: CI/image/manifest/artifact sources and demo execute/validate locally; remote GitHub Actions,
  final factual review and hardware gates remain open. All implementation/docs/evidence are reviewable in AI_PLAN.md.

Latest regression: **82 Java tests, no failures/errors/skips**; 5 frontend tests plus lint/build. Four ready-stack AI
browser demo cases additionally verify two distinct product sources and fresh post-restart metrics. Compose and kind
volumes remain intact. Native-model kind is restored and running at http://localhost:3000.

**Prerequisites:** Docker 29.8.2 / Compose 5.6.0, kind 0.33.0, kubectl 1.36.1 and Ollama 0.40.6 now respond. Historical
missing-tool blockers below are superseded; their runtime acceptance checks remain open. Host RAM is 48 GiB and Docker's
VM has about 7.75 GiB, so this run cannot certify the specified 32 GiB target. Both actual models are downloaded and
verified. A temporary container successfully reached the native localhost-bound Ollama through `host.docker.internal`.
No Docker Desktop resource settings were changed. Source and native runtime evidence are distinct from Docker/kind
acceptance. See [AI evaluation](docs/ai-evaluation.md) and retained [results](docs/ai-results/).

## Phase 1 — Maven repository and local infrastructure

- [x] Inspect repository/specification and create persistent engineering rules (`AGENTS.md`).
- [x] Verify official dependency/image compatibility and record pins and sources (runtime image checks remain blocked).
- [x] Build Java 25 Maven parent, contracts module, wrapper, reproducible test configuration, and service build
  conventions.
- [x] Configure PostgreSQL with separate service databases/users, Kafka internal/external listeners, Redis,
  Elasticsearch, and Keycloak; persistent volumes, health checks, localhost ports, lightweight profiles, local-only
  credentials (Compose configuration validated).
- [x] Compile/test the Maven reactor and validate the full Compose configuration.
- [x] Start container infrastructure and verify its health (2026-10-18 full Compose health checks passed).
- Acceptance: reproducible Maven build and healthy local infrastructure; no shared service database credentials.

## Phase 2 — Catalog Service and PostgreSQL

- [x] Implement Flyway products/categories/outbox schema, constraints, indexes, JPA versioning and repositories.
- [x] Implement catalog list/detail and admin create/update with typed DTOs, validation, pagination, errors, correlation
  IDs, OpenAPI and transactional writes.
- [x] Implement integration/HTTP tests against real PostgreSQL including rollback, constraints, stale-version rejection,
  concurrent idempotency and validation.
- [x] Build, migrate, run and smoke-test the catalog in the Spring integration context; record evidence.
- Acceptance: real PostgreSQL-backed CRUD with trusted money and transactional outbox intent; no runtime mocks.

## Phase 3 — Order and Inventory Services

- [x] Implement owned schemas for orders/items/saga steps/outbox/processed events and inventory/reservations.
- [x] Implement trusted catalog pricing, request-hash idempotency, ownership-ready order queries, cancellation and
  explicit transitions.
- [x] Implement atomic stock reservation/commit/release and idempotent operations, inventory administration and expiry
  reconciliation.
- [x] Verify 100 reservations against stock 10 yield exactly 10 successes; test transactions, duplicate operations and
  legal transitions.
- Acceptance: durable orders and reservations, no overselling, no cross-service SQL.

## Phase 4 — Kafka and transactional outbox

- [x] Define versioned topics/envelopes/contracts with order ID keys and correlation IDs.
- [x] Implement acknowledged, durable outbox publishing and transactional consumer deduplication.
- [x] Implement bounded retries/backoff, poison-message dead letters, metrics and recovery procedures.
- [x] Verify replay causes one effect and broker outage retains recoverable outbox rows.
- Acceptance: eventual at-least-once delivery without lost committed business events.

## Phase 5 — Payment Service and saga orchestration

- [x] Implement simulated authorize/decline/timeout/delay/refund, payments/attempts schema and idempotency.
- [x] Implement durable saga with reservation, payment, commitment, confirmation, release/cancellation and
  reconciliation.
- [x] Verify success, insufficient stock, decline, timeout, late authorization, duplicate events and process restart.
- Acceptance: correct final money/stock/order state under each failure; no double charge.

## Phase 6 — Redis cache and Cart Service

- [x] Implement cache-aside catalog TTL/invalidation, hit/miss/failure metrics and safe degradation.
- [x] Implement atomic user-scoped TTL carts with validated typed endpoints and trusted checkout conversion.
- [x] Verify Redis concurrency, isolation, expiry and outage behavior with real Redis.
- Acceptance: Redis-backed cart/cache; durable authoritative data unaffected by cache outage.

## Phase 7 — Elasticsearch indexing and search

- [x] Verify server/client/Spring Data compatibility; define explicit mappings, versioned index and read alias.
- [x] Implement Kafka indexing, stale-event protection, deactivation, full PostgreSQL rebuild and recovery.
- [x] Implement full-text search, category/price filters, bounded paging, sorting, relevance and degraded errors.
- [x] Verify creation/update/deactivation, replay, stale events, rebuild and unavailable search.
- Acceptance: real Elasticsearch results and documented eventual consistency.

## Phase 8 — Keycloak and security

- [x] Configure local realm, CUSTOMER/ADMIN/SUPPORT roles, public PKCE client and service identities.
- [x] Implement gateway routing, JWT validation in all services, method/route authorization, ownership and
  service-to-service tokens.
- [x] Verify 401/403, cross-customer isolation, administrative access and issuer/audience behavior.
- Acceptance: authenticated workflow with no custom identity/password system.

## Phase 9 — Notifications and React frontend

- [x] Implement durable asynchronous local notifications and replay-safe consumption.
- [x] Build responsive typed React/Vite application: catalog/search/details, cart, login/logout, checkout/history/status
  and admin products/stock/orders.
- [x] Implement accessible forms, validation/loading/errors, duplicate checkout protection and tests.
- [x] Verify frontend lint/test/build and authenticated end-to-end purchase flows.
- Acceptance: usable UI backed by real secured services and durable notifications.

## Phase 10 — Observability and load testing

- [x] Implement Actuator, Micrometer, JSON logs, propagation and specified HTTP/JVM/DB/Kafka/outbox/saga/cache/search
  metrics; inspect authenticated live samples.
- [x] Provision native Prometheus, Service Health and Order Processing dashboards, and four validated example alerts.
- [x] Add reproducible k6 catalog/checkout scenarios and platform/virtual-thread configuration; execute both thread
  baselines and approval/decline checkout smoke runs.
- [x] Verify seven scrape targets, dashboard queries and datasource health; record actual throughput, p95/p99,
  end-of-run memory, platform-thread and pool samples.
- [ ] Extend short smoke measurements to repeated sustained runs, peak pool/memory sampling and virtual-thread
  population profiling (future performance work; no capacity claim).
- Acceptance: meaningful live monitoring and reproducible measured benchmark artifacts.

## Phase 11 — Complete Docker Compose stack

- [x] Add multi-stage application/frontend Dockerfiles and all application/monitoring services; validate configuration
  and compile/run the Java readiness helper.
- [x] Validate profiles, environment references, dependency declarations, volumes and resource budgets with standalone
  Compose; check shell/Python syntax.
- [x] Execute image builds and verify container probes, dependencies, resource behavior and service discovery
  (2026-10-18).
- [x] Build/start full stack and run success/decline/search/cache/monitoring demonstration (Compose on 48 GiB host; 32
  GiB capacity unverified).
- Acceptance: documented reproducible Compose startup on a 32 GB Mac.

## Phase 12 — Complete Kubernetes deployment

- [x] Implement kind/Kustomize namespace, config/secrets templates, Deployments/Services/Ingress and stateful
  infrastructure/PVCs; render and schema-check all 61 resources.
- [x] Configure schema-valid startup/readiness/liveness probes, resource limits, rollout strategy, HPAs and
  NetworkPolicies.
- [x] Add syntax-checked local image loading/startup/teardown/diagnostics/recovery scripts and Kubernetes DNS; document
  single-node availability limits.
- [x] Deploy and verify ingress, probes, rollouts, HPAs and CNI policy enforcement (actual Calico/native-model kind).
- [x] Execute restart/PVC durability and recovery demonstrations on kind (Order, PostgreSQL, Redis and AI/index state).
- Acceptance: working local kind deployment with persisted data after restarts.

## Phase 13 — Failure tests, CI, documentation and polish

- [x] Execute critical failure scenarios 1–8 and the full native E2E suite; additionally restart the real Kafka broker
  over retained logs and recover a pending outbox command.
- [x] Execute critical scenario 9: Kubernetes service restarts preserve durable data.
- [x] Add CI Java/frontend/integration checks and eight-image Docker build matrix; validate workflow with actionlint and
  shell syntax checks.
- [ ] Execute GitHub Actions remotely; all nine image builds were verified locally, but no remote workflow was
  dispatched.
- [x] Complete/review README, Mermaid architecture, data/API/event/security/observability docs, ADR, recovery guide and
  measured load results.
- [x] Add and execute native five-minute successful/declined purchase, exact stock compensation, search/cache and
  provisioned Grafana demo.
- [x] Execute the demonstration on the full Compose/kind deployment (native local models; CPU co-location remains
  separate).
- [x] Audit every final acceptance criterion and retain an explicit evidence/remaining-work matrix below.
- Acceptance: reproducible setup, passing required checks and documented recovery; remaining limitations explicit.

## Execution log, decisions and blockers

- Initial inspection (2026-10-17): only `CODEX_PROMPT.md` and IDE metadata exist; directory has no Git repository.
  Existing files preserved.
- Java 25.0.4.1 is installed. `mvn`, `docker`, `kubectl`, and `kind` are not on PATH. Node/npm and Python are available.
  Initial sandbox request to Maven Central failed DNS resolution; investigate authorized network access and available
  local tooling before choosing a fallback.
- Official Spring documentation reports Boot 4.1.1 supports Java 25 and Spring Data Elasticsearch 6.1.x aligns with
  Elasticsearch 9.4.5. Pin only after checking artifact availability; exact sources/results will be retained in
  `docs/dependencies.md`.

## Next action

Independently review the retained v3 factual assertions/wording before closing AI release quality; run the explicit
CPU Compose/kind profiles on a VM with sufficient measured memory and verify the 32 GiB hardware target; dispatch the
configured CI when remote execution is authorized. These open gates do not invalidate the completed native-model
Compose/kind demonstrations. No routine repair deletes data or changes Docker Desktop allocation.

### Phase 1 verification

- `./mvnw -B verify`: PASS, 2 contract tests, Java 25, Boot 4.1.1.
- Docker Compose v5.6.0 standalone `-f infra/compose.yaml --profile full config --quiet`: PASS.
  `bash -n infra/postgres/init.sh`: PASS.
- Docker daemon/CLI and kind/kubectl absent, with no Docker socket. Infrastructure startup remains unchecked. Proceeding
  to Phase 2 under the specification’s environment-limits rule using real native PostgreSQL for integration tests.

### Phase 2 verification

- `./mvnw -B verify`: PASS, 7 tests total (2 contracts, 5 catalog integration), no skips. Real native PostgreSQL 18.6.0,
  Flyway migration and Hibernate validation, full Spring HTTP stack via MockMvc; executable jar packaged.
- Initial integration run failed on JDBC `Instant` binding; fixed with explicit SQL `Timestamp`, rerun passed. Tests
  include 20 concurrent retries producing exactly one product/outbox row, unique-SKU rollback including category, stale
  version conflict, deactivation, paging/input validation and OpenAPI generation.
- Catalog search/cache remain their specified later phases. Early security baseline will be introduced with Phase 3
  because order ownership needs authenticated identity. Keycloak realm provisioning and end-to-end login remain Phase 8.

### Phase 3 verification

- `./mvnw -B verify`: PASS, 19 tests, no skips. Catalog (5), inventory (5), order (7), contracts (2).
- Real PostgreSQL: 100 concurrent reservation transactions against stock 10 yielded exactly 10 reservations and 90
  rejections; stock stayed nonnegative. Also checked replay/release/commit accounting, multi-item rejection,
  cancellation tombstones and expiry reconciliation intent.
- Orders: trusted catalog quote boundary (test mock only), 20 concurrent retries yield one order/event and one pricing
  call; price-service failure rolls back; HTTP ownership, ADMIN role, service scopes, 401/403/404 behavior and
  cancellation intent verified. Actual cross-process catalog pricing is not yet verified.
- Added `service-support` library for database-local infrastructure/web/security helpers, with no JPA entities or
  foreign-service access. JWT issuer/audience/roles/scopes enforced; Keycloak realm/login still pending.

### Phase 4 verification

- `./mvnw -B verify`: PASS, 23 tests, no skips, including 4 real KRaft Kafka + PostgreSQL delivery tests.
- Replayed one Kafka event 5 times: one reservation, one consumer marker and one result event. Failed business effect
  rolled back its marker and succeeded on retry. Unreachable broker retained outbox rows; restoring delivery published
  in aggregate sequence. Malformed message reached the shared dead-letter topic after bounded retries.
- Native test broker is real Apache Kafka, not a mock; container lifecycle checks remain blocked. Consumer retries are
  bounded; outbox retries continue with capped backoff to preserve committed events.

### Phase 5 verification

- `./mvnw -B verify`: PASS, 33 tests, no skips (contracts 2, catalog 5, inventory 9, order 7, payment 3, four-service
  workflow 7).
- Four real HTTP applications, Kafka KRaft and one PostgreSQL server with four separately owned databases/roles
  exercised successful checkout, insufficient stock without charging, declined payment compensation, timeout
  cancellation, delayed result reconciliation, valid delayed authorization, repeated payment command and durable
  order-service restart.
- Real signed JWTs validated against a test-only JWKS fixture: wrong issuer/audience denied, roles enforced,
  cross-customer access hidden. This does not verify Keycloak realm provisioning or PKCE login.
- Payment tests: 20 concurrent authorizations produce one charge attempt, repeated cancellation produces one refund
  attempt, cancel-before-authorize prevents charging; 20 cancellation/settlement races end cancelled or refunded.
- Security errors now carry ProblemDetail and correlation IDs. No full Compose stack or frontend exists yet.

### Phase 6 verification

- `./mvnw -B verify`: PASS, 36 tests, no skips. Five real HTTP applications and Redis 8.10.2 added to the four-service
  suite.
- 100 concurrent cart increments retained all changes; replayed request key had one effect; user isolation, HTTP
  add/set/delete, TTL expiry and checkout using cart contents verified. Cart request deduplication is bounded to cart
  TTL and 1000 operations per live cart.
- Catalog cache hits, after-commit invalidation, Kafka invalidation and stale-fill version fences verified. Checkout
  requests bypass cache and validate prices from PostgreSQL.
- Stopping Redis produced explicit cart 503, while catalog fallback and a complete authoritative checkout still worked;
  cache failure metric incremented.
- Initial top-level Redis build failed on optional modules; core-only `make -C src redis-server` succeeded. Reproducible
  test-tool bootstrap script pins the downloaded source checksum. Native Redis is used here; Testcontainers fallback
  compiles but cannot run without Docker.

### Phase 7 verification

- `./mvnw -B verify`: PASS, 37 tests, no skips, with a real native Elasticsearch 9.4.5 server (bundled JVM 26) and
  Boot-managed Spring Data Elasticsearch 6.1.1/client 9.4.5.
- Actual Kafka creation/update/deactivation became searchable; category/currency/price filters, text matching and
  sorting exercised. External PostgreSQL versions rejected an older replay. Full rebuild created a fresh mapped index
  and swapped read/write aliases while retaining deactivation tombstones. Search outage returned 503; catalog browsing
  and checkout still completed.
- Initial search compilation found an ambiguous Timer import; fixed and full suite passed. Rebuild retains prior indices
  for explicit operator cleanup, and serializes with index consumers using a database advisory lock; PostgreSQL writes
  continue with durable follow-up events.

### Phase 8 verification

- `./mvnw -B verify`: PASS, 40 tests, no skips; real Keycloak 26.8.1 on a separately owned PostgreSQL database and
  Spring Cloud Gateway MVC 5.0.3 (Cloud BOM 2025.1.3) exercised. Official Cloud release documentation confirms Boot 4.1
  compatibility. Requested newer BOM 2025.1.4 was absent from Maven Central; used the officially verified available
  2025.1.3 release.
- Real login-form Authorization Code + S256 PKCE exchange produced tokens used through gateway and directly at backends.
  Verified ADMIN mutations, CUSTOMER denial, ownership isolation, client-credentials internal scope, disabled password
  grant and rejected missing PKCE.
- Tests caught realm imports lacking default roles/basic subject mappers; added explicit roles/username/basic scope and
  UUID-subject validation, and fixed the HTTP test client to preserve browser-style auth cookies. Full suite passed
  after fixes. Native test-tool bootstrap and updated full-profile Compose configuration validated.

### Phase 9 verification

- Java
  `./mvnw -B verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test`:
  PASS, 41 tests, no skips. Notification replay/logical deduplication and customer isolation verified on its owned
  PostgreSQL database.
- Frontend `npm run lint`, `npm test` (2 tests) and `npm run build`: PASS. Compatible pinned registry versions: React
  19.3.1, Vite 8.3.4, TypeScript 6.0.3 (TypeScript 7 excluded because typescript-eslint requires <6.1), Keycloak JS
  26.2.4.
- Real Chrome `npm run test:e2e`: PASS, 3 tests against real native infrastructure and all seven applications: PKCE
  login, successful checkout, decline compensation and notifications, real search/mobile viewport, admin product
  creation and stock setting. Screenshot visually inspected at `.cache/verification/storefront.png`.
- Fixed gateway routes to use explicit Java configuration rather than depend on application-resource ordering in
  multi-service tests. Fixed TypeScript UUID key inference, unit/E2E runner discovery boundaries, and the stock selector
  accessible name; reran affected checks successfully.
- Native browser harness is ephemeral test infrastructure; it does not replace the required persistent Docker/Kubernetes
  deployments. Remaining operational runtime acceptance is blocked by missing Docker/kind. Proceeding with verifiable
  operational code/configuration and recording runtime checks separately.

### Phase 10 verification

- Native Prometheus 3.15.0 scraped seven authenticated service endpoints; Grafana 13.2.4 provisioned both dashboards,
  datasource health passed, and a datasource-proxy query returned real order outcomes. `promtool check config` passed
  with four valid alert rules. Evidence is retained under `.cache/verification`.
- k6 2.3.0, 10 users / 15 seconds / three catalog reads per iteration: virtual 4,051 requests, 268.00 requests/s, p95
  6.60 ms, p99 11.01 ms; platform 4,081 requests, 270.03 requests/s, p95 6.19 ms, p99 9.20 ms. No failed HTTP requests.
  Both modes also ran the browser suite.
- Real memory/platform-thread/pool samples and search/cache/Kafka processing observations are recorded in
  `docs/load-tests.md` and raw `docs/load-results` JSON. These short sequential shared-JVM runs are smoke baselines, not
  a statistically supported winner/capacity claim. Peak/under-load profiling remains unchecked.
- Authenticated k6 checkout: two users, four approval iterations and four decline iterations; all 16 acceptance/outcome
  checks passed. Direct stock verification found exactly four committed units and zero remaining reservations.

### Phase 11 verification

- Full standalone Compose 5.6.0 configuration validates after final changes. Every application has a multi-stage nonroot
  Java image, frontend a Node/nginx build; source includes persistent infrastructure, all services, profiles, health
  dependencies and monitoring. The Java readiness helper compiled and passed against an actual native endpoint.
- Container image builds, pulls, health behavior, full startup, persistent restart and runtime resource budgets remain
  unexecuted: there is no Docker daemon/CLI/socket. Source validation and native app execution do not close those
  acceptance criteria.

### Phase 12 verification

- `kustomize build infra` and `kubeconform -strict -summary`: PASS, 61 valid resources, zero invalid/errors/skips.
  Shell/Python syntax checks passed for render/startup/teardown/diagnostics/recovery scripts. These checks do not
  execute the scripts against a cluster.
- Includes pinned kind node, service DNS, persistent infrastructure, app probes/budgets/rollouts, local image loading,
  ingress, HPAs, monitoring, namespace RBAC and NetworkPolicies. Controller API egress is generated from discovered
  exact private API addresses (/32 or /128); broad public egress is not used.
- A broad controller-egress proposal was rejected by automatic approval review. It was replaced with the approved
  exact-address generation above; no approval remains outstanding. Default kind networking does not establish CNI
  enforcement.
- No kind deployment, image loading, ingress/HPA/policy behavior or restart/PVC durability has executed because Docker,
  kind and kubectl are unavailable. Critical scenario 9 remains open.

### Phase 13 verification and final audit

- Final
  `./mvnw -B verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test`:
  PASS, **43 tests, zero failures/errors/skips**. Contracts 2, catalog 5, inventory 6, Kafka 5, orders 7, payments 3,
  multi-service workflows 12, real Keycloak 3. Full report: `docs/verification.md`; latest log:
  `.cache/verification/java-final.log`.
- Final hardening verified canonical PostgreSQL UUID lock ordering under concurrent mixed-product reservation/release,
  configurable initial saga deadlines, issue/retry-based attempt accounting and the shared customer-addressed
  notification contract. The full reactor passed after these changes.
- New real broker failure test stops/restarts the Kafka broker server with retained logs and the same listener, proves
  the pre-restart acknowledged event remains readable, keeps a PostgreSQL outbox command pending during outage and
  commits inventory once after recovery. This is broker lifecycle recovery inside the test JVM; container/JVM/controller
  restart remains a deployment check.
- Frontend lint, 2 unit tests, TypeScript/Vite production build and 4 Chrome E2E cases pass. PKCE session refresh,
  success, decline/notification compensation, search/mobile, admin products/stock and SUPPORT monitoring exercised.
  Repeated demo runs use product search so newly created admin products cannot displace fixture products from the first
  catalog page.
- `SERVICE_CLIENT_SECRET=local-test-service-only bash scripts/demo.sh`: PASS. Verified exact approved/declined stock,
  cache reads, seven authenticated metric captures and both Grafana dashboards. Screenshot inspected at
  `.cache/verification/storefront.png`. The complete native demo passed after final E2E changes.
- CI source passes actionlint 1.7.12 with embedded shellcheck disabled because it is absent; all shell scripts
  separately pass `bash -n`, Python scripts parse, Compose validates and all 61 Kubernetes resources schema-check.
  GitHub execution and Docker builds have not run.
- Initial failures (JDBC timestamp binding, source imports, realm scope mappers, runner discovery and accessible
  selectors) were corrected and affected/full suites rerun; no failing tests are excluded. Native server fixtures are
  real services and synthetic data; no H2/runtime mocks or paid external resources were used.

| Specification final acceptance criterion    | Evidence / remaining check                                                                                                              |
|---------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| Reproducible Docker Compose startup         | Nine images built; full Compose host/off startup and browser/monitoring/recovery pass. CPU/32 GiB gates remain open.                    |
| Authenticated React interface               | Real Chrome PKCE/session/purchase/admin/support flows pass.                                                                             |
| Elasticsearch product search                | Native search/index/update/deactivation/rebuild/outage tests and browser search pass.                                                   |
| Redis cart/catalog cache                    | Atomic concurrency, TTL, invalidation, isolation and outage tests pass; browser and live hit/miss samples observed.                     |
| Kafka-powered checkout                      | Real broker, HTTP services and separately owned databases pass success/failure/replay/restart checks.                                   |
| PostgreSQL integrity                        | Real migrations/constraints/rollback/idempotency/ownership tests pass.                                                                  |
| Durable saga/compensation                   | Timeout, late authorization, decline and order-application restart tests pass.                                                          |
| Reliable outbox/replay-safe consumers       | Broker outage/lifecycle restart, retained logs, deduplication and poison-message DLT tests pass.                                        |
| Successful/declined simulated payments      | Integration, Chrome demo and authenticated k6 checkout checks pass with exact stock verification.                                       |
| Concurrency-safe inventory                  | 100 requests / stock 10 gives exactly 10 reservations; mixed-product reserve/release conservation passes.                               |
| Meaningful Grafana metrics                  | Seven targets up, both dashboards provisioned, datasource health/query pass on native monitoring.                                       |
| Runnable automated tests                    | 82 Java + 5 frontend unit + 8 Chrome cases; frozen 240-case AI evaluation retained. Remote CI/Docker fallback suites remain unverified. |
| Local Kubernetes/restart recovery           | Native-model kind deployed; enforced policies, browser workflows and Order/PG/Redis/AI rebuild recovery pass; CPU memory-blocked.       |
| Security/authorization                      | Real Keycloak PKCE/service scope tests, issuer/audience/UUID subjects, roles and cross-customer ownership tests pass.                   |
| Architecture/setup/API/troubleshooting docs | Written/reviewed and linked from README; blocked deployment instructions explicitly identified as unexecuted.                           |

The repository intentionally remains partially accepted until the unchecked deployment/runtime items above execute
successfully. No remote deployment, Git initialization/commit or persisted user-data deletion was performed.

### IntelliJ cache exclusion follow-up

- [x] Inspect the read-only dialog and file permissions: listed files are downloaded Elasticsearch binaries under
  `.cache/tools`; `catalog-service/pom.xml` is writable.
- [x] Exclude `.cache` in the existing `commerceflow.iml` content root while preserving other module settings.
  Parse/validate the module XML and catalog POM; verify the exclusion and POM write access.
- [ ] Confirm the original operation in IntelliJ after reloading the project. Live dialog dismissal/retry was not
  exercised by the command-line validation.

### Docker prerequisite reopened and verified (2026-10-18)

- All eight commerce images built and were inspected for nonroot runtime users. All 15 AI-disabled Compose services
  passed health checks on the 48 GiB host / 7.75 GiB Docker VM. Actual `docker stats` samples are retained locally;
  this does not certify the original 32 GiB hardware target.
- Fixed two executed startup failures: PostgreSQL init bind-mount executable permission and frontend health probe's
  IPv6 localhost mismatch. Initial database provisioning is now additive, owner-checked and explicitly rerunnable;
  the partially initialized PostgreSQL volume was repaired and preserved. No `down -v`, password reset or data reset.
- Four Chrome cases PASS in 22.7 s on real Compose Keycloak/gateway/services, after documented `demo.py seed` created
  the initially missing fixtures. Exact successful-stock commitment/decline compensation verification PASS. First
  unseeded browser failures remain in logs and are not counted as passes.
- Prometheus acquired all seven authenticated service targets; Grafana provisioned all three dashboards. Added
  `METRICS_MODE=compose` to collect these samples without publishing internal application management ports. Its initial
  native-port collector failure was fixed and reran successfully. Original pod restart/remote CI gates remain open.

### Clarifying comments (2026-10-18)

- [x] Add explanatory comments to the AI worker, persistence, retrieval, Cart replay, frontend streaming/retry,
  startup and evidence-export paths. Explain ordering and failure recovery without changing executable behavior.
- [x] Annotate README command examples and maintenance notes for ephemeral versus persistent state, optional AI
  startup, separate pinned models, asynchronous knowledge indexing and the limits of recorded verification.
- [x] Verify `./mvnw -B -pl ai-service,cart-service -am test-compile -DskipTests` (BUILD SUCCESS), frontend lint,
  all 5 frontend tests and production build; `bash -n scripts/compose-up.sh`, Python AST parsing of
  `scripts/collect-safe-evidence.py` and `git diff --check` pass. The initial sandboxed Maven attempt could not open
  its existing cache lock; the approved rerun passed. Java tests, model evaluations and deployment recovery were
  not rerun for these comments-only edits; existing runtime acceptance gates remain unchanged.

### Project-wide clarifying comments (2026-10-18)

- [x] Review all 293 maintained files and add responsibility/invariant comments to 232 editable source,
  test, configuration, script and documentation files. Explain stock lock ordering, atomic deduplication,
  outbox retry ordering, cache version watermarks and issuer/JWKS separation beside the relevant logic.
- [x] Add `docs/file-guide.md` and link it from README. Document strict JSON, applied SQL migrations,
  model prompts, retrieval fixtures, retained results and upstream launchers without changing their data.
  Generated builds/dependencies, caches, local credentials and IDE metadata are outside this inventory.
- [x] Verify exact pre-edit contents are recovered when the newly added comments are removed, allowing
  only the README guide link and this progress record as separate documentation updates. Protected artifacts
  remain byte-identical. All 32 YAML files parse to the same data before/after annotation.
- [x] 
  `./mvnw -B -pl workflow-tests -am -Dtest=EventEnvelopeTest,OrderStateTest,GroundedAnswerTest,ChatAccumulatorTest,ToolArgumentsTest,ModelAdmissionTest -Dsurefire.failIfNoSpecifiedTests=false test`
  passes across the reactor: 15 selected Java tests, zero failures/errors/skips. This compiles all Java modules;
  the full infrastructure/model/recovery suites were not rerun for a comments-only change.
- [x] Frontend lint, all 5 unit tests and production build pass. Python AST parsing (18 files), Bash syntax (12 files),
  XML parsing (12 files) and JSON parsing (33 files) pass. Compose configuration validation with
  full/AI/model profiles and Kustomize builds for base, host AI and CPU AI pass. `git diff --check` passes.
  Local byte-comparison audit and verification logs are retained under ignored `.cache/comment-review`
  and `/private/tmp/commerceflow-project-comments-maven.log`; existing runtime acceptance gates are unchanged.

### Inline explanations beside executable code (2026-10-18)

- [x] Add 340 inline comments across 211 files, including every one of the 156 maintained Java,
  TypeScript/JavaScript, Python and shell files. Explain decisions inside methods/control flow and
  beside DTO fields, annotations, test setup/assertions and configuration settings rather than relying
  on file headers. Cover transaction boundaries, replay/idempotency, stock ordering, pricing authority,
  payment uncertainty, worker fencing, credentials, SSE reconnects and explicit local provisioning.
- [x] Verify that removing these exact comment insertions reproduces the pre-edit contents of all
  211 files. All 18 Python ASTs and all 12 parsed XML trees are unchanged (ignoring XML formatting
  whitespace); all 32 YAML documents retain their values. Strict JSON, applied Flyway SQL, model
  prompts, frozen evaluation inputs, retrieval fixtures and retained results remain byte-identical.
  Progress recording is the only separate documentation change in this pass.
- [x] Rerun
  `./mvnw -B -pl workflow-tests -am -Dtest=EventEnvelopeTest,OrderStateTest,GroundedAnswerTest,ChatAccumulatorTest,ToolArgumentsTest,ModelAdmissionTest -Dsurefire.failIfNoSpecifiedTests=false test`:
  BUILD SUCCESS, all reactor modules compile and all 15 selected Java tests pass without skips.
  Compile `infra/HealthCheck.java` separately with `javac`; frontend lint, all 5 unit tests and
  production build pass. Bash syntax (12 scripts), JSON parsing (33 files), Compose full/AI/model
  configuration, base/host-AI/CPU-AI Kustomize builds and `git diff --check` pass.
- Full infrastructure, browser, real-model and crash-recovery suites were not rerun for comment-only
  edits. Existing acceptance gates remain open. Audit snapshots/insertion metadata are retained in
  ignored `.cache/inline-comment-review`; Maven output is in
  `/private/tmp/commerceflow-inline-comments-maven.log`.

### Standalone run-and-demo guide (2026-10-18)

- [x] Create `docs/run-and-demo.md` with ordered prerequisites, native Ollama/model preparation,
  full Compose startup, seeding/indexing, URLs/accounts, commerce and AI demonstrations, monitoring,
  automated browser/recovery commands, troubleshooting and data-preserving shutdown. Include the
  alternative native-model kind workflow and retain the documented CPU/hardware/CI acceptance limits.
- [x] Link the guide near the top of README and add it to the project file guide.
- [x] Validate all 17 Bash examples with `bash -n`, all 8 local document links and 9 script references,
  README/file-guide navigation, startup/demo help options and `git diff --check`. No application
  startup, process interruption, model download or runtime suites were executed for this documentation change.

### Clarify native browser-harness shutdown (2026-10-18)

- [x] Explain when to skip the native-stack shutdown step, the conflicting gateway/Keycloak ports,
  the exact marker-file commands, shutdown order and terminal completion signal. Distinguish the
  harness from separately launched Vite, IntelliJ, Docker, kind and Ollama processes.
- [x] Verify the description against `BrowserTestStack` stop-file polling, startup marker removal
  and cleanup order; validate guide Bash examples and `git diff --check`. No processes were stopped.

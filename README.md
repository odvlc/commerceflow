# CommerceFlow

<!-- TOC START -->

## Table of contents

- [Technology stack](#technology-stack)
- [Prerequisites](#prerequisites)
- [Build and verify](#build-and-verify)
- [Docker Compose](#docker-compose)
- [Kubernetes](#kubernetes)
- [Optional local Llama copilot](#optional-local-llama-copilot)
- [Five-minute demonstration](#five-minute-demonstration)
- [Documentation](#documentation)

<!-- TOC END -->

<!--
Maintenance: Entry point for setup commands and recorded verification scope; detailed operations and
acceptance evidence live in the linked guides.
-->

A Java 25 / Spring Boot event-driven commerce application with a React storefront, Keycloak login,
PostgreSQL-owned service data, Kafka checkout orchestration, Redis carts/cache and Elasticsearch
search.

The optional local Llama copilot adds cited retrieval, authorized tools, durable
investigations and explicitly confirmed cart changes. The frozen 80-case hybrid corpus ran three
times. All 240 scenario checks passed, with zero observed unsafe business effects. Individual
factual assertions still need review; see [AI evaluation](docs/ai-evaluation.md).

The full Compose host-model stack and native-model kind deployment run with real infrastructure.
Tests cover Calico allow/deny rules and recovery after process or pod loss. The complete CPU model
co-location profile exceeds the current 7.75 GiB Docker VM; standalone CPU generation/embeddings and
model-volume restart pass. The host has 48 GiB, so the original 32 GiB hardware target remains
unverified. GitHub Actions definitions are validated but have not run remotely. [PLAN.md](PLAN.md),
[AI_PLAN.md](AI_PLAN.md) and [verification](docs/verification.md) retain exact scope/results. No
commits, remote deployment or volume deletion were performed.

## Technology stack

Ordered approximately by usage across source code, modules and configuration, rather than runtime
traffic or resource consumption. Related technologies are grouped; transitive dependencies are
excluded. File and line counts are a snapshot of maintained source, including tests and configuration.
Direct-use locations identify folders with code, configuration or build declarations for each technology.

| Rank | Technology                                                     | Usage in this repository                                                                                   | Direct use (modules/folders)                                                                                                                                                                                                    |
|-----:|----------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|    1 | Java 25                                                        | Backend services, shared contracts, infrastructure helpers and tests: 106 files, about 14,900 lines.       | `api-gateway/`, `ai-service/`, `cart-service/`, `catalog-service/`, `inventory-service/`, `notification-service/`, `order-service/`, `payment-service/`, `contracts/`, `service-support/`, `workflow-tests/`                    |
|    2 | Spring Boot 4.1.1 / Spring Framework                           | Foundation for all eight backend applications; Spring imports appear in 84 Java files.                     | `api-gateway/`, `ai-service/`, `cart-service/`, `catalog-service/`, `inventory-service/`, `notification-service/`, `order-service/`, `payment-service/`                                                                         |
|    3 | Spring MVC / REST / HTTP / JSON                                | Controllers, service-to-service calls, validation and consistent API errors throughout the backend.        | `api-gateway/`, `ai-service/`, `cart-service/`, `catalog-service/`, `inventory-service/`, `notification-service/`, `order-service/`, `payment-service/`, `contracts/`, `service-support/`                                       |
|    4 | PostgreSQL 18.6 / SQL                                          | Durable storage for six application databases, plus Keycloak's database; 14 SQL files.                     | `catalog-service/`, `order-service/`, `inventory-service/`, `payment-service/`, `notification-service/`, `ai-service/`, `infra/postgres/`, `infra/compose.yaml`                                                                 |
|    5 | Maven 3.10 / Maven Wrapper                                     | Multi-module Java builds, dependency management and test execution; 12 POM files.                          | `pom.xml`, `*/pom.xml`, `.mvn/`, `mvnw`                                                                                                                                                                                         |
|    6 | Spring Security / OAuth 2.0 / OpenID Connect / JWT             | Authentication, roles, ownership checks and service credentials across backend applications.               | `service-support/`, `api-gateway/`, `ai-service/`, `cart-service/`, `catalog-service/`, `inventory-service/`, `notification-service/`, `order-service/`, `payment-service/`, `frontend/src/auth.ts`, `infra/keycloak/`          |
|    7 | Jackson                                                        | JSON serialization and parsing; imported in 30 Java files.                                                 | `ai-service/`, `cart-service/`, `catalog-service/`, `inventory-service/`, `notification-service/`, `order-service/`, `payment-service/`, `service-support/`, `workflow-tests/`                                                  |
|    8 | Spring JDBC / Spring Data JPA / Hibernate                      | Catalog uses JPA and JDBC; order, inventory, payment, notification and AI use JDBC.                        | `catalog-service/` (JPA and JDBC); `order-service/`, `inventory-service/`, `payment-service/`, `notification-service/`, `ai-service/`, `service-support/` (JDBC)                                                                |
|    9 | Apache Kafka 4.2.2 / Spring Kafka                              | Business events, transactional outboxes, consumer deduplication and saga coordination across six services. | `catalog-service/`, `inventory-service/`, `order-service/`, `payment-service/`, `notification-service/`, `ai-service/`, `contracts/`, `service-support/`                                                                        |
|   10 | Flyway                                                         | Versioned database migrations in six application services.                                                 | `catalog-service/`, `inventory-service/`, `order-service/`, `payment-service/`, `notification-service/`, `ai-service/`                                                                                                          |
|   11 | JUnit / AssertJ / Mockito / Spring Boot Test / MockMvc         | Backend unit, HTTP, integration and workflow testing; JUnit and AssertJ each appear in 17 Java files.      | `contracts/src/test/`, `catalog-service/src/test/`, `inventory-service/src/test/`, `order-service/src/test/`, `payment-service/src/test/`, `notification-service/src/test/`, `ai-service/src/test/`, `workflow-tests/src/test/` |
|   12 | Micrometer / Spring Boot Actuator / Prometheus 3.15            | Health checks and metrics across all eight backend applications.                                           | `api-gateway/`, `ai-service/`, `cart-service/`, `catalog-service/`, `inventory-service/`, `notification-service/`, `order-service/`, `payment-service/`, `infra/prometheus/`, `infra/grafana/`                                  |
|   13 | Docker / Docker Compose                                        | Container builds and local deployment of applications and infrastructure.                                  | `infra/`, `frontend/`, `.github/workflows/`                                                                                                                                                                                     |
|   14 | Kubernetes / Kustomize / kind                                  | Local cluster deployment, persistent storage, probes, autoscaling and deployment overlays.                 | `infra/k8s/`, `infra/kind.yaml`, `infra/kustomization.yaml`, `deploy/`                                                                                                                                                          |
|   15 | TypeScript / React 19.3 / React DOM                            | Storefront and AI Copilot interface; 16 TS/TSX files, about 2,700 lines including tests and configuration. | `frontend/src/`, `frontend/e2e/`, `frontend/package.json`                                                                                                                                                                       |
|   16 | Python 3                                                       | Demo, model preparation, knowledge import, evaluation and verification automation; 18 scripts.             | `scripts/`                                                                                                                                                                                                                      |
|   17 | HTML / CSS                                                     | Frontend page structure and styling; approximately 1,200 lines.                                            | `frontend/index.html`, `frontend/src/`                                                                                                                                                                                          |
|   18 | Bash / shell                                                   | Startup, shutdown, deployment and verification automation; 12 scripts.                                     | `scripts/`, `.github/workflows/`                                                                                                                                                                                                |
|   19 | Elasticsearch 9.4.5 / Spring Data Elasticsearch                | Catalog search and AI knowledge retrieval, including vector search.                                        | `catalog-service/`, `ai-service/`, `infra/compose.yaml`, `infra/k8s/`                                                                                                                                                           |
|   20 | Redis 8.10.2 / Spring Data Redis / Lua                         | Product caching, shopping carts, TTLs and atomic Redis operations.                                         | `cart-service/`, `catalog-service/`, `infra/compose.yaml`, `infra/k8s/`                                                                                                                                                         |
|   21 | Keycloak 26.8 / keycloak-js / PKCE                             | Identity provider, browser login and service authentication.                                               | `service-support/`, `api-gateway/`, `ai-service/`, `cart-service/`, `catalog-service/`, `inventory-service/`, `notification-service/`, `order-service/`, `payment-service/`, `frontend/src/auth.ts`, `infra/keycloak/`          |
|   22 | Spring AI 2.0.1 / Ollama 0.40.6                                | Local model integration in the optional AI service.                                                        | `ai-service/`, `infra/compose.yaml`, `deploy/ai/`, `scripts/`                                                                                                                                                                   |
|   23 | Llama 3.1 8B / nomic-embed-text                                | AI generation, tool requests and 768-dimensional knowledge embeddings.                                     | `ai-service/`, `infra/ai/`, `deploy/ai/`, `scripts/`                                                                                                                                                                            |
|   24 | Node.js 26 / npm / Vite 8.3                                    | Frontend dependency installation, development server and production builds.                                | `frontend/`                                                                                                                                                                                                                     |
|   25 | Vitest / React Testing Library / jest-dom / jsdom              | Frontend unit and component testing.                                                                       | `frontend/src/`, `frontend/package.json`, `frontend/vite.config.ts`                                                                                                                                                             |
|   26 | Playwright                                                     | Browser-based end-to-end testing.                                                                          | `frontend/e2e/`, `frontend/playwright.config.ts`                                                                                                                                                                                |
|   27 | springdoc / OpenAPI / Swagger UI                               | API documentation across seven backend applications.                                                       | `ai-service/`, `cart-service/`, `catalog-service/`, `inventory-service/`, `notification-service/`, `order-service/`, `payment-service/`                                                                                         |
|   28 | Nginx 1.30                                                     | Serves the built frontend and supports the local kind inference proxy.                                     | `frontend/nginx.conf`, `infra/ai/*.conf`                                                                                                                                                                                        |
|   29 | Grafana 13.2                                                   | Dashboards for application and infrastructure metrics.                                                     | `infra/grafana/`, `infra/compose.yaml`, `infra/k8s/infrastructure.yaml`                                                                                                                                                         |
|   30 | Testcontainers / Zonky Embedded PostgreSQL / Spring Kafka Test | Real infrastructure and broker support for integration tests.                                              | `ai-service/src/test/`, `catalog-service/src/test/`, `inventory-service/src/test/`, `order-service/src/test/`, `payment-service/src/test/`, `workflow-tests/src/test/`                                                          |
|   31 | ESLint / typescript-eslint / Prettier                          | Frontend linting and formatting tooling.                                                                   | `frontend/`                                                                                                                                                                                                                     |
|   32 | GitHub Actions                                                 | Automated repository verification pipeline.                                                                | `.github/workflows/`                                                                                                                                                                                                            |
|   33 | Traefik 3.7 / Calico 3.33                                      | Kubernetes ingress routing and network-policy enforcement.                                                 | `infra/k8s/ingress.yaml`, `infra/k8s/policies.yaml`, `infra/kind.yaml`                                                                                                                                                          |
|   34 | k6 2.3                                                         | Load testing and performance scenarios.                                                                    | `load-tests/`                                                                                                                                                                                                                   |
|   35 | Server-Sent Events (SSE)                                       | Streams AI run updates from the backend to the browser.                                                    | `ai-service/src/main/`, `frontend/src/`                                                                                                                                                                                         |

Version pins are recorded in the [backend build](pom.xml), [frontend manifest](frontend/package.json),
[Compose configuration](infra/compose.yaml) and [dependency notes](docs/dependencies.md).

## Prerequisites

- Java 25, Node 26 and npm; Maven 3.10.0 is supplied by `./mvnw`.
- For persistent development: Docker with Compose; allow about 12 GB Docker memory on the specified 32 GB Mac.
- For kind: kind 0.33.0 and kubectl compatible with Kubernetes 1.37. The kind node image is pinned by digest in
  `infra/kind.yaml`.
- Without Docker, the test scripts build/run real native PostgreSQL, Redis, Elasticsearch and Keycloak. Native builds
  require a C compiler/make, curl, tar, unzip and Python 3.12+. Linux tests require an unprivileged user because
  PostgreSQL cannot run as root.

## Build and verify

```bash
# Install native test dependencies; these are separate from persistent Compose infrastructure.
bash scripts/bootstrap-test-tools.sh
./mvnw -B verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test
npm ci --prefix frontend
npm run --prefix frontend lint
npm run --prefix frontend test
npm run --prefix frontend build
```

The native test tools live in ignored `.cache/tools`. No runtime database is replaced with H2 or an
in-memory implementation. On a machine with Docker, the workflow fixtures can instead use
Testcontainers for missing Redis/Elasticsearch/Keycloak executables. That fallback compiles but has
not run here. Override native locations with `REDIS_SERVER`, `ELASTICSEARCH_HOME` or
`KEYCLOAK_HOME`.

For the browser-tested, ephemeral local stack, run the following in separate terminals after the
build above:

```bash
# Test harness only: stopping this stack ends its temporary database lifetime.
bash scripts/browser-stack.sh
npm run --prefix frontend dev
```

Open [localhost:5173](http://localhost:5173). This harness launches real infrastructure and all
services, seeds six synthetic products/stock, and keeps authoritative state in real PostgreSQL
databases until stopped. Use this harness for tests and Compose/PVCs for persistent development.
Stop it with `touch .cache/verification/browser-stop` and wait for ordered
application/infrastructure shutdown before restarting. To run Chrome verification against it:

```bash
npm run --prefix frontend test:e2e
```

Local Playwright uses installed Google Chrome; CI installs bundled Chromium. The suite performs PKCE
login/session refresh, SUPPORT monitoring, purchase confirmation, declined payment/compensation
notifications, search, mobile layout, product creation and stock administration.

## Docker Compose

```bash
cp infra/.env.example infra/.env   # only if the file does not already exist
# Starts the full commerce stack with AI disabled; model-enabled startup is shown below.
bash scripts/compose-up.sh
```

The startup script renders ignored monitoring configuration, validates Compose, builds images and
waits for health checks. The default is `--ai=off`; startup and full browser workflows have been
verified locally. Defaults are public local-development credentials. Keep overrides in ignored
`infra/.env`; the renderer keeps Prometheus's client secret consistent with Keycloak.

| URL                   | Purpose          |
|-----------------------|------------------|
| http://localhost:3000 | React storefront |
| http://localhost:8080 | API Gateway      |
| http://localhost:8180 | Keycloak         |
| http://localhost:9090 | Prometheus       |
| http://localhost:3001 | Grafana          |

Public local users: `alice` / `local-alice-only`, `bob` / `local-bob-only`, `admin` /
`local-admin-only`, `support` / `local-support-only`. Keycloak bootstrap administrator:
`local-admin` / `local-admin-only`. Grafana: `local-admin` / `local-grafana-only`. These fixtures
contain no real customer data and must remain local.

Compose starts PostgreSQL alone by default. Profiles `events`, `cache`, `search`, `identity` add
individual dependencies; `apps` starts applications and their infrastructure; `full` also adds
monitoring. For example:

```bash
docker compose -f infra/compose.yaml --profile events --profile cache up -d
docker compose -f infra/compose.yaml --profile full logs --tail=100 order-service
docker compose -f infra/compose.yaml --profile full down
```

Named volumes preserve development data. `down -v` removes them and is not a repair/upgrade step.
Initial database provisioning is additive and owner-checked if explicitly rerun after interrupted
setup. AI startup separately verifies its owned role/database for existing volumes without changing
existing passwords.

## Kubernetes

```bash
# Choose Compose or kind for these localhost endpoints; their published ports overlap.
bash scripts/kind-up.sh
bash scripts/kind-diagnostics.sh
```

The script builds/loads local application images, applies Kustomize resources, installs the pinned
metrics-server, discovers exact API addresses for the controller network policy, and waits for
workloads. Frontend ingress is at http://localhost:3000 and Keycloak at http://localhost:8180.
Service-to-service addresses use cluster DNS. Public OIDC issuer URLs intentionally use the
browser-visible localhost address.

For cluster monitoring, port-forward `service/grafana 3001:3000` and `service/prometheus 9090:9090`
in namespace `commerceflow` using context `kind-commerceflow`, in separate terminals.

Read [operations](docs/operations.md) before recovery/teardown. A single-node kind cluster is not a
production high-availability deployment. The startup script installs pinned Calico on a new cluster;
actual allowed/denied connections were verified. It refuses to erase or silently migrate an existing
default-kindnet cluster.

## Optional local Llama copilot

```bash
# Host mode requires Ollama already running locally. Generation and retrieval use separate pinned models:
# llama3.1:8b for chat/tool proposals and nomic-embed-text:latest for embeddings.
python3 scripts/prepare-ai-models.py --warmup  # explicit download/digest/inference checks
# Startup checks prepared models; it does not silently download them or fall back to a cloud provider.
bash scripts/compose-up.sh --ai=host
python3 scripts/demo.py seed                # synthetic demo stock only; no pending reservations
# Imports curated policy documents through the AI API; indexing then proceeds asynchronously.
python3 scripts/import-ai-knowledge.py
```

Open the storefront, sign in as alice and open Local copilot. Ask about the synthetic 30-day return
window, compare Workspace products, or explain an owned order. Cart changes require the exact typed
proposal and explicit confirmation. No checkout, payment, refund or inventory write tool exists.
SUPPORT can investigate through its scoped read route. Use `--ai=off` to withdraw AI routes and stop
optional workers while retaining all data.

<!-- Keep host-mode verification separate from CPU co-location: they have different memory/network requirements.
     A passing host deployment does not close the CPU-profile or 32 GiB hardware acceptance gates. -->
For native-model kind, use `bash scripts/kind-up.sh --ai=host`; `--skip-build` reuses already-built
local images. The host runtime stays on localhost. A fixed inference-only proxy on the private kind
Docker network handles Docker Desktop routing, publishes no host port and is allowed by an exact
endpoint policy. CPU mode uses explicit model preparation and a retained volume/PVC; read
[AI operations](docs/ai-operations.md) for capacity/status and recovery commands.

## Five-minute demonstration

With the ephemeral browser stack and frontend running:

1. Sign in as alice, add a product, use Approved, and place an order. Observe Confirmed and live notifications.
2. Return to the collection, add another product, choose Declined, and place an order. Observe Cancelled and the
   inventory-release notification.
3. Search for `mechanical`, filter category/price and view product details. Search is backed by Elasticsearch; detail
   reads use Redis while checkout bypasses cache for current prices.
4. Sign out and sign in as admin. Inspect orders, create/edit a product, and read/update stock. Backend integration
   tests independently verify exact compensation quantities.
5. In a full monitoring environment, open Grafana's CommerceFlow / Service Health and Order Processing
   dashboards. Check outbox, outcome, cache, search, JVM and HTTP metrics. For native monitoring commands
   see [observability](docs/observability.md).

The complete native demonstration was executed successfully:

```bash
SERVICE_CLIENT_SECRET=local-test-service-only bash scripts/demo.sh
```

It seeds only loopback synthetic data, runs the browser cases, checks exact committed/released
stock, captures authenticated metrics and checks both dashboards. Compose uses its own documented
service-client secret. The full demonstration and recovery evidence for Compose/kind are recorded in
the dated verification supplement.

## Documentation

- [Project file guide](docs/file-guide.md): responsibilities and maintenance notes for every maintained file,
  including JSON, migrations and model inputs whose contents must remain unchanged during comment-only edits.

- [Authoritative specification](CODEX_PROMPT.md), [engineering rules](AGENTS.md), [phase plan](PLAN.md)
- [AI copilot implementation plan](AI_PLAN.md): implementation, verified progress and explicit remaining release
  requirements.
- [AI operations](docs/ai-operations.md), [API](docs/ai-api.md), [security](docs/ai-security.md), [evaluation](docs/ai-evaluation.md).
- [Architecture](docs/architecture.md), [data model](docs/data-model.md), [API](docs/api.md), [events](docs/events.md)
- [Security](docs/security.md), [search consistency](docs/search.md), [observability](docs/observability.md)
- [Recovery](docs/recovery.md), [operations](docs/operations.md), [dependencies](docs/dependencies.md)
- [Verification](docs/verification.md), [measured load results](docs/load-tests.md), [architecture decisions](docs/adr/0001-service-boundaries.md)

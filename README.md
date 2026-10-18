# CommerceFlow

A Java 25 / Spring Boot event-driven commerce application with a React storefront, Keycloak login, PostgreSQL-owned
service data, Kafka checkout orchestration, Redis carts/cache and Elasticsearch search.

The latest regression passes **82 Java tests**, **5 frontend unit tests**, lint/build and **8 real-browser cases**.
The optional local Llama copilot adds cited retrieval, authorized tools, durable investigations and explicitly confirmed
cart changes. Its frozen 80-case hybrid corpus ran three times: 240 scenario checks passed with zero observed unsafe
business effects. These checks do not certify every factual assertion; see [AI evaluation](docs/ai-evaluation.md).

The full Compose host-model stack and native-model kind deployment run with real infrastructure. Calico allow/deny
checks and real process/pod recovery have execution evidence. The complete CPU model co-location profile exceeds the
current 7.75 GiB Docker VM; standalone CPU generation/embeddings and model-volume restart pass. The host has 48 GiB,
so the original 32 GiB hardware target remains unverified. GitHub Actions definitions are validated but have not run
remotely. [PLAN.md](PLAN.md), [AI_PLAN.md](AI_PLAN.md) and [verification](docs/verification.md) retain exact
scope/results.
No commits, remote deployment or volume deletion were performed.

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
bash scripts/bootstrap-test-tools.sh
./mvnw -B verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test
npm ci --prefix frontend
npm run --prefix frontend lint
npm run --prefix frontend test
npm run --prefix frontend build
```

The native test tools live in ignored `.cache/tools`. No runtime database is replaced with H2 or an in-memory
implementation. On a machine with Docker, the workflow fixtures can instead use Testcontainers for missing
Redis/Elasticsearch/Keycloak executables. That fallback compiles but has not run here. Override native locations with
`REDIS_SERVER`, `ELASTICSEARCH_HOME` or `KEYCLOAK_HOME`.

For the browser-tested, **ephemeral** local stack, run the following in separate terminals after the build above:

```bash
bash scripts/browser-stack.sh
npm run --prefix frontend dev
```

Open [localhost:5173](http://localhost:5173). This harness launches real infrastructure and all services, seeds six
synthetic products/stock, and keeps authoritative state in real PostgreSQL databases until stopped. It is a test
convenience; use Compose/PVCs for persistent development. Stop it with `touch .cache/verification/browser-stop` and
wait for ordered application/infrastructure shutdown before restarting. To run Chrome verification against it:

```bash
npm run --prefix frontend test:e2e
```

Local Playwright uses installed Google Chrome; CI installs bundled Chromium. The suite performs PKCE login/session
refresh, SUPPORT monitoring, purchase confirmation, declined payment/compensation notifications, search, mobile layout,
product creation and stock administration.

## Docker Compose

```bash
cp infra/.env.example infra/.env   # only if the file does not already exist
bash scripts/compose-up.sh
```

This renders ignored monitoring configuration, validates Compose, builds images and waits for health. The default is
`--ai=off`; startup and full browser workflows have been verified locally. Defaults are public local-development
credentials. Keep overrides in ignored `infra/.env`; the renderer keeps Prometheus's client secret consistent with
Keycloak.

| URL                   | Purpose          |
|-----------------------|------------------|
| http://localhost:3000 | React storefront |
| http://localhost:8080 | API Gateway      |
| http://localhost:8180 | Keycloak         |
| http://localhost:9090 | Prometheus       |
| http://localhost:3001 | Grafana          |

Public local users: `alice` / `local-alice-only`, `bob` / `local-bob-only`, `admin` / `local-admin-only`, `support` /
`local-support-only`. Keycloak bootstrap administrator: `local-admin` / `local-admin-only`. Grafana: `local-admin` /
`local-grafana-only`. These fixtures contain no real customer data and must remain local.

Compose starts PostgreSQL alone by default. Profiles `events`, `cache`, `search`, `identity` add individual
dependencies; `apps` starts applications and their infrastructure; `full` also adds monitoring. For example:

```bash
docker compose -f infra/compose.yaml --profile events --profile cache up -d
docker compose -f infra/compose.yaml --profile full logs --tail=100 order-service
docker compose -f infra/compose.yaml --profile full down
```

Named volumes preserve development data. `down -v` removes them and is not a repair/upgrade step. Initial database
provisioning is additive and owner-checked if explicitly rerun after interrupted setup. AI startup separately verifies
its owned role/database for existing volumes without changing existing passwords.

## Kubernetes

```bash
bash scripts/kind-up.sh
bash scripts/kind-diagnostics.sh
```

The script builds/loads local application images, applies Kustomize resources, installs the pinned metrics-server,
discovers exact API addresses for the controller network policy, and waits for workloads. Frontend ingress is
at http://localhost:3000 and Keycloak at http://localhost:8180. Service-to-service addresses use cluster DNS. Public
OIDC issuer URLs intentionally use the browser-visible localhost address.

For cluster monitoring, port-forward `service/grafana 3001:3000` and `service/prometheus 9090:9090` in namespace
`commerceflow` using context `kind-commerceflow`, in separate terminals.

Read [operations](docs/operations.md) before recovery/teardown. A single-node kind cluster is not a production
high-availability deployment. The startup script installs pinned Calico on a new cluster; actual allowed/denied
connections were verified. It refuses to erase or silently migrate an existing default-kindnet cluster.

## Optional local Llama copilot

```bash
python3 scripts/prepare-ai-models.py --warmup  # explicit download/digest/inference checks
bash scripts/compose-up.sh --ai=host
python3 scripts/demo.py seed                # synthetic demo stock only; no pending reservations
python3 scripts/import-ai-knowledge.py
```

Open the storefront, sign in as alice and open **Local copilot**. Ask about the synthetic 30-day return window, compare
Workspace products, or explain an owned order. Cart changes require the exact typed proposal and explicit confirmation.
No checkout, payment, refund or inventory write tool exists. SUPPORT can investigate through its scoped read route.
Use `--ai=off` to withdraw AI routes and stop optional workers while retaining all data.

For native-model kind, use `bash scripts/kind-up.sh --ai=host`; `--skip-build` reuses already-built local images.
The host runtime stays on localhost. A fixed inference-only proxy on the private kind Docker network handles Docker
Desktop routing, publishes no host port and is allowed by an exact endpoint policy. CPU mode uses explicit model
preparation and a retained volume/PVC; read [AI operations](docs/ai-operations.md) for capacity/status and recovery
commands.

## Five-minute demonstration

With the ephemeral browser stack and frontend running:

1. Sign in as alice, add a product, use **Approved**, and place an order. Observe **Confirmed** and live notifications.
2. Return to the collection, add another product, choose **Declined**, and place an order. Observe **Cancelled** and the
   inventory-release notification.
3. Search for `mechanical`, filter category/price and view product details. Search is backed by Elasticsearch; detail
   reads use Redis while checkout bypasses cache for current prices.
4. Sign out and sign in as admin. Inspect orders, create/edit a product, and read/update stock. Backend integration
   tests independently verify exact compensation quantities.
5. In a full monitoring environment, open Grafana's **CommerceFlow / Service Health** and **Order Processing**
   dashboards. Check outbox, outcome, cache, search, JVM and HTTP metrics. For native monitoring commands
   see [observability](docs/observability.md).

The complete native demonstration was executed successfully:

```bash
SERVICE_CLIENT_SECRET=local-test-service-only bash scripts/demo.sh
```

It seeds only loopback synthetic data, runs the browser cases, checks exact committed/released stock, captures
authenticated metrics and checks both dashboards. Compose uses its own documented service-client secret. The full
demonstration and recovery evidence for Compose/kind are recorded in the dated verification supplement.

## Documentation

- [Authoritative specification](CODEX_PROMPT.md), [engineering rules](AGENTS.md), [phase plan](PLAN.md)
- [AI copilot implementation plan](AI_PLAN.md) — implementation, verified progress and explicit remaining release gates.
- [AI operations](docs/ai-operations.md), [API](docs/ai-api.md), [security](docs/ai-security.md), [evaluation](docs/ai-evaluation.md).
- [Architecture](docs/architecture.md), [data model](docs/data-model.md), [API](docs/api.md), [events](docs/events.md)
- [Security](docs/security.md), [search consistency](docs/search.md), [observability](docs/observability.md)
- [Recovery](docs/recovery.md), [operations](docs/operations.md), [dependencies](docs/dependencies.md)
- [Verification](docs/verification.md), [measured load results](docs/load-tests.md), [architecture decisions](docs/adr/0001-service-boundaries.md)

# Run and demonstrate CommerceFlow

Use this guide to start the complete local application, demonstrate checkout and the Llama copilot, inspect monitoring,
and stop without deleting data. The recommended Mac setup is **Docker Compose with native Ollama**.

## 1. Check prerequisites

Start Docker Desktop. Install Python 3.12+ and Ollama before using the commands below. Allow approximately 12 GB of
Docker memory for the commerce stack; native Ollama uses host memory separately. The first model preparation may
download approximately 5.2 GB. See [AI prerequisites](ai-prerequisites.md) for the detailed capacity requirements.

Run commands from the repository root:

```bash
cd /Users/osman/IdeaProjects/commerceflow
docker info
docker compose version
python3 --version
```

Java and frontend images build inside Docker. Host Java 25 is needed for Maven development/tests; Node 26, npm and
Google Chrome are needed for the optional local automated browser demonstration.

Compose and kind publish the same storefront and Keycloak ports. Use one deployment at a time. If this repository's
kind node is currently running, pause it before starting Compose:

```bash
# Only needed when this project's kind node is running; its container/PVC data is retained.
docker stop commerceflow-control-plane
```

**Skip this step if you have not previously started `bash scripts/browser-stack.sh` or run services directly on
your Mac.** Here, “native test stack” means application and infrastructure processes running directly on your Mac,
outside Docker. The browser-test harness starts a temporary set of real services, including the gateway on port 8080
and Keycloak on port 8180. Compose needs those same ports, so both stacks cannot use them at the same time.

If `bash scripts/browser-stack.sh` is currently running, open another terminal at the repository root and run:

```bash
mkdir -p .cache/verification
touch .cache/verification/browser-stop
```

`touch` creates an empty marker file. The running harness checks for that file and treats its presence as a request
to stop. It closes its application services first, then its test infrastructure. “Wait for ordered shutdown” means
wait until the original `browser-stack.sh` command finishes and its terminal returns to the shell prompt before
starting Compose. The harness removes an old stop marker on its next startup.

If you also started the separate Vite frontend with `npm run --prefix frontend dev`, stop it with Ctrl+C in its own
terminal. The marker stops only the browser-test harness; it does not stop Docker, kind, Ollama, Vite or applications
you launched separately from IntelliJ. Stop those separately only if they occupy ports needed by your chosen stack.

## 2. Start Ollama

Check whether the native runtime is already available:

```bash
curl -fsS http://127.0.0.1:11434/api/version
```

If it is unavailable, run the following in a separate terminal and leave that terminal running. If the Ollama desktop
application already provides the endpoint, there is no need to start a second server.

```bash
ollama serve
```

Back in the project terminal, explicitly prepare and warm up the pinned models:

```bash
python3 scripts/prepare-ai-models.py --warmup
```

The script checks model digests and performs real inference with both models:

| Model                     | Purpose                                        |
|---------------------------|------------------------------------------------|
| `llama3.1:8b`             | Answer generation and structured tool requests |
| `nomic-embed-text:latest` | Separate 768-dimensional retrieval embeddings  |

Preparation downloads missing weights explicitly. Application startup checks existing models and does not download
them or switch to cloud inference. To verify already prepared models without downloading:

```bash
python3 scripts/prepare-ai-models.py --check --warmup
```

## 3. Start all application components

```bash
bash scripts/compose-up.sh --ai=host
```

The script creates `infra/.env` from the documented example only when it is missing, renders local monitoring
configuration, checks container-to-host model access, provisions the AI-owned database additively, builds images and
waits for health checks. Existing environment overrides and persisted volumes are retained. First builds take longer
than later starts; the application readiness wait is separate from model preparation.

This starts the following components:

| Group                 | Components                                                                |
|-----------------------|---------------------------------------------------------------------------|
| Browser               | React storefront served by nginx                                          |
| Commerce applications | Catalog, Cart, Order, Inventory, Payment, Notification and API Gateway    |
| AI application        | AI service for conversations, retrieval, tools and confirmed Cart changes |
| Infrastructure        | PostgreSQL, Kafka, Redis, Elasticsearch and Keycloak                      |
| Monitoring            | Prometheus and Grafana                                                    |
| Model runtime         | Native Ollama started in step 2                                           |

Check status and gateway readiness:

```bash
docker compose -f infra/compose.yaml --profile full --profile ai ps
curl -fsS http://localhost:8080/actuator/health/readiness
```

The readiness response should report `UP`. Native Ollama is a host process and therefore does not appear in the
Compose service list. Individual backend application ports remain internal; use the gateway or storefront.

For a commerce-only demonstration, use `bash scripts/compose-up.sh --ai=off` and omit the model/knowledge/copilot steps.
AI is off when no option is supplied to the startup script.

## 4. Seed synthetic products and import policy knowledge

```bash
python3 scripts/demo.py seed
python3 scripts/import-ai-knowledge.py
```

Seeding creates six synthetic products: Studio mechanical keyboard, Everyday headphones, Morning ceramic mug,
Arc desk lamp, Daily canvas tote and Pocket notebook. It sets their available stock to 25 and refuses to reset stock
while reservations remain pending. Run it before the demo; rerunning it resets those demo stock quantities.

The importer uses the ADMIN API for the curated policy fixtures and waits for successful indexing. Complete it before
asking the copilot policy questions. These scripts use the documented default local demo accounts.

## 5. Open the applications and sign in

| Application             | Address                       | Default local login                    |
|-------------------------|-------------------------------|----------------------------------------|
| Storefront              | <http://localhost:3000>       | `alice` / `local-alice-only`           |
| API Gateway             | <http://localhost:8080>       | API entry point used by the storefront |
| Keycloak administration | <http://localhost:8180/admin> | `local-admin` / `local-admin-only`     |
| Prometheus              | <http://localhost:9090>       | Local monitoring UI                    |
| Grafana                 | <http://localhost:3001>       | `local-admin` / `local-grafana-only`   |

Additional storefront accounts:

| Role            | Username  | Password             |
|-----------------|-----------|----------------------|
| Second customer | `bob`     | `local-bob-only`     |
| Administrator   | `admin`   | `local-admin-only`   |
| Support         | `support` | `local-support-only` |

These are deliberately public local-development fixtures. Use the credentials matching your existing local overrides
if you have changed them. Changing a password in `infra/.env` does not rotate an existing PostgreSQL role password.

## 6. Demonstrate commerce workflows

1. Sign in to the storefront as **alice**.
2. Search for `mechanical`, filter the catalog, and inspect a product. This exercises Elasticsearch search and catalog
   detail reads.
3. Add a product to the cart. This exercises the authenticated Redis-backed Cart service.
4. Choose **Approved** at checkout and place the order. Wait for **Confirmed**, then inspect its notification.
   This crosses Order, Kafka, Inventory, Payment and Notification services.
5. Place another order using **Declined**. Wait for **Cancelled** and the inventory-release notification. This
   demonstrates compensation rather than fulfillment.
6. Sign out and sign in as **admin**. Inspect orders, create or edit a product, and inspect/update its inventory.
7. Sign in as **bob** to demonstrate a separate customer's cart and order history.

Payment behavior is simulated locally; no real payment provider or customer data is involved. Order creation starts
an asynchronous saga, so wait for a terminal state before describing an order as fulfilled or compensated.

## 7. Demonstrate the local AI copilot

Sign in as **alice**, open **Local copilot**, and try these requests one at a time:

1. **Policy retrieval:** “What is the synthetic return window? Cite the policy.” Open the returned source citation.
2. **Product comparison:** “Compare the Studio mechanical keyboard and Arc desk lamp.” Inspect the product sources.
3. **Owned order explanation:** “Explain order `<your-order-UUID>`.” Use an order belonging to alice.
4. **Proposed Cart change:** “Add one Arc desk lamp to my cart.” Review the exact product, quantity, price and revision,
   then explicitly confirm the proposal. Refresh/inspect the Cart result.
5. **Rejected proposal:** Request another Cart change and reject it to demonstrate that a suggestion alone does not
   perform the mutation.

Sign in as **support** and select **Support investigation** to demonstrate the scoped order-explanation read path.
The copilot has no checkout, payment, refund or inventory-write tool. Checkout remains an explicit storefront action.

Generation may take time on a local model. Progress represents committed run state; it is not a stream of hidden
reasoning or partially accepted answer text. Use reconnect to inspect saved progress after a connection interruption.

## 8. Show monitoring

Open Grafana's **CommerceFlow** folder and inspect:

- **Service Health:** application, JVM and HTTP metrics.
- **Order Processing:** saga outcomes, compensation and event-delivery activity.
- **CommerceFlow Local Copilot:** model, tool, retrieval and run latency, queue state and approval outcomes.

In Prometheus, run:

```promql
up{job="commerceflow"}
```

With AI enabled, expect eight Java-service targets with value `1` after a scrape completes. Generate requests first
when demonstrating latency/activity panels. For an authenticated snapshot from the Compose stack:

```bash
AI_ENABLED=true METRICS_MODE=compose python3 scripts/metrics-snapshot.py
```

## 9. Optionally run the automated AI demonstration

On the already started, seeded host-model stack, with Node 26/npm and Google Chrome available:

```bash
npm ci --prefix frontend
bash scripts/ai-demo.sh --target=compose
```

This imports knowledge, runs real browser copilot scenarios, checks confirmed/stale/rejected Cart changes, exercises
AI process-loss and lost-response recovery, and checks fresh post-restart monitoring data. It creates synthetic
activity and temporarily restarts the AI service. It does not reseed stock. Let any manual investigations finish
before running the recovery demonstration.

For commerce browser scenarios against the same built storefront:

```bash
E2E_BASE_URL=http://localhost:3000 npm run --prefix frontend test:e2e -- e2e/storefront.spec.ts
```

Use [AI evaluation](ai-evaluation.md) for the separate frozen-corpus and load-testing procedures. An operational demo
is not independent certification of every generated factual assertion.

## 10. Inspect failures and stop safely

Inspect application status and bounded recent logs:

```bash
docker compose -f infra/compose.yaml --profile full --profile ai ps
docker compose -f infra/compose.yaml --profile full --profile ai \
  logs --tail=100 api-gateway order-service ai-service
```

| Symptom                             | First check                                                                  |
|-------------------------------------|------------------------------------------------------------------------------|
| Port already allocated              | Stop the other Compose/kind/native stack using the same ports.               |
| Model endpoint unavailable          | Check native Ollama's version endpoint, then rerun model preparation checks. |
| Missing or changed model digest     | Run explicit model preparation; startup accepts the pinned digests only.     |
| Empty catalog or insufficient stock | Complete seeding and ensure no unfinished demo orders hold reservations.     |
| Policy answer has no sources        | Check knowledge import completion and AI/indexing logs.                      |
| Containers fail readiness           | Inspect the failing service's logs and Docker memory/disk availability.      |
| Grafana panels are initially empty  | Generate requests and wait for the next authenticated Prometheus scrape.     |

Stop all Compose components while preserving their named volumes:

```bash
docker compose -f infra/compose.yaml --profile full --profile ai --profile ai-model stop
```

If you started `ollama serve` manually, stop that terminal with Ctrl+C when you no longer need inference. The Ollama
desktop application can instead remain running. Restart applications later with `bash scripts/compose-up.sh --ai=host`.
Use `--ai=off` to run commerce while withdrawing AI routes and stopping optional workers.

Do not use `down -v` to repair startup: it deletes persisted development data. See [recovery](recovery.md) for durable
outbox/saga troubleshooting and [AI operations](ai-operations.md) for unknown Cart outcomes and re-authentication.

## Alternative: Kubernetes with native Ollama

Use this after the Compose walkthrough when you want to demonstrate orchestration and network policy. Keep native
Ollama running with its prepared models, stop Compose as shown above, and then run:

```bash
bash scripts/kind-up.sh --ai=host
BASE_URL=http://localhost:3000 python3 scripts/demo.py seed
BASE_URL=http://localhost:3000 python3 scripts/import-ai-knowledge.py
bash scripts/kind-diagnostics.sh
```

If you previously paused this project's existing kind node, start it with `docker start commerceflow-control-plane`
before running the startup script. This path also requires kind and kubectl; use the versions documented in
[dependencies](dependencies.md). A new cluster installs the pinned Calico CNI. Existing kindnet clusters require an
explicit networking migration rather than automatic replacement.

The storefront and Keycloak use the same browser URLs as Compose. In separate terminals, expose monitoring locally:

```bash
kubectl --context kind-commerceflow -n commerceflow port-forward --address=127.0.0.1 service/grafana 3001:3000
```

```bash
kubectl --context kind-commerceflow -n commerceflow port-forward --address=127.0.0.1 service/prometheus 9090:9090
```

For the automated kind demonstration, run `bash scripts/ai-demo.sh --target=kind` on the prepared/seeded stack.
For detailed policy probes, restart durability and CPU model preparation, follow [AI operations](ai-operations.md).
`scripts/kind-down.sh` deletes the cluster and its PVC data; it is not a data-preserving pause command.

Native-model Compose and kind have recorded runtime evidence. The full in-cluster CPU-model stack remains blocked by
the documented 7.75 GiB Docker VM capacity; a standalone CPU inference pass does not close that gate. The original
32 GiB hardware target and remote CI acceptance also remain open. Consult [verification](verification.md) and
[AI_PLAN.md](../AI_PLAN.md) for the exact recorded results and remaining gates.

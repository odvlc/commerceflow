# Local Copilot operations

Use native Ollama on Apple Silicon for the normal development path. CommerceFlow uses pinned local `llama3.1:8b`
Q4_K_M for generation/tools and separate `nomic-embed-text:latest` F16, 768-dimensional embeddings. Models and license
hashes are in `infra/ai/models.lock.json`. There is no paid or cloud inference fallback. Keep one AI application
replica,
one inference slot, context 4096, queue eight and the documented execution budgets.

## Compose host mode

Start Ollama on its default localhost endpoint. Explicitly prepare models before application startup:

```bash
python3 scripts/prepare-ai-models.py --warmup
bash scripts/compose-up.sh --ai=host
python3 scripts/import-ai-knowledge.py
```

The first command may download about 5.2 GB; it checks disk space, prints progress, verifies digests and performs real
inference warmup. Its configurable timeout defaults to 1800 seconds (`--timeout` or `AI_MODEL_PREPARE_TIMEOUT`),
separate
from the 300-second application readiness wait. `--check` never downloads. A changed/missing digest fails clearly.
The host command checks `host.docker.internal:11434` from inside a temporary container, including both model digests.
`AI_HOST_MODEL_URL` can select another port on that same Docker Desktop hostname; no LAN binding is configured.
Native model preparation uses `OLLAMA_BASE_URL` with the corresponding loopback port.

The full storefront is at http://localhost:3000, Keycloak at http://localhost:8180, Grafana at http://localhost:3001.
Use the documented synthetic accounts in the main README. `python3 scripts/demo.py seed` creates six demo products
through authenticated APIs; run it only when no reserved demo stock exists, since it explicitly resets demo stock to 25.
Knowledge import uses real admin PKCE and only the four curated synthetic fixtures. The AI database has its own role,
Flyway migrations and credentials; no other service can write its tables.

## Compose CPU model mode

This mode is separate from native GPU inference and needs sufficient Docker VM RAM/disk. The measured development
VM has 7.75 GiB; the complete commerce stack plus the 6 GiB CPU model limit requires capacity review. Do not interpret
valid Compose configuration as a successful CPU deployment, or silently raise Docker Desktop resource settings.

```bash
# Start only the runtime; a healthy runtime can still have no models.
docker compose -f infra/compose.yaml --profile ai-model up -d --wait ollama
# Explicit model download, disk check inside its persistent volume, digest check and warmup:
OLLAMA_BASE_URL=http://127.0.0.1:11435 python3 scripts/prepare-ai-models.py --container-storage --warmup
bash scripts/compose-up.sh --ai=container
```

Ollama 0.40.6 uses a named `ollama-data` volume and only publishes localhost:11435, leaving native Ollama's port intact.
The application uses `http://ollama:11434` inside Compose. The startup command checks/warmups installed models and
refuses
missing ones; it never silently downloads gigabytes during the application health wait. Runtime health (`ollama list`),
model presence/digests, warmup and application readiness are distinct checks. CPU latency must fit the existing
45-second
inference/120-second run budgets; this mode is not assumed equivalent to native GPU performance.

## Disable and preserve data

```bash
bash scripts/compose-up.sh --ai=off
```

Off is the default. It withdraws AI gateway routes and stops optional AI/model workers while preserving all named
volumes. Commerce health does not depend on AI model readiness. Model health is visible through authenticated
`GET /api/v1/ai/capabilities`; model outages reject new inference messages/resumes with `AI_MODEL_UNAVAILABLE`, and
existing
runs have bounded retries. History and reconciliation do not require a working LLM. Never use `down -v` for upgrades.

For an existing PostgreSQL volume, startup explicitly runs `python3 scripts/provision-ai-database.py`. It adds missing
AI ownership only, preserves an existing password, and refuses an incompatible database owner. Set `AI_DB_PASSWORD` in
ignored `infra/.env`; changing it does not rotate an existing role password. PostgreSQL's initial script is also safe to
rerun explicitly after interrupted first initialization; it does not delete rows or change existing ownership/passwords.

## Recovery and monitoring

A run lease lasts 15 seconds and renews every three seconds. After process loss, persisted active work pauses for fresh
same-owner authentication. Resume with the UI or authenticated run resume API; never persist/replay JWTs from storage.
A committed proposal survives a crash before its waiting-state checkpoint. Inspect exact proposal/hash/version/expiry
before confirmation. An existing execution retains its request key across retries. Never generate a new key to resolve
an unknown Cart outcome. Cancellation prevents future work but cannot retract an already-issued Cart request; its late
acknowledgment remains visible in execution audit. Recovery stops before the Cart memo retention horizon expires.

The Copilot dashboard shows model/text-first-token/tool/retrieval/run latency, queue depth, indexing backlog, approvals
and unknown outcomes. UI events stream committed progress/final state; backend first-token timing is not a browser
partial-text metric. Prompt and hidden-reasoning logging are disabled. Scrapes use the service-account OAuth2
credential.

```bash
AI_ENABLED=true METRICS_MODE=compose python3 scripts/metrics-snapshot.py
python3 scripts/ai-load.py retrieval --iterations=20
python3 scripts/ai-load.py tool --iterations=20
python3 scripts/ai-load.py investigation --iterations=10
```

See `docs/ai-evaluation.md` for model evaluation and isolated warm/cold load protocols. Keep raw browser traces private:
they may contain authorization headers/cookies even when all data is synthetic. Deployment/runtime checks and unresolved
quality or hardware gates remain tracked in `AI_PLAN.md`; this operations guide is not evidence that each mode passed.

## kind with native inference

Use either Compose or kind for the fixed storefront/Keycloak ports, not both simultaneously. Compose `stop` retains
its volumes. To pause a task-owned kind node while using Compose, `docker stop commerceflow-control-plane` preserves
its container/PVC state; `kind-down.sh` is explicit destructive teardown and is not a mode-switch/repair command.

```bash
bash scripts/kind-up.sh --ai=host
BASE_URL=http://localhost:3000 python3 scripts/demo.py seed
BASE_URL=http://localhost:3000 python3 scripts/import-ai-knowledge.py
AI_ENABLED=true E2E_BASE_URL=http://localhost:3000 npm run --prefix frontend test:e2e
python3 scripts/verify-ai-network.py
python3 scripts/ai-recovery-smoke.py --target=kind
python3 scripts/kind-durability.py
```

`--skip-build` reuses existing local images. After rebuilding a mutable `:local` image for an existing Deployment,
load it and explicitly roll that Deployment; initial startup uses the loaded image. AI has one replica/Recreate strategy
and no autoscaling beyond inference capacity. A new cluster disables kindnet and installs pinned Calico; an existing
kindnet cluster is refused without deleting its state. Actual probes verify twelve allowed/denied workload connections.

Native Ollama remains bound to macOS localhost. A nonroot, read-only nginx proxy on the private `kind` Docker network
forwards only tags/version/chat/embed to that runtime; it has no published host port and a 64 MiB limit. A selectorless
`ollama` Service/EndpointSlice and exact /32 egress policy target its discovered address. This resolves the observed
Calico 192.168/16 pool overlap with Docker Desktop's host gateway while preserving policy enforcement. No Apple GPU
access inside Linux pods is assumed; generation runs in the separately verified native macOS process.

The final recovery checker proves actual container termination before pod replacement, fresh-owner authentication,
exact waiting proposal preservation and replay of a withheld real Cart acknowledgment while inference is unavailable.
The durability checker replaces Order/PostgreSQL/Redis pods and interrupts a RUNNING rebuild. It uses a temporary
SUPPORT-only synthetic source, then retains an inactive audited version. A diagnostic lease never bypasses the shared
PostgreSQL session lock. Database restart can briefly leave Keycloak with stale pool connections; the checker waits for
actual fresh PKCE rather than claiming readiness from realm metadata alone.

For Grafana/Prometheus, use localhost-only forwards in separate terminals:

```bash
kubectl --context kind-commerceflow -n commerceflow port-forward --address=127.0.0.1 service/grafana 3001:3000
kubectl --context kind-commerceflow -n commerceflow port-forward --address=127.0.0.1 service/prometheus 9090:9090
```

## kind CPU preparation and current capacity blocker

```bash
bash scripts/kind-up.sh --ai=container
# Once the CPU runtime/PVC can actually schedule, downloads are a separate explicit operation:
python3 scripts/kind-ai-models.py
python3 scripts/kind-ai-models.py --check
```

The current 7.75 GiB node reported `FailedScheduling: Insufficient memory` for the 6 GiB model request; retained
`docs/ai-results/kind-cpu-capacity.json` records the real event. Full CPU kind acceptance is open. The CPU workload was
scaled to zero, the disposable check Job stopped, its PVC retained, and native mode restored. The completed standalone
Docker CPU warmup/volume restart is separate evidence, not a CPU kind deployment pass. Raising Docker VM memory is an
operator decision; no settings were changed by this implementation.

The explicit preparation Job checks free space in its model PVC, model digests and real generation/embedding warmup.
Only that operation temporarily adds Ollama HTTPS egress for registry/CDN downloads. `--check` never opens download
egress or pulls models. If preparation is interrupted, inspect its Job and remove
`networkpolicy/ollama-explicit-model-download` when no preparation is active. Normal inference has no registry egress.

## Ready-stack demonstration and CI evidence

```bash
bash scripts/ai-demo.sh --target=kind     # or --target=compose
```

On a prepared/seeded stack this exercises cited policy, a comparison with two distinct real product sources, owned
order explanation, confirmed/stale/rejected Cart changes, restart/reauthentication/lost-response recovery and fresh
post-restart model metrics. It does not reseed/reset stock. Timer counters reset with the process; the demonstration
performs a fresh investigation and waits for the next authenticated scrape before checking the dashboard data.

CI separates normal real-infrastructure tests/image/schema checks from the explicit `workflow_dispatch` real-model job
on a dedicated `commerceflow-ai` runner. The runner must already have Java 25, Node 26, Python, native build tools and
the two pinned local models; missing models fail rather than skip. Remote jobs have not been executed in this session.
`scripts/collect-safe-evidence.py` exports only synthetic JSON, stripped JUnit results, model identity and the public
catalog screenshot. Upload is gated on successful collection; unknown/stale files or credential patterns fail closed.
Raw traces, authenticated screenshots, HTTP dumps, logs and rendered Secret manifests remain local/private.

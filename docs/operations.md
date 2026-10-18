# Deployment and recovery operations

Compose's `full` profile declares all required infrastructure, seven Java applications, frontend, Prometheus and
Grafana. Java images use Maven/Java 25 multi-stage builds and a non-root runtime. A compiled Java health-check program
avoids requiring curl in the JRE. Frontend uses a Node build and non-root nginx on 8080. Backend ports remain internal
except the gateway; database/broker/cache/search/identity/monitoring development ports bind loopback.

`scripts/compose-up.sh` preserves an existing `.env`, renders ignored Prometheus credentials, builds and waits for
health. CI runs tests before its Docker build matrix; image packaging omits integration execution inside root build
containers because native PostgreSQL cannot run as root. Docker builds and full startup have not run in this workspace.

Kustomize uses service-owned database credentials from an ignored local Secret source, ConfigMaps, application
Deployments, persistent infrastructure StatefulSets, PVCs, probes, requests/limits, rolling updates, HPAs, Traefik
ingress and namespace policies. Kind maps frontend 3000 and Keycloak 8180. Java service addresses use cluster DNS;
issuer URLs stay browser-visible, with a separate internal JWKS URI. The node image comes from kind 0.33.0's official
release and is digest-pinned.

Traefik's namespaced permissions are separated from its cluster-scoped IngressClass read permission. Its API egress rule
is rendered from the created cluster's exact service/endpoint IP addresses; no all-address egress exception is used.
Offline schema checks use the representative private service IP 10.96.0.1, replaced by discovery at startup.
NetworkPolicy enforcement depends on the installed CNI. kind's default CNI is not an isolation test.

The kind startup script sets `vm.max_map_count` only inside the created node, loads application images, applies
resources and installs metrics-server 0.9.0 with the local kind kubelet TLS setting. Stateful workloads wait for PVCs,
application init containers wait for PostgreSQL/Kafka, and startup probes tolerate initialization. These scripts are
syntax-checked; actual cluster behavior is unverified because kind/Docker/kubectl are absent.

Diagnostics: `bash scripts/kind-diagnostics.sh`. Durable order restart check: export a synthetic customer's
`ACCESS_TOKEN` and terminal `ORDER_ID`, then run `bash scripts/kind-recovery.sh`. It compares persisted
ID/customer/status/money/items before and after an order-service rollout. This check has not executed here. Native tests
independently restart the real order application against its existing database.

`scripts/kind-down.sh` explicitly deletes the local cluster and its PVC data. Compose `down` retains named volumes;
`down -v` destroys them. Do not use either destructive data-removal operation to repair an outbox or saga backlog.
Reapplying ConfigMaps mounted via subPath requires rolling the affected workloads; PostgreSQL bootstrap roles require an
empty initial volume or explicit migration/role administration.

This single-node deployment has no infrastructure high availability. Restarts must preserve volumes, credentials and
consumer groups. Broker replication factor one is a local development choice. Production capacity, backups, TLS, secret
distribution and CNI enforcement require separate deployment validation.

## Executed deployment supplement (2026-10-18)

The historical missing-tool statements above are superseded. All nine application images (including AI) build; full
Compose host mode and native-model kind browser workflows pass. Kind now installs Calico, disables Service-link env
injection, publishes Kafka controller DNS before readiness, and grants Traefik the read-only Node discovery required
by its running informer. Exact controller API egress remains unchanged. AI-owned provisioning is additive and waits
for real database readiness. Actual allow/deny, process-loss, database/cart PVC and rebuild recovery evidence is in
[AI operations](ai-operations.md), [AI_PLAN.md](../AI_PLAN.md) and `docs/ai-results/`.

The full CPU model profile is blocked by the current Docker VM memory, and 32 GiB target hardware/remote GitHub Actions
remain unverified. No cluster or volume was erased to repair startup. New UI/AI data is synthetic demonstration data.

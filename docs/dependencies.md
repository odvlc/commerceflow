# Dependency compatibility

Verified against official sources on 2026-10-17. Maven resolves the Boot BOM and exact transitive versions; do not
override managed Spring libraries.

| Component       | Pin                                    | Official evidence and decision                                                                                                                                                                                                   |
|-----------------|----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| JDK             | 25                                     | [Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) include Java 25; local JDK is 25.0.4.1.                                                                                                  |
| Boot            | 4.1.1                                  | Same requirements; parent POM resolved from Maven Central and compiled locally.                                                                                                                                                  |
| Maven           | 3.10.0                                 | [Apache download page](https://maven.apache.org/download.cgi), official wrapper 3.3.4, Java 25 build executed.                                                                                                                   |
| OpenAPI         | 3.1.1                                  | [springdoc compatibility](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot): 3.x supports Boot 4; verify runtime generation in HTTP tests.                                          |
| PostgreSQL      | 18.6-bookworm                          | [Official image](https://hub.docker.com/_/postgres); PostgreSQL 18 volume mount is `/var/lib/postgresql`.                                                                                                                        |
| Test PostgreSQL | 18.6.0 binaries, Zonky 2.2.2           | [Maintainer documentation](https://github.com/zonkyio/embedded-postgres). Real native server for tests without Docker, never runtime infrastructure. ARM macOS profile included.                                                 |
| Kafka           | apache/kafka:4.2.2                     | [Official Docker guide](https://kafka.apache.org/42/getting-started/docker/); JVM KRaft image, internal and host listeners.                                                                                                      |
| Redis           | 8.10.2-alpine                          | [Official supported tags](https://hub.docker.com/_/redis); noeviction prevents silent cart eviction; AOF enabled.                                                                                                                |
| Elasticsearch   | 9.4.5                                  | [Spring Data compatibility matrix](https://docs.spring.io/spring-data/elasticsearch/reference/elasticsearch/versions.html): 6.1.x → Elasticsearch 9.4.5. Real indexing/search/rebuild/outage tests passed with this version.     |
| Gateway         | Cloud BOM 2025.1.3 / Gateway MVC 5.0.3 | [Official release](https://spring.io/blog/2026/08/20/spring-cloud-2025-1-3-has-been-released/) supports Boot 4.1; real gateway/Keycloak integration verified.                                                                    |
| Keycloak        | 26.8.1                                 | [Official downloads](https://www.keycloak.org/downloads), [container configuration](https://www.keycloak.org/server/containers); external PostgreSQL storage. Real PostgreSQL-backed realm, PKCE and service-token tests passed. |
| Prometheus      | 3.15.0                                 | [Official releases](https://github.com/prometheus/prometheus/releases/tag/v3.15.0); native server and promtool executed.                                                                                                         |
| Grafana         | 13.2.4                                 | [Official download](https://grafana.com/grafana/download/13.2.4); native server, datasource and dashboard queries executed.                                                                                                      |
| k6              | 2.3.0                                  | [Official releases](https://github.com/grafana/k6/releases/tag/v2.3.0); native catalog/thread and authenticated checkout runs executed.                                                                                          |

The Boot-managed test broker is Kafka 4.2.1, a different patch from the configured 4.2.2 container image. Native
verification therefore does not establish container-image compatibility. Its lifecycle restart test uses Spring Kafka's
documented [KRaft test-cluster access](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/test/EmbeddedKafkaKraftBroker.html),
preserving broker log directories and listener addresses across broker-server shutdown/startup.

Container tags and configuration are source-checked. Docker is now available (2026-10-18 prerequisite recheck), but
image pulls/startup have not executed here. Image availability and combined runtime compatibility are not yet claimed.

## AI candidate compatibility probe (2026-10-18)

Spring AI BOM 2.0.1 with Ollama and Elasticsearch vector-store starters resolved and compiled against Boot 4.1.1 in an
ignored isolated probe. Selected versions included Elasticsearch client 9.4.5, Spring Core 7.0.9 and Jackson 3.1.5;
API classloading and basic Jackson serialization passed. This is preliminary dependency verification, not actual
inference/tool/vector-query validation or a production dependency pin. See the
[prerequisite assessment](ai-prerequisites.md) and [official compatibility guidance](https://docs.spring.io/spring-ai/reference/getting-started.html).

## Implemented AI module and selected local models (2026-10-18)

`ai-service` uses Spring AI 2.0.1 with the Boot 4.1.1 BOM. Llama `llama3.1:8b` Q4_K_M is the user-selected chat/tool model;
its actual digest is `46e0c10c039e019119339687c3c1757cc81b9da49709a3b3924863ba87ca666e`. Nomic `nomic-embed-text:latest` F16
is the separate 768-dimensional embedding model, digest `0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f`.
Both were downloaded and executed with Ollama 0.40.6; exact details/license hashes are in `infra/ai/models.lock.json`.
Llama uses its Community License; do not assume Apache licensing.
[Official Llama model](https://ollama.com/library/llama3.1:8b), [embedding model](https://ollama.com/library/nomic-embed-text).

The direct capability spike passed generation/tool-request/embedding checks. Full-service model quality and deployment
are separate later acceptance checks, not implied by artifact availability.

AI deployment prerequisite verification (2026-10-18): official `ollama/ollama:0.40.6` manifest resolves for Linux amd64
and arm64. A temporary `curlimages/curl:8.16.0` container actually fetched native Ollama `/api/tags` through
`host.docker.internal:11434`, returning both pinned model names/digests. The existing localhost host binding was retained;
no host-network setting or LAN listener was enabled. This verifies this Docker Desktop host, not every Linux installation.
Sources: [Ollama Docker guide](https://github.com/ollama/ollama/blob/main/docs/docker.mdx) and
[Docker Desktop host connectivity](https://docs.docker.com/desktop/features/networking/networking-how-tos/).
The standalone runtime image existing does not verify a full Compose/kind model deployment; those gates remain separate.

AI deployment additions (2026-10-18): CPU Ollama remains pinned at 0.40.6; the explicit provisioning Job uses the
Docker Official `python:3.15.0-alpine` image (manifest inspected successfully). [Official Python image](https://hub.docker.com/_/python).
Calico 3.33.0 is pinned for enforcing local kind policy; its official kind guide requires disabling kind's default CNI
and documents the manifest installation. Kubernetes 1.37 uses the current API group path. NetworkPolicy enforcement
is still a runtime test, distinct from installation/schema validity.
[Calico kind installation](https://docs.tigera.io/calico/latest/getting-started/kubernetes/kind).

Executed kind networking compatibility fixes: Traefik 3.7's informer requires read-only Node discovery in addition to
EndpointSlices/IngressClass; the official migration guide documents that RBAC change.
[Traefik Kubernetes RBAC migration](https://doc.traefik.io/traefik/v3.7/migrate/v3/).
Calico outgoing NAT excludes destinations inside any configured IP pool. The official 192.168.0.0/16 kind pool overlaps
Docker Desktop's 192.168.65.254 host gateway in this environment; node access succeeded while pod access timed out.
A fixed private `nginx:1.30-alpine` inference-only proxy on the kind Docker network resolved the measured routing issue
without changing native Ollama's loopback binding or bypassing NetworkPolicy. Exact endpoint rules then passed twelve
actual allow/deny probes. [Calico IP pool NAT semantics](https://docs.tigera.io/calico/latest/reference/resources/ippool).

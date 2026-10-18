# Dependency compatibility

Verified against official sources on 2026-10-17. Maven resolves the Boot BOM and exact transitive versions; do not override managed Spring libraries.

| Component | Pin | Official evidence and decision |
|---|---|---|
| JDK | 25 | [Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) include Java 25; local JDK is 25.0.4.1. |
| Boot | 4.1.1 | Same requirements; parent POM resolved from Maven Central and compiled locally. |
| Maven | 3.10.0 | [Apache download page](https://maven.apache.org/download.cgi), official wrapper 3.3.4, Java 25 build executed. |
| OpenAPI | 3.1.1 | [springdoc compatibility](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot): 3.x supports Boot 4; verify runtime generation in HTTP tests. |
| PostgreSQL | 18.6-bookworm | [Official image](https://hub.docker.com/_/postgres); PostgreSQL 18 volume mount is `/var/lib/postgresql`. |
| Test PostgreSQL | 18.6.0 binaries, Zonky 2.2.2 | [Maintainer documentation](https://github.com/zonkyio/embedded-postgres). Real native server for tests without Docker, never runtime infrastructure. ARM macOS profile included. |
| Kafka | apache/kafka:4.2.2 | [Official Docker guide](https://kafka.apache.org/42/getting-started/docker/); JVM KRaft image, internal and host listeners. |
| Redis | 8.10.2-alpine | [Official supported tags](https://hub.docker.com/_/redis); noeviction prevents silent cart eviction; AOF enabled. |
| Elasticsearch | 9.4.5 | [Spring Data compatibility matrix](https://docs.spring.io/spring-data/elasticsearch/reference/elasticsearch/versions.html): 6.1.x → Elasticsearch 9.4.5. Real indexing/search/rebuild/outage tests passed with this version. |
| Gateway | Cloud BOM 2025.1.3 / Gateway MVC 5.0.3 | [Official release](https://spring.io/blog/2026/08/20/spring-cloud-2025-1-3-has-been-released/) supports Boot 4.1; real gateway/Keycloak integration verified. |
| Keycloak | 26.8.1 | [Official downloads](https://www.keycloak.org/downloads), [container configuration](https://www.keycloak.org/server/containers); external PostgreSQL storage. Real PostgreSQL-backed realm, PKCE and service-token tests passed. |
| Prometheus | 3.15.0 | [Official releases](https://github.com/prometheus/prometheus/releases/tag/v3.15.0); native server and promtool executed. |
| Grafana | 13.2.4 | [Official download](https://grafana.com/grafana/download/13.2.4); native server, datasource and dashboard queries executed. |
| k6 | 2.3.0 | [Official releases](https://github.com/grafana/k6/releases/tag/v2.3.0); native catalog/thread and authenticated checkout runs executed. |

The Boot-managed test broker is Kafka 4.2.1, a different patch from the configured 4.2.2 container image. Native verification therefore does not establish container-image compatibility. Its lifecycle restart test uses Spring Kafka's documented [KRaft test-cluster access](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/test/EmbeddedKafkaKraftBroker.html), preserving broker log directories and listener addresses across broker-server shutdown/startup.

Container tags and configuration are source-checked, but image pulls/startup require a Docker daemon, absent from this environment. Image availability and combined runtime compatibility are not yet claimed.

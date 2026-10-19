# Local Spring AI copilot with application-owned orchestration

<!--
Maintenance: Records local model, retrieval and constrained tool execution decisions for the
optional copilot.
-->

Status: accepted for implementation, 2026-10-18. User preference: Llama 3.1 local inference.

Use Java 25 / Boot 4.1.1 with Spring AI 2.0.1, preserving the Boot BOM. The isolated dependency
probe compiled and loaded Ollama/tool/vector APIs with Elasticsearch client 9.4.5 and Jackson 3.1.5.
Call the Spring AI Ollama API one model round at a time. Application code controls tool validation,
authorization, budgets, checkpoints and approvals.

Llama `llama3.1:8b` downloaded and returned an actual generation and structured `searchProducts`
request. Separate `nomic-embed-text:latest` inference returned 768-dimensional embeddings. Runtime
is Ollama 0.40.6. Immutable artifact digests and license hashes are recorded in
`infra/ai/models.lock.json`; Llama uses the Llama 3.1 Community License, not an Apache model
license. Official sources: [Llama](https://ollama.com/library/llama3.1:8b),
[embeddings](https://ollama.com/library/nomic-embed-text),
[Spring AI](https://docs.spring.io/spring-ai/reference/getting-started.html).

Start with 4096 context tokens, temperature 0 and bounded output. Initial direct API observations (not service load
benchmarks): generation 1.85 seconds, tool request 0.76 seconds, first embedding
4.03 seconds. Service quality, tool effects, context limits and repeatable capacity still need
verification. These observations do not establish statistical throughput.

AI state belongs to its own PostgreSQL database. Curated knowledge has a separately owned
Elasticsearch index and versioned Kafka/outbox ingestion. Personal data is fetched through
caller-authorized service APIs, never embedded. Bearer credentials stay in process memory and are
never model context or durable records. Restarted private work requires fresh authorization. Write
actions are prepared by the model but confirmed/executed by application code with stable downstream
idempotency keys and cart preconditions. The existing checkout saga remains authoritative.

Native Ollama provides macOS GPU inference; optional container CPU inference is separately verified
during deployment. Verify the model, services and deployment directly; framework adapters, scripted
unit tests and schema checks cover only part of that behavior. The frozen 80-case evaluation corpus
has 20 holdout cases; targets in AI_PLAN.md remain unverified until executed.

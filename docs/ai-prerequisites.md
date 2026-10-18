# AI task prerequisite assessment

**Historical prerequisite audit.** Its missing-model/deployment observations below preceded implementation on the same
date. Both pinned models now work; native-model Compose/kind deployments and recovery are verified. Current results,
including the actual CPU-memory blocker, are in [AI_PLAN.md](../AI_PLAN.md), [verification](verification.md) and
[AI operations](ai-operations.md). No 32 GiB or remote-CI acceptance is implied.

Checked on 2026-10-18 against [AI_PLAN.md](../AI_PLAN.md), using the current local workspace.

**Result: ready to begin development and finish A0. Real AI execution is blocked by missing local models.
Docker/kind tooling is available, but image/deployment and cluster acceptance remain unverified.**

## Observed environment

| Prerequisite | Observed result | Assessment |
|---|---|---|
| Host | Apple M5 Pro, ARM64, macOS 27.0.1 | Matches the native ARM test environment. |
| Physical memory | 51,539,607,552 bytes = 48 GiB | Exceeds the project's 32 GB target; model/context capacity still needs measurement. |
| Available disk | Approximately 808.7 GiB | Initial headroom available; images/model weights still need provisioning. |
| Java / javac | Oracle Java 25.0.4.1; compiler available | Ready. |
| Maven | Wrapper runs Maven 3.10.0; global `mvn` absent | Ready through `./mvnw`; global installation unnecessary. |
| Node / npm | 26.12.0 / 11.20.0; frontend dependencies present | Ready; lint/test/build pass. |
| Integration tools | Cached real native Redis, Elasticsearch, Keycloak, test PostgreSQL and KRaft | Fresh full Java and browser suites pass. |
| Browser | Google Chrome / Playwright available | Four Chrome cases pass. |
| Docker | Client/server 29.8.2; working Docker Desktop context `desktop-linux` | Daemon ready; no images/containers/volumes/build cache existed at inspection. |
| Compose | 5.6.0; full configuration validates | Source configuration ready; image builds/full container startup not executed here. |
| Docker VM | 18 CPUs, approximately 7.75 GiB RAM | Headroom concern for the complete stack; see below. |
| kind | 0.33.0; no clusters | Tool ready; cluster deployment not executed. |
| kubectl | 1.36.1; no current context | Tool ready; no server connectivity/runtime claim. |
| Ollama | CLI and loopback API both 0.40.6 | Runtime ready at `http://127.0.0.1:11434`. |
| Local models | `/api/tags` and `/api/ps` both empty | Blocks real generation, tool-calling and embedding checks. |
| Git | Repository present; remote named `origin` | Available; no remote operation or CI run performed. |

The earlier planning-time diagnosis of missing Docker/kind/kubectl/Ollama is superseded by this audit. Existing
deployment tasks stay unchecked: installed tools do not demonstrate deployed applications.

kubectl 1.36 is within the documented one-minor skew for the planned Kubernetes 1.37 node; verify the actual server
version when a cluster exists. [Kubernetes version policy](https://kubernetes.io/releases/version-skew-policy/).

## Fresh verification results

| Executed check | Result |
|---|---|
| Full Maven `verify dependency:build-classpath` | PASS: 43 tests, zero failures/errors/skips, real infrastructure. |
| Frontend lint | PASS. |
| Frontend unit tests | PASS: 2 tests. |
| TypeScript / Vite production build | PASS. |
| Chrome E2E against temporary native stack | PASS: 4 cases, customer purchase/decline, search/mobile, ADMIN product/stock, SUPPORT monitoring. |
| Full Docker Compose `config --quiet` | PASS. |

The temporary native browser stack was stopped after verification; pre-existing Ollama remains running. No model
download, deployment, Docker resource setting change, Git remote modification or persisted user-data deletion occurred.

Logs remain in ignored `.cache/verification/ai-prereq-java.log`, `ai-prereq-browser.log`,
`ai-prereq-browser-stack.log`, `ai-prereq-dependencies.log` and `ai-prereq-classloading.log`, plus module Surefire reports.

## Isolated Spring AI compatibility probe

An ignored Maven probe under `.cache/verification/ai-dependency-probe` used Java 25, Boot 4.1.1 and candidate Spring AI
BOM 2.0.1. Ollama and Elasticsearch vector-store starters resolved and compiled. Chat, embedding, vector-store and
tool API classes loaded; basic Boot-managed Jackson 3 serialization passed. Production POMs were not changed.

| Selected dependency | Version |
|---|---|
| Spring Boot starters | 4.1.1 |
| Spring AI components | 2.0.1 |
| Spring Core | 7.0.9 |
| Elasticsearch Java client | 9.4.5 |
| `tools.jackson.core:jackson-databind` | 3.1.5 |
| Elasticsearch runtime `com.fasterxml.jackson.core:jackson-databind` | 2.21.5 |

The Jackson coordinates use separate namespaces. This probe checks resolution, compilation, classloading and basic
serialization, **not Spring AI application autoconfiguration, inference, tools, embeddings or vector queries**.
The candidate remains unpinned in production. Documented Boot 4.1 support agrees with the initial probe.
[Spring AI compatibility/BOM guidance](https://docs.spring.io/spring-ai/reference/getting-started.html).

## Remaining setup and checks

### 1. Download and test models

Initial candidates are `qwen3:8b` for chat/tool selection and `nomic-embed-text` for embeddings. They are not final
digest/license/capability selections. The official Qwen listing advertises tool support and a roughly 5.2 GB download;
embedding weights are additional. [Qwen3 8B](https://ollama.com/library/qwen3:8b),
[Nomic embeddings](https://ollama.com/library/nomic-embed-text).

Preparation commands, **not executed by this check**:

```bash
ollama pull qwen3:8b
ollama pull nomic-embed-text
ollama list
ollama show qwen3:8b
ollama show nomic-embed-text
```

Then execute actual generation, structured tool requests and embeddings. Record full digests/licenses, actual
embedding dimension, context/quantization, memory and cold/warm timing. Model presence alone does not satisfy A0.
Keep inference local as specified.

### 2. Review Docker memory before full-stack testing

The VM currently has about 7.75 GiB. Existing full-stack configured container ceilings sum to 9.375 GiB, before an
additional AI service/container model. These ceilings are not measured consumption or a minimum-memory requirement;
they do show insufficient room for all containers to reach their configured limits together.

Use the README's approximately 12 GiB Docker allocation as an initial full-stack baseline and measure actual usage.
Review Docker Desktop **Settings -> Resources** (or its controlling resource manager) and verify the reported VM
allocation after applying changes. Budget native models, OS and IDE separately within the 48 GiB host. Container
inference would need additional measured VM headroom.

Native Ollama is preferred initially because Docker Desktop on macOS lacks Ollama GPU acceleration. Host loopback
availability does not establish model reachability from containers; verify that network path during A10.
[Ollama runtime/network/GPU guidance](https://docs.ollama.com/faq).

### 3. Execute deployment gates in their phases

First image pulls/builds, full Compose startup/health, persistent restarts, kind deployment and PVC recovery are now
unverified tasks rather than missing-tool blockers. No local cluster exists yet. The proposed `compose-up.sh --ai=...`
options are still future interfaces, not implemented commands.

Only the baseline and isolated dependency-inspection A0 tasks are checked in AI_PLAN.md. Real-model selection,
evaluation fixtures, ADR and complete A0 acceptance remain open. No AI implementation is marked complete.

# Local AI evaluation protocol

<!--
Maintenance: Distinguishes scenario assertions from factual answer review; retained procedural
results do not close independent wording gates.
-->

The immutable `ai-service/src/test/resources/evaluation-cases.json` defines 80 labeled scenarios: 20 retrieval/citation,
20 investigation, 20 action/recovery and 20 adversarial. Each group has 15 development and five held-out scenarios.
Reports include the corpus, prompt and model hashes. No external paid model judge or scripted runtime model is used.

Run the deterministic/native regression suite and real-model checks separately:

```bash
./mvnw -B -pl ai-service -am -Pai-real-model verify
./mvnw -B -pl workflow-tests -am -Pai-evaluation -Dai.eval.split=development -Dai.eval.repeats=1 verify
./mvnw -B -pl workflow-tests -am -Pai-evaluation verify
```

The final command runs all 80 scenarios three times. Missing pinned models fail explicitly. The harness uses real
PostgreSQL, Kafka, Redis, Elasticsearch and HTTP service boundaries with the existing signed-JWT test fixture. It is
not a Keycloak login test; the separate Chrome suite verifies actual Keycloak PKCE and gateway behavior.

Retrieval and investigation prompts materialize only their frozen synthetic fixture UUIDs. Action entries are
procedural scenarios: the real model first prepares an exact product proposal, then the harness drives explicit
confirmation/rejection/reauthentication and fault boundaries. Natural-language “confirm” never authorizes execution.
The report identifies this driver separately rather than counting every action label as an independent LLM answer.
Interrupted-action tests simulate durable checkpoint/credential loss; they are not a full OS process-kill test.

Outputs are written incrementally under `.cache/verification/ai-evaluation-{split}.json`. They contain only synthetic
questions, public tool artifacts/answers, IDs, timings and outcomes. JWTs, cookies, passwords and hidden reasoning are
excluded. A failed scenario stays in the denominator. An aborted setup is not an evaluated case. Keep incomplete and
complete runs distinct when retaining results in `docs/ai-results/`.

Measure retrieval recall@5 over all labeled retrieval cases, expected-source citation rate, successful expected tool
selection, schema validity, rounds/calls, abstention on the two labeled unsupported questions, scenario completion,
latencies and assessed unsafe effects. Reference validity is code-enforced and is distinct from natural-language
factual grounding. The corpus has only a few public documents, so high recall@5 is a small-corpus check, not evidence
that hybrid beats lexical search. Grounding review must identify factual assertions and their supporting source;
never relabel a citation-ID pass as “95% supported facts” or claim a human review that did not happen.

Release targets remain recall@5 >=90%, supported factual assertions >=95%, answerable task completion >=90% and zero
unauthorized/unconfirmed business effects. Deterministic ownership, idempotency, limits and failure tests are mandatory.
A passing Java evaluation test alone does not close deployment, full process/pod recovery, human review or capacity
gates.

Local load scenarios are explicit and separate from functional evaluation:

```bash
python3 scripts/ai-load.py commerce --iterations=100
python3 scripts/ai-load.py generation --iterations=1 --cold
python3 scripts/ai-load.py generation --iterations=20
python3 scripts/ai-load.py retrieval --iterations=20
python3 scripts/ai-load.py tool --iterations=20
python3 scripts/ai-load.py investigation --iterations=10
python3 scripts/ai-load.py mixed
```

Run against a ready native/Compose demo, one inference request at a time, and prepare models beforehand. The cold
command
explicitly unloads Llama and should run without other AI work. `mixed` compares one catalog VU with one generation VU;
it does not model a high-volume production workload. k6 summaries report p50/p95/p99 and completion; collect Ollama
process
residency, host/Docker memory and model/context settings alongside them. Establish a regression budget only after
repeatable
measurements. Backend first-token latency is measured from inference dispatch to the first generated text chunk; first
structured tool requests have a separate timer. The UI streams committed progress/final events, so this is not a browser
first-token measurement.

Initial development evidence (2026-10-18): `docs/ai-results/development-initial.json` retains the first completed
60-case run: 56/60 broad scenario passes, 15/15 retrieval recall@5, 12/15 successful expected investigation tools,
15/15 action scenarios and no observed unauthorized cart effects. Three search cases repeated invalid optional
price/empty-query arguments; one attempted forged citation failed closed. Strict schemas were retained and safe model
correction guidance added before the final holdout run. Negative application-denial states and model refusals are
reported separately in the final harness. The initial report does not certify grounding or release readiness.

The initial runner checked successful expected tool calls separately from answer usefulness. An empty product result
can satisfy that call check while the assistant still abstains. Do not report its broad scenario pass rate as task
completion or supported-fact accuracy. Review positive answers against the fixture facts and retain that denominator
separately. Partial text matches are explicitly labeled by the search tool; catalog presence is not stock evidence.

The retained `corpus-v1.json` completed 240 scenarios with 231 broad scenario passes and zero observed unsafe effects.
Its fixture inadvertently omitted the embedding flag, so it is **lexical**, not a hybrid evaluation. Its broad metric
also admitted empty search results; it cannot establish the answerable-task target. The corrected harness explicitly
requires semantic retrieval and evidence in positive answers, including two distinct products for the comparison.
Reports identify the model client/worker bytecode and prompt hashes; do not combine different candidates into one rate.
Use `python3 scripts/summarize-ai-evaluation.py REPORT --require-complete` to check completeness and display separate
split numerators/denominators. Grounding review remains a separate requirement.

Measured native load is retained in `docs/ai-results/native-load-initial.json` and `native-hybrid-load.json`. The latter
actually used embeddings for 20/20 retrieval requests: median 26.15 ms, p95 47.40 ms, p99 188.00 ms. Ten full
cited-policy
investigations completed: median 2.08 seconds, p95 2.95 seconds, p99 3.51 seconds. These small samples are observations,
not a production SLA or a high-concurrency capacity result. The host has 48 GiB RAM; no 32 GiB certification is claimed.

Three repeated catalog baseline/mixed pairs gave baseline p95 4.16–4.78 ms and mixed p95 3.67–4.02 ms. The difference is
within local measurement noise and does not establish a speedup. For future comparisons on this same host/profile,
flag a catalog p95 above twice the largest measured baseline (9.56 ms) for investigation, repeat the three pairs, and
require all commerce checks to pass. This measured-baseline review budget is not an externally promised latency target.
Keep cold/warm generation, lexical/hybrid retrieval and complete-agent times separate. A single cold request cannot
estimate a cold-start percentile distribution. Queue admission/fencing limits are tested deterministically; the load
measurements use one inference request at a time.

The `tool` scenario measures the authenticated Cart read API used by `getMyCart`, without generation. It measures the
downstream tool target through the gateway; registry validation/dispatch overhead is reported by the application tool
timer during full investigations.

The final repeated candidate is `docs/ai-results/corpus-hybrid-v3.json`: 240/240 procedural scenario checks, semantic
retrieval/expected citations 60/60, positive procedural task checks 120/120, zero observed unsafe effects across all
240 assessed cases. Development positive checks are 90/90 and holdout 30/30; recall is 45/45 and 15/15 respectively.
Twenty-four malformed tool calls were rejected; three adversarial cases were denied by application validation. A
scenario can pass while the system fails closed, so terminal failure counts are separate from model refusal.

[Source review](ai-results/source-review-v3.md) records the corrected timestamp/duration issue and remaining wording
concerns. It is an agent review, with no independent-human or >=95% factual assertion claim. The later API admission,
integral-overflow rejection
and session-lock rebuild recovery changes do not change valid the v3 model prompt/tool/worker behavior; they have
separate
real-infrastructure/model regression and process/pod recovery evidence. Preserve candidate-specific hashes and results.

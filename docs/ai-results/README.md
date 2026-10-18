# Retained local AI measurements

Only synthetic fixtures and safe model/tool observations belong here. Credentials, cookies, hidden reasoning, raw
browser traces and full local logs are excluded. Raw local logs remain ignored under `.cache/verification`.

| Artifact | Measured scope | Limits |
|---|---|---|
| `development-initial.json` | First 60 development cases, one repeat, actual Llama | Lexical fixture; broad tool-selection scoring; no final quality certification |
| `corpus-v1.json` | All 80 frozen cases, three repeats, actual Llama; 231/240 broad passes, zero observed unsafe effects | Lexical fixture; empty search could count as a broad pass; not the task-completion or factual-grounding metric |
| `native-load-initial.json` | Cold/warm short generation, lexical retrieval, full policy runs, three paired baseline/mixed catalog samples | One inference request at a time; cold N=1; 48 GiB host; does not certify 32 GiB or container capacity |
| `native-hybrid-load.json` | Explicit semantic retrieval N=20 and full policy runs N=10 | Small native sample; not a production SLA or a hybrid-versus-lexical quality improvement claim |

`corpus-v1.json` records its observed older tool-schema hash. Later candidates must retain separate prompt, schema,
model and implementation identities; never overwrite an old report to make its results describe newer code. Run
`scripts/summarize-ai-evaluation.py REPORT --require-complete` for denominators and split-level metrics. A citation-ID
check establishes reference validity; factual assertion support needs a separate source review. No human review or
external model judge has been claimed.

Later retained artifacts:

- `corpus-hybrid-v2.json`: full explicit hybrid candidate; source review found timestamp-derived duration wording.
- `corpus-hybrid-v3.json` and summary: full repeated candidate after model-visible Order timeline minimization;
  240/240 procedural checks, with factual-review limitations in `source-review-v3.md`.
- `compose-process-recovery.json`, `ai-kind-recovery.json`, `ai-kind-durability.json`: complete real process/pod/PVC
  fault checks. The final Cart replay succeeds with inference unavailable; rebuild evidence records RUNNING state and
  released exclusive index lock after actual container termination.
- `ai-kind-network.json`, `ai-kind-observability.json`, `ai-demo-metrics.json`: actual enforced policies and live
  authenticated targets/dashboard data, including fresh post-restart inference metrics.
- `cpu-runtime.json`: actual CPU-only generation/residency; `kind-cpu-capacity.json`: real insufficient-memory event.

Final deterministic/recovery validation includes an integer-overflow guard before tool dispatch and Cart-only resume
without model admission. Those changes have separate final tests; candidate v3 remains immutable. Raw diagnostics
remain ignored/private and are not included in CI artifact exports.

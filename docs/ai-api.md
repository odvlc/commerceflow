# Local Copilot API

<!--
Maintenance: Describes AI HTTP/SSE contracts and explicit proposal approval; use committed run state
as the replay authority.
-->

All endpoints use the existing Keycloak issuer/audience and bearer access token. Tokens are never stored in PostgreSQL,
passed to the model, or placed in stream URLs. The gateway exposes these paths only when `AI_ENABLED=true`.

| Method/path (`/api/v1` prefix)                                       | Behavior                                                                                 |
|----------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| GET `/ai/knowledge/search?query=demo%20returns&mode=CUSTOMER&size=5` | Authorized bounded lexical/hybrid evidence; no personal identifiers in embedding queries |
| GET `/ai/capabilities`                                               | Pinned local model readiness, caller roles, supported actions and message limit          |
| POST `/ai/conversations`                                             | `{ "mode": "CUSTOMER" }` or authorized `SUPPORT`; `Idempotency-Key` required             |
| GET `/ai/conversations?page=0&size=20`                               | Owned conversation page                                                                  |
| GET `/ai/conversations/{id}/messages`                                | Owned ordered messages, committed citations and pagination                               |
| POST `/ai/conversations/{id}/messages`                               | `{ "content": "..." }`, <=4,000 characters, `Idempotency-Key`; returns 202/run           |
| GET `/ai/runs/{id}`                                                  | Owned state, bounded steps and exact proposals                                           |
| POST `/ai/runs/{id}/cancel`                                          | Fences future work; cannot undo an already-issued cart request                           |
| POST `/ai/runs/{id}/resume`                                          | Same owner/current mode, fresh token; requeues an authentication pause                   |
| GET `/ai/runs/{id}/events`                                           | Authenticated SSE; numeric `Last-Event-ID`, ordered persisted progress/final events      |
| GET `/ai/proposals/{id}`                                             | Owned proposal including product, before/after quantities, revision and expiry           |
| POST `/ai/proposals/{id}/confirm`                                    | Exact `{ "version": 0, "argumentsHash": "<64 hex>" }`; durable execution, 202            |
| POST `/ai/proposals/{id}/reject`                                     | `{ "version": 0 }`; no downstream mutation                                               |
| GET `/ai/proposals/{id}/execution`                                   | Stable execution ID/state, attempts, acknowledged result or unknown outcome              |
| POST `/admin/ai/knowledge/imports`                                   | ADMIN-only bounded curated source import, idempotent key                                 |
| GET `/admin/ai/knowledge/jobs/{id}`                                  | Durable projection/rebuild state                                                         |
| POST `/admin/ai/knowledge/rebuilds`                                  | ADMIN-only explicit new index generation/rebuild                                         |

Read the generated `/v3/api-docs` for exact validation/DTO contracts. Errors use the common `ProblemDetail` shape and
correlation ID. Inaccessible resources return 404; unauthorized modes return 403; full admission returns 429; active
conversation conflicts and stale confirmations return 409. Missing pinned inference models return 503 before a new
inference message is persisted. Owned proposal/Cart-intent resume can reconcile without generation. Keep the same
idempotency key and contents after ambiguous
network failures. A changed body under an existing key conflicts.

SSE carries `queued`, `state`, `step`, `proposal` and `final` event types, each with a monotonic persisted event ID.
It exposes public progress and committed final content, not private model reasoning or partial token previews. A stream
never starts/resumes a run. Reconnect with a refreshed header token and the last received ID; use GET run/history to
recover committed content. Streams stop at token expiry, after at most two minutes, or at a terminal/paused state.
There are at most two per subject and 32 per application instance. Disconnecting does not cancel the run.

CUSTOMER tools forward the current user's JWT to live APIs. SUPPORT tools include only the scoped order explanation
read and public catalog/authorized knowledge reads. Neither mode can invoke checkout, refunds, stock edits, arbitrary
HTTP/SQL or shell operations. `proposeCartChange` only prepares a proposal; model text saying “yes” cannot confirm it.

Cart reads return `revision` and an ETag of `"<incarnation UUID>:<counter>"`. Existing cart writes remain compatible;
AI writes always send the approved `If-Match` plus their persisted downstream key. Redis checks its request memo before
the revision, so an acknowledged-but-lost response can be safely recovered with that same request inside its retention
window. Proposal expiry is five minutes. Recovery is capped below the observed cart TTL and at 23 hours; after expiry
or uncertain Redis state, inspect `OUTCOME_UNKNOWN` and the current cart rather than retrying with a new key.

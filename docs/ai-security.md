# Copilot security boundaries

<!--
Maintenance: Defines caller authority, prompt/data trust boundaries and explicit confirmation rules
for AI tools.
-->

Keycloak is the identity provider. Services independently validate issuer, audience, expiry and UUID
subject. The AI service verifies ownership and current mode on commands, history, run status, event
replay and proposals. SUPPORT allows the exact Order explanation GET route, not administrative
payment or stock access. Caller tokens are held only in an expiring process-local map, forwarded to
fixed service URLs and never sent to Ollama or persisted. A process restart requires fresh
authentication; the model cannot supply identity, roles, customer IDs or arbitrary URLs.

Llama's tool requests are untrusted input. The low-level Spring AI Ollama API has no automatic tool
dispatch loop. The application validates a fixed schema/allowlist and persists intent before
dispatch. Limits include four model rounds, eight tool calls, one model request at a time, an
eight-run active queue, 120 seconds active time and two transient retries. Renewable leases and a
fencing counter reject stale completions. An admission failure rolls back its new user message.
Reauthentication and confirmation also pass admission. No SQL, shell, arbitrary HTTP, payment,
checkout, stock or refund tools exist.

Curated knowledge is ADMIN-imported, versioned, audience-filtered and rechecked against
authoritative PostgreSQL before returning evidence. Embedding-model changes require a new index
generation. Order/customer data is not indexed as knowledge. Retrieved text, product descriptions
and model answers are untrusted content. The browser uses React text rendering and typed controls,
never generated HTML/action URLs. Server-side validation restricts final citation IDs to the
evidence collected by that run. Citation validation checks the references. Each natural-language
assertion still needs quality evaluation and review.

A cart proposal is preparation only. It records the exact operation, product, before/after quantity,
observed product version, cart incarnation/revision, hash and expiry. An authenticated explicit
confirm command supplies that hash and proposal version. The execution key is persisted before
sending the Cart request. Redis checks the matching memo before checking the original revision. A
changed product or cart requires new review. Duplicate confirms reuse the execution; lost
acknowledgments retry the same request only within its retention horizon. Unknown outcomes stay
visible and cannot authorize a new-key retry. Cancellation prevents future steps, but cannot undo an
already-issued request; a late positive acknowledgment remains in the execution audit even when its
run was cancelled.

SSE requires an Authorization header, checks ownership before replay and stops at token expiry. It
has per-owner/global connection caps, bounded replay batches, heartbeats and a maximum two-minute
lifetime. A reconnect never creates a run. Metric labels omit subjects, run/product IDs, tokens and
prompts. Public progress excludes chain-of-thought. Inference runs locally. Conversations and safe
tool observations still contain private application data and need the same database access and
backup protections as the rest of CommerceFlow.

Development is localhost-only. No remote deployment, paid resource, real payment/customer data, or
destructive volume reset is part of this feature. A CPU container model is a distinct resource
profile from native macOS inference.

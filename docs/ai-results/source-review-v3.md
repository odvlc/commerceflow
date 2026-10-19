# Source review of hybrid candidate v3

An agent performed this source review. It provides neither independent human review nor a measured
95% factual-assertion score. The review inspected all 120 positive answers, comparing their distinct
texts and differences between repeats with the curated Markdown and saved live tool observations.
The frozen scenarios, model/schema/client/worker identities and raw safe observations remain in
`corpus-hybrid-v3.json`; the accompanying summary gives exact programmatic denominators.

The earlier v2 SUPPORT answer turned ISO timestamp suffixes into durations. The model's Order tool
now selects only current public state/cause/version/observation; the complete authorized Order API
history remains intact. All three v3 SUPPORT answers correctly report the recorded decline, without
those invented durations. This fix was followed by a complete 240-scenario run; none of the frozen
questions or expected answers was changed.

Remaining wording/usefulness concerns for independent review:

- `retrieval-11` says APPROVE “approves the order.” Its source establishes simulated payment authorization; inventory
  commitment is a separate saga step. This wording can overstate the payment guide's scope.
- `retrieval-13` reports inventory release after timeout but omits reconciliation before release. The underlying saga
  enforces that ordering; the short answer should not be treated as operational instructions.
- Some catalog answers use “available” for a catalog listing. The tool establishes active catalog details/prices,
  not authoritative stock availability. Quantity-sensitive decisions still go through Inventory at checkout.
- `investigation-16` repeat one repeats a matching product rather than presenting a useful distinct list. Other repeats
  identify both actual products. Valid citations do not establish complete comparison coverage.
- Very short “No” answers for terminal timeout/unknown legacy cause have live evidence but can be less useful than an
  explicit explanation. Product and Order identifiers/observation timestamps are synthetic fixture values.

Do not turn the 240/240 scenario checks, 60/60 recall/citation checks or 120/120 positive procedural
checks into a 100% factual-grounding claim. The >=95% supported-fact and independent wording-review
release requirements remain unchecked. Zero observed unsafe effects and deterministic
role/ownership/confirmation checks are separately verified invariants.

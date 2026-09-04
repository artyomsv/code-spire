---
name: llm-charge-readers-are-review-shaped
description: Two attention queries read llm_charge with no subject_kind filter, so every new charge subject (RUN, and later AUTONOMY/KNOWLEDGE/INSIGHT) lights review-shaped rows
metadata:
  type: project
---

`CostAttentionRow.UNPRICED` and `CostAttentionRow.UNRECONCILED`
(`spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/CostAttentionRow.java`)
count `llm_charge` rows with **no `subject_kind` filter**, while every read in
`ReviewProjection` carries `subject_kind = 'REVIEW'`. `SpendWindow` is unfiltered
deliberately; these two are unfiltered by omission.

Verified 2026-09-02 against commit `ceeead0`: a run charge whose usage is unknown
(one `ChargeLine.unknown(TOTAL, 0)` line) raises `LLM_USAGE_UNRECONCILED`, whose
message tells the operator to fix a `TokenUsageMapper` mapping defect. For a run
that failed before it spent anything that is a misdiagnosis with no action link.

**Why:** `ChargeCapability` already names three more producers that do not exist yet
(AUTONOMY, KNOWLEDGE, INSIGHT). Each one will inherit the same false row on the day
it starts writing charges, and the symptom is a panel row nobody can clear by
fixing anything — the opposite of the attention panel's stated contract.

**How to apply:** whenever a change adds a new `ChargeSubject` or `ChargeCapability`,
check every `llm_charge` reader for a subject filter, not just the ones in
`ReviewProjection`. Grep: `grep -rn "llm_charge" --include=*.java spire-orchestrator/src/main`.
Relates to [[code-spire-test-gap-pattern]].

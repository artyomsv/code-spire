# "No charges recorded" is inferred from an empty model string rather than stated

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-ui/src/components/ReviewsList.tsx` (`costCell`), fed by `ReviewProjection.listSummaries`'s derived `model` column |
| Found during | ADR-023 LLM cost accounting — Task 10 review, confirmed by the whole-branch review |
| Date | 2026-08-07 |

## Issue

The reviews-list Cost cell has to tell three states apart:

| State | Should render |
|---|---|
| no charge line has ever landed | `—` |
| every charge line was `UNKNOWN` | `— (unpriced)` |
| a real total, possibly partial | `$4.180` or `$4.180*` |

It distinguishes the first by testing **`r.model === ''`**. That works because `listSummaries` derives
`model` from the review's most recent `llm_charge` row, so no rows yields SQL NULL, which serialises to an
empty string. The signal is correct — but it is *inferred*, and what it infers from is a display field.

Not currently reachable as a bug: `llm_charge.model` is `NOT NULL`, a provider's model must be non-blank
**and** catalogued (`LlmProviderRegistry.requirePriceableModel` on both create and update), and
`LlmModelRegistry` refuses to rename or delete a catalogued model a provider still names. So a charge row
with a blank model cannot presently exist.

## Risks

A coupling risk rather than a live defect, and the failure mode is silent: **one charge row with a blank
model would render a review's real spend as "no charges recorded yet"** — the cost disappears from the busiest
screen with no error, no empty state and nothing in the logs. The guards that make it unreachable are four
separate rules in three classes; a future path that writes a charge row (a backfill, an import, a repair
script, a second worker) does not automatically inherit them.

It is filed at Low because every current path is guarded, and because it is a *reading* fault rather than a
data fault — the ledger would still hold the truth.

It is filed at all because it is the same shape as a **Critical** this branch had to fix. `ReviewCostCard`
read `unpricedCalls` off a payload the server never sent, so `undefined > 0` was `false` and a partial total
rendered as a confirmed `$0.000`. Both are one discipline: **send the fact, do not infer it.** One instance
was reachable and one is not, which is the only difference between them.

## Suggested Solutions

1. **Send the fact.** Add an explicit count — `chargeLineCount`, or a boolean `hasCharges` — to
   `ReviewSummary`, populated by the same aggregate that already computes `total_cost_millicents` and
   `unpriced_calls` in `listSummaries`. One extra `COUNT(*)` in a query that is already scanning those rows,
   and the UI then branches on a fact instead of a proxy. This is the fix; the others are only worth
   considering if it is somehow blocked.
2. **Assert the coupling instead.** A test that a review with no charge lines yields `model === ''` at least
   pins the inference, so a change to the derivation fails loudly. Cheaper, and strictly worse — it protects
   the inference rather than removing the need for one.
3. **Leave it.** Only honest while all four guards hold, and nothing enforces that they continue to.

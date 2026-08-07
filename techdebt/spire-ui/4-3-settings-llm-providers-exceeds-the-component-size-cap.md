# The LLM providers settings screen is more than twice the component size cap

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-ui/src/components/SettingsLlmProviders.tsx` (549 lines) |
| Found during | ADR-023 LLM cost accounting — PR #40 rules-compliance review |
| Date | 2026-08-07 |

## Issue

The project's guideline is **250 lines per React component** (`~/.claude/rules/clean-code-react.md`).
`SettingsLlmProviders.tsx` is **549**.

It is not this branch's doing, and the direction of travel is right: the file was **756 lines at
`master`** and ADR-023 reduced it by 207 as a side effect of extracting `SettingsLlmModelForm` (185),
`SettingsLlmModelRateFields` (108) and `SettingsLlmModelDialectFields` (110). It is recorded because no
debt entry covered it, so the remaining overage had no owner — and because the branch's own extractions
prove the split is straightforward, not blocked on anything.

The file holds four separable things:

| Lines | Unit | Size |
|---|---|---|
| 34-74 | four pure helpers — `profileHint`, `defaultBaseUrl`, `byExpenseDesc`, `ratesSummary` (+ `RatesSummary`) | ~41 |
| 81-333 | `SettingsLlmProviders` — the default-exported screen | **~253** |
| 334-370 | `ConnCell` — connectivity-state cell | ~37 |
| 371-549 | `LlmProviderForm` — the provider create/edit form | ~179 |

**The load-bearing detail: extracting all three co-located units still leaves the screen component at
roughly 253 lines — over the cap on its own.** So this is not a "move the neighbours out" job. Those
extractions are necessary and cheap, but the screen itself has to decompose too, most obviously by
lifting the provider table's row rendering out of the component body. Anyone who does only the easy
half will find the file still non-compliant and may conclude the cap is unreachable, when in fact the
second step was simply never started.

A second signal in the same file: the four pure helpers are exported, but their **only importer is
`SettingsLlmProviders.test.ts`** (`git grep` confirms — the sibling `.form.test.tsx` imports just the
default export). They are public solely to be testable, which is the usual sign a helper module is
missing rather than that the component needs a wider API. `llmPricing.ts` already exists next door (38
lines, `RATE_TYPES` / `sumRates` / `formatRate` / `TOKEN_TYPE_LABEL`) and `profileHint` / `ratesSummary`
are pricing-display functions by any reading, so the home is already there and already tested in the
same style.

## Risks

Low, and maintainability-only — nothing here is a correctness or money risk, the file is well covered
(`SettingsLlmProviders.test.ts` + `SettingsLlmProviders.form.test.tsx`, both added or extended by this
branch), and the screen works.

The concrete costs are the ones the branch already paid elsewhere:

- **This is the settings screen every LLM change goes through**, so provider, model, pricing and dialect
  work all serialise on one file and collide there.
- **Review attention is finite per file**, and this branch has direct evidence for that claim: ADR-023's
  Critical 1 hid in `ReviewProjection`'s 1,700 lines through eleven task reviews (see
  `techdebt/spire-orchestrator/3-4-...`). A 549-line component is far better than that, but it is the
  same mechanism at smaller scale — and the QA pass on this branch found that
  `SettingsLlmModelDialectFields`' branches have **no test at all** despite the dialog being opened by
  an existing test file, which is precisely the kind of gap a large screen makes invisible.

Filed at Low because the file is shrinking rather than growing, and at Medium complexity because
reaching compliance needs both the mechanical extractions and a decomposition of the screen body, with
tests following each.

## Suggested Solutions

1. **Two commits, mechanical first** (the fix):
   - Move the four pure helpers into `llmPricing.ts`, and drop the `export` from anything that then has
     no non-test importer. Pure move; `SettingsLlmProviders.test.ts` only changes its import path.
   - Extract `LlmProviderForm` → `SettingsLlmProviderForm.tsx` and `ConnCell` →
     `SettingsLlmProviderConnCell.tsx`, following exactly the pattern the branch used for
     `SettingsLlmModelForm`.
   - Then decompose the remaining screen (row rendering is the obvious seam) to land under 250.

   Keep it behaviour-free and separate from any feature work, so the diff reads as a move.
2. **Do only step one and re-measure.** Defensible as a partial improvement, but state in the commit
   that the file is still over the cap, so it does not read as closed.
3. **Leave it.** Honest only while nothing else grows the file — and this is the screen every LLM
   feature touches, so that assumption is weaker here than for most components.

## Related, not the subject of this entry

`ReviewDetail.tsx` is **256 lines** — 6 over the cap, and 255 at `master`, so it was already over before
this branch touched 5 of its lines (a mechanical `usageCard(r)` → `<ReviewCostCard …/>` swap; the swap
*removed* the inline card and is why `ReviewCostCard.tsx` exists at 147 lines). Noted here rather than
filed separately because a 6-line overage with a 1-line delta would be noise as its own entry, and
because whoever applies the discipline above will be in the neighbourhood. `ReviewsList.tsx` is 244 —
under the cap, but it grew 216 → 244 on this branch, so it is the next one to cross.

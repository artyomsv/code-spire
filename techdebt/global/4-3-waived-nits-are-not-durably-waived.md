# A nit waived while resolving a finding can come back as its own finding

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-contract/.../llm/PromptCatalog.java` (`REVIEW_BODY` `{{prior_findings}}`), `spire-orchestrator/.../readmodel/ReviewProjection.java` (`priorRunFor`, `recordOpenFindings`), `spire-ui/src/components/ReviewsList.tsx` (the open-count badge) |
| Found during | Three-provider parity pass (runbook Mode G), S10 round 3.2 |
| Date | 2026-07-26, re-scoped 2026-08-02 |

## Issue

When the reviewer resolves a finding it may explicitly set aside lesser issues it noticed at the
same place. Observed on Bitbucket:

> "The incompatible-types compile error is fixed: `VARIABLE` is now assigned a String literal. The
> residual nits (field still unused and not `final`) are stylistic only and don't block."

That judgement lives only in the note's prose. A later round — in the observed case one whose diff
moved the file — raised the unused/non-final field as a **new finding** with its own thread.

Nothing is being re-reported: the exclusion list (`{{prior_findings}}`, built from the posted-run
snapshot) does its job on the *type-mismatch* finding, and the new finding is a genuinely different
concern that happens to sit at the same line. The gap is that a waiver is conversational, not
tracked, so it cannot be honoured by a later run.

## Re-scoping (2026-08-02)

This entry recommended option 3 below as "the cheapest and addresses the actual harm". Neither half
of that holds up on inspection:

- **It is not implementable as written.** Marking "a finding that was previously waived" requires
  knowing a waiver happened. Nothing records one — that is the entry's own premise. `PriorFinding`
  carries `(path, line, severity, message, threadRef)` and no waiver flag, and the reconcile contract
  has no field for it. Option 3 silently assumes option 1's storage.
- **The nearest implementable version already ships.** The detail page's unified findings list
  already separates this round's findings (`newFindingRows`, status `new`) from reconciliation
  verdicts against prior findings, and collapses closed verdicts into their own section. A reader
  looking at a review after a fix can already see which rows are new and which are carried over.

What is genuinely left is narrower than the entry claimed: the **reviews-list badge** is a bare
number with no breakdown, so a count moving 1 → 2 between rounds still reads as a regression until
the review is opened. That is a display gap on one number, not the reconciliation-correctness problem
the original framing implied.

## Risks

Low, and lower than filed. A confusing signal rather than a wrong one, and confined to the list view
now that the detail view distinguishes new from carried-over. The counter-argument for leaving it
whole: the nit *is* real, and a reviewer that never revisits a set-aside issue would let genuine
problems disappear because one earlier round called them minor.

## Suggested Solutions

1. **Break the list badge down** — the remaining gap, and cheap: show new-vs-carried-over on the
   reviews list the way the detail page already does, so the count's movement explains itself without
   opening the review. Needs the split in the list payload, which the detail endpoint already computes.
2. **Track waivers explicitly.** Give the reconcile contract an optional `waived: [...]` alongside its
   verdicts, persist it next to the findings snapshot, and feed it into the review prompt as a second
   exclusion list ("noted and set aside — do not raise"). Durable and honest, but it adds a store, a
   wire field and a prompt slot — and it is the prerequisite for any UI that claims to show a waiver.
3. **Do not waive in prose at all.** Require the reconcile note to either keep the finding open or
   close it cleanly, and let anything worth raising become its own finding immediately — consistent,
   at the cost of more findings up front.

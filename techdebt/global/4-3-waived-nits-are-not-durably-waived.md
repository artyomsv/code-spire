# A nit waived while resolving a finding can come back as its own finding

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-contract/src/main/java/dev/codespire/contract/llm/PromptCatalog.java` (`REVIEW_BODY` `{{prior_findings}}`), `spire-orchestrator/.../readmodel/ReviewProjection.java` (`priorRunFor`, `recordOpenFindings`) |
| Found during | Three-provider parity pass (runbook Mode G), S10 round 3.2 |
| Date | 2026-07-26 |

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

## Risks

Mostly a confusing signal rather than a wrong one. `openFindings` **rises after a fix** (observed
1 → 2), which reads as a regression when it is a previously-waived nit surfacing. On a long-running
PR the same nit can be waived and re-raised repeatedly, since each round is free to rediscover it.
It also erodes trust in the open count, which the reviews list badge is derived from.

The counter-argument for leaving it: the nit *is* real, and a reviewer that never revisits a
set-aside issue would let genuine problems disappear because one earlier round called them minor.

## Suggested Solutions

1. **Track waivers explicitly.** Give the reconcile contract an optional `waived: [...]` alongside
   its verdicts, persist them next to the findings snapshot, and feed them into the review prompt as
   a second exclusion list ("noted and set aside — do not raise"). Durable and honest, but it adds a
   store, a wire field, and a prompt slot.
2. **Do not waive in prose at all.** Require the reconcile note to either keep the finding open or
   close it cleanly, and let anything genuinely worth raising become its own finding immediately —
   consistent, at the cost of more findings up front.
3. **Surface it in the UI instead of suppressing it.** Mark a finding that was previously waived so
   the open count still changes but the reader can see why, which removes the "count went up after a
   fix" surprise without new prompt machinery.

Option 3 is the cheapest and addresses the actual harm (a misleading signal) rather than the
philosophical question of whether a waiver should be permanent.

# A cancelled run's spend is reported as if the agent had finished

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-run-worker/.../RunLauncher.java` (`stopAgent`), `RunControlListener.stop`, `spire-harness-codex/.../CodexAdapter` (usage is folded on `turn.completed`) |
| Found during | M1 Task 7/8 four-lens review (security) |
| Date | 2026-09-03 |

## Issue

Cancelling a run, and the push gate's own agent-stop, both kill the agent mid-turn. The reference
adapter folds usage only when a turn COMPLETES, so the tokens the agent had bought during the turn it
was killed in are never reported. The run's result then carries the usage of its completed turns and
nothing marks it partial.

This is honest in the ADR-023 sense — an unmeasured turn stays unmeasured rather than becoming a
zero — and it is the safe direction for a reader. It is the unsafe direction for the SPEND CAP,
which is the one consumer that acts on the number: the deployment's rolling window sees less than was
actually spent, and every cancelled run under-reports by up to one turn.

The precedent for the fix already exists on the wire. `RunFinished.agentUnobserved` was added for the
structurally identical case — a run whose exit nobody watched — precisely so a consumer can tell a
measured outcome from an unmeasured one instead of inferring it.

## Risks

- A deployment that cancels runs routinely drifts below its true spend, and the drift is invisible
  because nothing distinguishes a partial figure from a complete one.
- Anyone later reconciling the ledger against a provider's own billing finds a gap with no marker
  explaining it, and the obvious suspicion is the ledger rather than the cancellation.

## Suggested Solutions

- Carry a `usagePartial` flag on the result whenever the agent was stopped rather than allowed to
  exit — set at the two stop sites, which are the only places that know. Mirror `agentUnobserved`:
  a boolean, defaulting false, so an omitted field deserializes to "complete" and the wire stays
  backward compatible.
- Have `RunTokenUsage` treat a partial report as unreconciled, so it prices as UNKNOWN rather than as
  a confident total. That reuses the existing degradation instead of inventing a second one, and it
  makes the run visible on the new `RUN_SPEND_UNPRICED` attention row rather than silently low.
- Do NOT try to estimate the missing turn. A plausible number here is exactly the fabricated figure
  ADR-023 exists to keep out of the ledger.

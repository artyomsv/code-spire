# The factory's dispatch path exceeds the method-size and parameter-count rules

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-run-worker/.../RunLauncher.java` (`launch` 52 lines, `interpret` 5 parameters), `spire-run-worker/.../RunDispatcher.java` (`onCommand` 48 lines), `spire-run-worker/.../RunAckBudget.java` (`verify` 4 parameters), `spire-publisher/.../PublishCycle.java` (`handle` 34 lines, constructor 7 parameters), `spire-contract/.../RunIds.java` (`of` 5 parameters) |
| Found during | PR #95 four-lens review, rounds 1 and 2 (rules-compliance) |
| Date | 2026-09-02 |

## Issue

`clean-code-java.md` caps a method at 30 lines and 3 parameters. The methods above grew past both
during the two review rounds, each addition individually right — a guard, a bound, an ack — and
none of them split. `RunResource.dispatch` and `FactoryRunProjection.queued` were on the same list
and are being split as part of round 2; the rest are recorded here so the rule is not silently
suspended for one package.

## Risks

- Readability only: none of these methods hides a defect the size conceals, as far as two review
  rounds could see. The risk is the next addition, which lands in a method already too long to
  read at one sitting.

## Suggested Solutions

- `RunLauncher.launch`: extract the two channel readers and the salvage-then-destroy tail;
  `interpret` takes a small `RunObservation(seen, outcome, finalization)` record.
- `RunDispatcher.onCommand`: extract the claim-and-ack prelude from the launch-and-emit body.
- `PublishCycle`: a `PublishSettings(baseCommit, branch, protectedPaths, bundleMaxBytes)` record
  for the constructor; `handle` split into fetch/gate/push steps.
- `RunIds.of`: a `RunCoordinates` record, or drop `attempt` from the call and let `parse` carry it.
- `RunAckBudget.verify`: a `ChannelSettings` record for the three channel values.

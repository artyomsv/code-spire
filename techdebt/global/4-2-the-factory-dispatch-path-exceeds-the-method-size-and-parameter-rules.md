# The factory's dispatch path exceeds the method-size and parameter-count rules

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-run-worker/.../RunLauncher.java` (`launch` 52 lines, `interpret` 5 parameters), `spire-run-worker/.../RunDispatcher.java` (`onCommand` 48 lines), `spire-run-worker/.../RunAckBudget.java` (`verify` 4 parameters), `spire-publisher/.../PublishCycle.java` (`handle` 34 lines, constructor 7 parameters), `spire-contract/.../RunIds.java` (`of` 5 parameters) |
| Found during | PR #95 four-lens review, rounds 1 and 2 (rules-compliance). **Extended 2026-09-04** by the M2 whole-PR round, which found the orchestrator half had joined the list |
| Date | 2026-09-02 (updated 2026-09-04) |

## Issue

`clean-code-java.md` caps a method at 30 lines and 3 parameters. The methods above grew past both
during the two review rounds, each addition individually right — a guard, a bound, an ack — and
none of them split. `RunResource.dispatch` and `FactoryRunProjection.queued` were on the same list
and are being split as part of round 2; the rest are recorded here so the rule is not silently
suspended for one package.

## Update — 2026-09-04, M2 (the `/fix` dispatch)

`FixRunDispatcher.dispatch` is **87 code lines (131 physical) and takes 5 parameters** — the
largest member of this set, and the one the entry did not gain when it was written. That is this
entry's own subject arriving one level up: it exists "so the rule is not silently suspended for
one package", and a new method in that package went past both limits without being added.

The method is deliberately LINEAR: claim → spend cap → plan → configuration → machine account →
spec → credential → command → row → launch, each with its own refusal. The order is load-bearing
and stated in the javadoc (the claim is first because it is the only gate that answers "this
already happened"; the credential is last because selecting one is a write). So it reads as one
sequence and splitting it into two halves would hide the ordering that is the point.

That argument justifies the SHAPE, not the size. What it actually asks for is a small type per
step, the way `SpendGate` already is — each gate answering one question with a `Refused` or null,
and `dispatch` becoming the list of them. The round found no defect hiding in the length; the
risk below is the one that applies.

**The five parameters are `(reviewId, repo, threadRef, commentId, finding)`.** Four of the five
are the target's identity and want a record — the same `RunCoordinates` shape this entry already
proposes for `RunIds.of`, which takes five parameters for the same reason.

## Risks

- Readability only: none of these methods hides a defect the size conceals, as far as three review
  rounds could see. The risk is the next addition, which lands in a method already too long to
  read at one sitting — and `dispatch` has now taken four additions after the one that made it too
  long.

## Suggested Solutions

- `RunLauncher.launch`: extract the two channel readers and the salvage-then-destroy tail;
  `interpret` takes a small `RunObservation(seen, outcome, finalization)` record.
- `RunDispatcher.onCommand`: extract the claim-and-ack prelude from the launch-and-emit body.
- `PublishCycle`: a `PublishSettings(baseCommit, branch, protectedPaths, bundleMaxBytes)` record
  for the constructor; `handle` split into fetch/gate/push steps.
- `RunIds.of`: a `RunCoordinates` record, or drop `attempt` from the call and let `parse` carry it.
- `RunAckBudget.verify`: a `ChannelSettings` record for the three channel values.
- `FixRunDispatcher.dispatch`: a gate per step behind one interface, each returning a `Refused` or
  null, with `dispatch` reduced to the ordered list of them — so the order stays visible in one
  place, which is the property the current shape is protecting. Plus a `FixTarget(reviewId, repo,
  threadRef, commentId)` record for four of the five parameters.

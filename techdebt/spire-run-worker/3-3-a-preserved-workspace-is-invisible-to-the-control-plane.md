# A preserved workspace is invisible to the control plane

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-run-worker/src/main/java/dev/codespire/runworker/RunLauncher.java` (the `finalization.salvaged()` branch preserves the unit), `spire-contract/.../event/RunResult.java` (neither result carries the fact) |
| Found during | M1 Task 3 — salvage before teardown |
| Date | 2026-09-02 |

## Issue

A unit whose salvage did not observe an exit is deliberately kept, because throwing it away
destroys exactly what salvage exists to read. Nothing tells the orchestrator that it was kept.

The orchestrator can half-guess it: a run failing with `SALVAGE_FAILED` or `AGENT_TIMEOUT` has a
preserved unit. But Task 3 added a case the guess does not cover — **a run that pushed before it
overran is now reported `RunFinished`**, which is correct, and it leaves a container running. So a
run reads as a success while a sandbox stays alive with the agent still in it, and no surface says
so. That case is new, and it is the one most likely to go unnoticed precisely because the run looks
fine.

Deliberately not fixed here by adding a field to both result records. The consumer for that field
does not exist yet, and the natural owner does: FR-F8's orphan watchdog discovers sandboxes by
label and defines an orphan as one whose lease is absent or stale. A preserved unit is exactly that
once its lease stops being renewed, so the watchdog surfaces it without the wire needing to carry
the fact at all. Adding a component to two wire records to serve a reader that the next task builds
differently would be the wrong shape.

## Risks

- A container holding a still-running agent survives a run reported as finished, consuming a
  daemon's memory and a model's tokens, with no operator-facing signal.
- The reclaim and expiry paths FR-F7 asks for have nothing to list, so an operator cannot act on a
  preserved workspace even when they know it exists.

## Suggested Solutions

- Build it into the orphan watchdog (Task 6). The lease gives the watchdog the run id, the label
  gives it the sandbox, and a preserved unit is an orphan under the definition that task already
  needs. Reaping one runs salvage before destroy, which is the same rule.
- The attention row and the operator reclaim FR-F7 names then have a real source to read, rather
  than a flag the worker asserts about itself.

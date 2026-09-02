# A preserved workspace is invisible to the control plane

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-run-worker/src/main/java/dev/codespire/runworker/RunLauncher.java` (the preserve branch), `spire-runtime/src/main/java/dev/codespire/runtime/RunRuntime.java` (nothing lists units) |
| Found during | M1 Task 3 — salvage before teardown |
| Date | 2026-09-02 |
| Amended | 2026-09-02, after the Task 3 review round |

## Issue

A unit whose salvage did not observe an exit is deliberately kept, because throwing it away
destroys exactly what salvage exists to read. **No surface lists the units that are being kept.**

Two of this entry's original claims were corrected by the Task 3 review and are worth stating,
because the remaining risk is much smaller than the first version said:

- The run is **not** invisible any more. A run that pushed and then overran is stored as
  `delivered_unfinished` rather than `succeeded`, so the row itself says the agent's outcome was
  never observed. What has no surface is the **sandbox**, not the run.
- The unit is **stopped**, not running. `RunLauncher` now cancels a preserved unit rather than
  trusting the Docker arm's private promise that an overrun kills the agent. So a preserved unit
  costs disk and holds a credential in a stopped container's environment. It does not burn memory,
  CPU or model tokens, which is what the first version of this entry claimed.

Still deliberately not fixed by adding a field to the result records. The natural owner exists:
FR-F8's orphan watchdog discovers sandboxes by label and defines an orphan as one whose lease is
absent or stale. A preserved unit is exactly that once its lease stops being renewed.

## Risks

- Preserved units accumulate on a busy daemon. Each holds a workspace volume and a stopped
  container, so the cost is disk that nothing reclaims and no listing an operator can consult.
- A stopped container's environment still holds the harness credential until it is removed, so the
  window an operator would want bounded is bounded by nothing.
- The reclaim and expiry paths FR-F7 asks for have nothing to list, so an operator cannot act on a
  preserved workspace even when they know one exists.

## Suggested Solutions

- Build it into the orphan watchdog (Task 6). The lease gives the watchdog the run id, the label
  gives it the sandbox, and a preserved unit is an orphan under the definition that task needs.
  Reaping one runs salvage before destroy, which is the same rule.
- The attention row and the operator reclaim FR-F7 names then have a real source to read, rather
  than a flag the worker asserts about itself.

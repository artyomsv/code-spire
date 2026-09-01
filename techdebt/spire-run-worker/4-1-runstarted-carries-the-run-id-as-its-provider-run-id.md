# `RunStarted.providerRunId` is the run id, not the unit

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-run-worker/src/main/java/dev/codespire/runworker/RunDispatcher.java` (emits `RunStarted` before `launch`, passing the run id twice) |
| Found during | PR #95 four-lens review, round 1 (code-reviewer, worker side) |
| Date | 2026-09-02 |

## Issue

`RunHandle` defines `providerRunId` as the pod name or docker unit id, and `DockerRunRuntime.create`
returns the real agent container id — but `RunStarted` is emitted before `create` is called, so no
handle exists and the run id is passed in its place. A unit preserved for inspection after a failed
salvage is findable only by its `dev.codespire.runId` label, never by the id the event handed the
operator.

## Risks

- The one field meant to point an operator at a preserved unit points at nothing; the label is the
  workaround and is documented in SMOKE-TEST Mode P.

## Suggested Solutions

- Emit `RunStarted` after `runtime.create` returns, carrying `handle.providerRunId()`; a create
  failure still produces `RunFailed`, so nothing is lost.
- If the started signal must precede creation, drop the component rather than fill it with a
  duplicate.

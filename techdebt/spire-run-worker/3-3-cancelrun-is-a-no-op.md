# `CancelRun` is logged and dropped

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-run-worker/src/main/java/dev/codespire/runworker/RunDispatcher.java` (the `CancelRun` branch), `spire-runtime-docker/.../DockerRunRuntime.java` (`cancel`, implemented and unreached from the dispatch path) |
| Found during | PR #95 four-lens review, round 1 (code-reviewer and security-officer, worker side) |
| Date | 2026-09-02 |

## Issue

The `CancelRun` branch logs the request and returns. `RunRuntime.cancel` exists and works; nothing
calls it from dispatch. An operator cancelling a run gets no error and no effect, and the run keeps
spending until its wall clock. The plan puts cancel in M1 (FR-F6); the command is on the wire today.

A second obstacle beyond wiring: the cancel arrives as a separate Kafka record while the executing
record is still being processed on the same channel, so acting on it needs the launcher to expose the
live handle to the dispatcher.

## Risks

- A runaway agent cannot be stopped by the operator; the only bound is the wall clock.
- A silent no-op on an operator command is the same failure shape as the silent turn cap this
  project has already learned from: indistinguishable from a lost message.

## Suggested Solutions

- Keep a `runId → RunHandle` map populated by the launcher; the cancel branch looks it up and calls
  `runtime.cancel`. A restarted replica finds the handle through `discoverOrphans`.
- Until it is implemented, refuse a cancel explicitly with a `RunFailed`-shaped answer rather than
  dropping it.

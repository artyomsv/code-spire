# A gate refusal stops the publisher, not the agent

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-publisher/src/main/java/dev/codespire/publisher/PublishCycle.java` (`handle` returns false on a refusal), `spire-publisher/.../PublisherMain.java` (the loop exits and the process ends normally), `spire-run-worker/.../RunLauncher.java` (`salvage` waits on the agent alone) |
| Found during | PR #95 four-lens review, round 1 (code-reviewer, worker side) |
| Date | 2026-09-02 |

## Issue

RUN-TOPOLOGY §5 says a refusal mid-run terminates the run. What terminates is the publisher: it
stops reading bundles and exits, and nothing reaches the remote — `PublishCycleTest` and
`M0WalkingSkeletonTest` prove that half. The agent keeps running until it finishes or the wall clock
expires, spending model calls on work that can never be published.

## Risks

- An agent that touches a workflow file ninety seconds into a one-hour run burns the remaining
  fifty-eight minutes of paid calls for nothing.
- Combined with the failed-salvage entry, the wall-clock path then reports `SALVAGE_FAILED` without
  the blocked paths, so the operator learns neither what was refused nor why the run cost an hour.

## Suggested Solutions

- Make the refusal reach the worker as a stop signal: the publisher exits with a distinct status on
  a refusal, the launcher watches the publisher's exit beside the agent's, and calls
  `runtime.cancel(handle)` when it sees it.
- Verify by asserting the agent container is no longer running after a refusal, with the blocked
  paths still reported.

# A failed salvage discards every push the publisher reported

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Medium |
| Location | `spire-run-worker/src/main/java/dev/codespire/runworker/RunLauncher.java` (`interpret`, the `!finalization.salvaged()` branch), `spire-runtime-docker/src/main/java/dev/codespire/runtime/docker/DockerRunRuntime.java` (`salvage`) |
| Found during | PR #95 four-lens review, round 1 (code-reviewer, worker side) |
| Date | 2026-09-02 |

## Issue

`interpret` returns `RunFailed("SALVAGE_FAILED", …)` the moment `finalization.salvaged()` is false,
without consulting the publisher's outcome. A wall-clock overrun is the normal way a long run ends,
and `DockerRunRuntime.salvage` reports it as a failed salvage. So an agent that pushed five good
checkpoints over an hour and then overran its clock has five commits on the real remote and a run
record that says `SALVAGE_FAILED` with no `pushedRef` and no `changedPaths` — contradicting the rule
the same method states a few lines below: a run that pushed before failing is reported as finished
because the work is on the branch either way.

The cause code also collapses two different situations: a wall-clock overrun (the agent's doing, not
retryable) and a daemon fault during salvage (retryable). Both read as `SALVAGE_FAILED`,
`retryable=false`.

## Risks

- The operator is told nothing was delivered when the branch holds an hour of work; the run looks
  like an infrastructure fault rather than a timeout, so the wrong thing gets investigated.
- The reconciliation in M2 (a pull request from the branch) would open against commits the run
  record does not know exist.

## Suggested Solutions

- Consult `outcome` in that branch: when `pushedRef` is present, report `RunFinished` carrying the
  ref, the changed paths and a note that the run overran, or add a terminal shape that carries both.
- Separate `WALL_CLOCK_EXCEEDED` from `SALVAGE_FAILED`, and make only the second retryable.
- Assert both in `RunLauncherTest`: a salvage failure after a reported push yields the ref.

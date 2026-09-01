# A run that delivered nothing is recorded as `succeeded` with no ref

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/FactoryRunProjection.java` (`finished`: a `RunFinished` that is not refused writes `succeeded` whatever its `pushedRef`) |
| Found during | PR #95 four-lens review, round 1 (code-reviewer, orchestrator side) |
| Date | 2026-09-02 |

## Issue

`RunFinished` with a null `pushedRef` and no blocked paths is what the worker emits when the agent
exited cleanly and committed nothing — a legitimate outcome, and one `M0WalkingSkeletonTest` shows
for a script that commits nothing. The read model records it as `succeeded`, the same status as a
run whose branch is on the remote. The status vocabulary has no value for "finished, delivered
nothing", which is why this is a decision rather than a one-line fix.

## Risks

- An operator reading the list cannot tell a run that produced a branch from one that produced
  nothing without opening each row; a later "runs succeeded" metric would count both.

## Suggested Solutions

- A distinct terminal status (`finished_empty` or similar) written when `pushedRef` is null and
  `blockedPaths` is empty, with the reviews-list-style pill the UI already knows how to draw for
  "no output"; the CHECK constraints on `factory_run` extended with it.

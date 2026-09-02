# A non-fast-forward push is reported as a generic push failure

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-workspace/src/main/java/dev/codespire/workspace/PublishRepo.java` (`pushRef`: every rejected `RemoteRefUpdate` status becomes `PushRefusedException`), `spire-publisher/.../PublishCycle.java` (one `PUSH_FAILED` cause) |
| Found during | PR #95 four-lens review, round 1 (code-quality M5), carried to round 2 |
| Date | 2026-09-02 |

## Issue

The publisher pushes the run's branch from its own bare clone of the base branch. If the remote's
branch has moved since — a resumed run (RUN-TOPOLOGY §5), a human commit on `spire/<subject>`, two
replicas of the same run — the forge rejects the push as non-fast-forward. `pushRef` checks every
ref update's status and throws, which is right, but the cause reaching the operator is the same
`PUSH_FAILED` as a network fault or a forge ruleset, and nothing fetches the moved ref, rebases, or
says "the branch moved under this run".

## Risks

- A resumed run cannot deliver: its first bundle is refused and the run ends `failed` with a cause
  that points at the forge rather than at the divergence. M1's resume (FR-F31) lands on this.

## Suggested Solutions

- Map `REJECTED_NONFASTFORWARD` to its own cause (`BRANCH_MOVED`), never retryable as-is, so the
  operator and the eventual resume logic can tell it from a transport fault.
- For a resumed run, clone the branch rather than the base commit (as §5 already says) so the push
  is a fast-forward by construction; never force-push from the publisher.

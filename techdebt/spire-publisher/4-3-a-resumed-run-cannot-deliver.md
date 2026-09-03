# A resumed run still cannot deliver; only its diagnosis improved

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-publisher/src/main/java/dev/codespire/publisher/PublisherMain.java` (clones the base branch), `spire-workspace/src/main/java/dev/codespire/workspace/PublishRepo.java` (`cloneBranch`) |
| Found during | M1 Task 1 four-lens review — the half of `3-2-a-non-fast-forward-push-has-no-handling.md` that Task 1 did not close |
| Date | 2026-09-02 |

## Issue

The entry this narrows had two suggestions and Task 1 took the first. A non-fast-forward push is now
its own cause, not retryable, told apart from a forge refusal and from a transport fault. That fixed
what an operator is **told**.

It did not fix what the entry recorded as the risk: *"A resumed run cannot deliver."* The publisher
still clones the base branch and pushes the run's branch from it, so the moment the remote's branch
has moved — a resumed run, a human commit on `spire/<subject>`, two replicas of one run — the push is
refused. The run now ends with an accurate cause instead of a misleading one, and still delivers
nothing.

## Risks

- M1's resume work lands directly on this. A resumed run is the case the whole feature is about, and
  it is the case that cannot push.

## Suggested Solutions

- Clone the run's **branch** when it already exists on the remote, falling back to the base commit
  when it does not, so the push is a fast-forward by construction. RUN-TOPOLOGY §5 already says this.
- **Never force-push from the publisher.** Forcing would discard whatever moved the branch, which
  may be a human's commit, and the push gate cannot judge what it never saw.

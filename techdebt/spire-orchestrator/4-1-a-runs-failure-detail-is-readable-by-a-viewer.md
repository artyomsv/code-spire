# A run's failure detail is readable by a viewer

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunResource.java` (`GET /api/runs/{runId}`, both roles), `FactoryRunProjection.RunView.failureDetail` |
| Found during | PR #95 four-lens review, round 1 (rules-compliance, orchestrator side) |
| Date | 2026-09-02 |

## Issue

`failure_detail` is a container's outcome line or a worker exception message, readable by
`spire-viewer`. ADR-022's second rule is about what is in the payload. The posture matches
`review_status.error`, which a viewer also reads, and the publisher scrubs its secret from every
line it writes — so this is recorded as an accepted posture with one condition: the worker-side
scrub entry (`spire-run-worker/4-2-the-workers-own-failure-details-are-not-scrubbed.md`) is what
keeps it acceptable.

**Condition met (2026-09-02, M1 Task 1).** The worker-side scrub this entry names as its condition
now exists: `SecretScrub` removes the run's own credentials from every `RunFailed` detail in three
forms — literal, percent-encoded, and the Basic-auth pair — and all four of the launcher's failure
paths route through one place, so a fifth cannot be added that bypasses it.

The posture is therefore kept rather than changed, deliberately. Making the detail admin-only would
diverge from `review_status.error`, which a viewer reads for the same class of text, and a viewer
who can see that a run failed but not why is sent to ask an admin for a line the admin will simply
read out. What made the posture defensible was the scrub, and the scrub is now there.

## Risks

- Reduced, not removed. The scrub removes the secrets of the run it is cleaning, so a detail quoting
  a credential this run does not hold — the worker's own proxy password, say, or a registry secret
  from its environment — still reaches a viewer. That is the residual, and it is the reason this
  entry stays open at Low rather than being deleted.

## Suggested Solutions

- Either the worker-side scrub, or making `failureDetail` admin-only on the view while keeping
  `failureCause` for viewers.

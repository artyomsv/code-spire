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

## Risks

- If a future detail quotes an environment or a URL with a credential, a viewer sees it.

## Suggested Solutions

- Either the worker-side scrub, or making `failureDetail` admin-only on the view while keeping
  `failureCause` for viewers.

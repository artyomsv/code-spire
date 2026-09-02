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
now exists. `SecretScrub` removes the run's credentials in three forms — literal, percent-encoded,
and the Basic-auth pair — and `RunFailures` is the single collaborator both the launcher and the
dispatcher build failures through, so a new failure site cannot bypass it.

The first version of this note was wrong twice, and is corrected here rather than quietly amended.
It claimed every `RunFailed` detail was covered while only the launcher's were, and the scrub omitted
the model key — the credential this entry's own risk paragraph names. Both are closed now.

The posture is therefore kept rather than changed, deliberately. Making the detail admin-only would
diverge from `review_status.error`, which a viewer reads for the same class of text, and a viewer
who can see that a run failed but not why is sent to ask an admin for a line the admin will simply
read out. What made the posture defensible was the scrub, and the scrub is now there.

## Risks

- Reduced, not removed. The scrub removes the secrets of the run it is cleaning, so a detail quoting
  a credential that run does not hold — the worker's own proxy password, a registry secret from its
  environment — still reaches a viewer. A run whose credentials will not decrypt is unscrubbed, and
  that degradation is now logged without naming what failed.
- Two classes of content the first version of this note did not weigh. Daemon and transport errors
  quote infrastructure inventory — registry hosts, addresses, socket paths, image references — which
  ADR-022's third rule makes admin-only elsewhere. And agent-influenced text reaches this field
  through the publisher, so the first UI that renders it must render it as text.
- The calculus has also changed: now that the cause is a closed set, a viewer learns *why* from
  `failureCause` alone, so "cause for a viewer, detail for an admin" costs less than it did when
  this posture was accepted. Worth re-deciding rather than assuming; that is why this stays open.

## Suggested Solutions

- Either the worker-side scrub, or making `failureDetail` admin-only on the view while keeping
  `failureCause` for viewers.

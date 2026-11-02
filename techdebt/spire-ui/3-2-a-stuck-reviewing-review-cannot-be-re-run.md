# A review stuck at `reviewing` cannot be re-run from the dashboard

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-ui/src/components/ReviewActions.tsx` (`OFFERS_RERUN`, `reviewing: false`) |
| Found during | Harness reviews step 5 live proof, `spire-test` #93 (PR #368) |
| Date | 2026-11-01 |

## Issue

`OFFERS_RERUN` hides Re-run while a review is `reviewing`: a run is in flight, and a re-run would drop its claims
and pay for a second. That holds while something is in flight. It does not hold when nothing is: on #93 the
harness start threw on `ContextAssembled` (fixed in `939a1277`), the message was dead-lettered, and the review sat at
`reviewing` with no run, no worker command and no attention row. The only way on was a `/review` comment on the
pull request. `ReviewRerunService.rerun()` itself gates only on `archived`.

## Risks

Any future defect that leaves a review at `reviewing` with nothing in flight strands it on the dashboard. An
operator without forge access to comment cannot recover it.

## Suggested Solutions

1. Offer Re-run on `reviewing` once the review is stuck: no run attached and no status change for longer than the
   review's wall clock, or a `REVIEW_STUCK` attention row open. Keep the confirm dialog's cost warning.
2. Offer Re-run on `reviewing` always, behind a confirm that names the in-flight run it supersedes.

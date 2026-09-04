# A fork pull request is indistinguishable from a same-repository one, and ADR-040 needs the difference

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Medium |
| Location | `spire-contract/.../event/IntegrationEvent.java` (`PullRequestEventReceived`); the three `spire-scm-*` ingresses; `review_status`; `spire-orchestrator/.../factory/FixTargets.java` |
| Found during | M2 task 5b — writing `FixTargets`, the orchestrator half of ADR-040 |
| Date | 2026-09-04 |

## Issue

**Nothing in this deployment records whether a pull request came from a fork.** Not
`review_status`, not `PullRequestEventReceived`, not any ingress. A repo-wide search for `fork`
across the migrations and the contract returns one unrelated hit about prompt templates.

That did not matter while the factory only ever pushed to branches it created under `spire/`.
ADR-040's `existing` mode changes it: a fix now pushes to the branch `review_status.source_branch`
names, in the repository `workspace`/`slug` names — and for a fork pull request those two do not
belong together. `source_branch` is a branch in the **contributor's fork**; the clone URL is the
**base** repository.

Two outcomes, both wrong:

- The base repository has no branch of that name → the push **creates one**. A stray branch appears
  on the operator's repository, attached to no pull request, and reconciliation never joins because
  no review is watching it.
- The base repository **does** have a branch of that name → the fix is pushed onto an unrelated
  branch. This is the bad one: someone else's work receives a machine-authored commit produced from
  a completely different diff.

ADR-040 already says forks are out of scope for `existing` mode. The decision is right; the
**enforcement does not exist**, and `FixTargets.isPushable()` cannot supply it.

## Risks

High, and it is the reason this is filed rather than carried quietly.

The trigger is ordinary: an outside contributor opens a pull request from their fork — the normal
shape on any public repository — the reviewer raises a finding, and an allowlisted maintainer types
`/fix`. Nothing in the chain refuses it. The publisher's floor does not catch it either: that floor
refuses trunks and the destination branch, and a fork's source branch is neither.

It is not reachable **today**, because nothing dispatches with `existing` mode yet — the mode exists
and no caller sets it. That is precisely why this is filed now, while it is still cheap and still
ahead of the code that would trigger it.

**`existing` mode must not be enabled on a repository that accepts fork pull requests until this is
closed.** That sentence belongs in the operator documentation before the mode ships.

## Suggested Solutions

1. **Record it at the ingress, which is the only place that knows.** All three providers expose it
   directly in the webhook payload — GitHub compares `pull_request.head.repo.full_name` with
   `base.repo.full_name`, GitLab `object_attributes.source_project_id` with `target_project_id`,
   Bitbucket `pullrequest.source.repository.full_name` with `destination.repository.full_name`.
   `PullRequestEventReceived` gains a `fromFork` component (additively, with a convenience
   constructor, the same treatment `mentions` and `location` took), `review_status` gains a column,
   and `FixTargets.isPushable()` gains one clause. The parity test in the gateway is the right home
   for asserting all three agree, since disagreeing about it is the failure that matters.
2. **Refuse `existing` mode outright until (1) lands**, by making `isPushable()` answer false
   whenever the mode would be used. Safe and honest, and it makes the exit criterion unreachable —
   which is the point: it turns a silent hazard into a visible blocker.
3. Leave it. Defensible only while no caller sets `existing` mode, which is true at the moment this
   entry is written and stops being true in the very next slice. It is written down here so that
   slice cannot land without meeting this.

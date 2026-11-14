# Block approval at: a per-repository approval threshold

Operator request, 2026-11-14. Live on `spire-test` #106 the bot posted **Approve** next to an open MAJOR
finding. To the operator that reads as a contradiction. Today `ReviewVerdictDecision` counts open BLOCKER
findings only (`ReviewProjection.openBlockers`), and the factory land gate (`WorkReview.advance`) waits on
the same count.

## Decided with the operator

- One repository setting, **Block approval at**, next to "Reviewer may approve" (Settings → Repositories).
- Values: `BLOCKER` (default, today's behaviour), `MAJOR` (Major and above), `MINOR` (Minor and above).
  `INFO` and `NIT` never block.
- An open finding at or above the level makes the assessment **Request changes**; none makes it **Approve**.
  A degraded review stays a Comment.
- "Reviewer may approve = off" still wins: the bot posts a Comment and never changes merge state.
- The factory land gate uses the same level: a factory PR lands only when no open finding is at or above it.
- The review page counts open findings at or above the level as "blocking" for that review.
- An unknown repository blocks at `BLOCKER`.

## Shape (builder's calls, recorded)

- Column `repository.block_approval_at TEXT NOT NULL DEFAULT 'BLOCKER'`, checked to the three values
  (migration V115). Every repository that exists today keeps today's behaviour.
- Enum `BlockApprovalAt { BLOCKER, MAJOR, MINOR }` in the orchestrator's repository package, with
  `blocks(Severity)`.
- `ReviewVerdictDecision.decide` takes one parameter record instead of five arguments, with no `Optional`
  parameter. The reason keeps `open_blockers=N` at `BLOCKER`; other levels name the level
  (`open_at_or_above_major=N`).
- The timeline line `ReviewVerdictDecided` shows the level, so an operator can see why a MAJOR held a PR.

## Also in this PR: step 8 minors

From PR #419's deferred list: the pull ref number is checked against the PR number; the fork fetch gets a
transport timeout; the `fork_requires_credential_proxy` match is strict; the reply refusal names the
harness; stale comments and text are fixed; the redundant null default and the old 11-argument
`PullRequestEventReceived` constructor are removed. Skipped on purpose: the leftover `refs/spire-head/pull`,
the system gitconfig tripwire, the retry on an auth failure, and the mutation check.

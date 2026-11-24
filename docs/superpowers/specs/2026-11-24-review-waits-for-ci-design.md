# Review waits for CI

Status: design, 2026-11-24. Part of #391.

## Why

On the first production reviews the bot reviewed the moment a pull request opened. On code-spire #444 it approved
while CI was still running, and CI then failed. An approval on a red build is wrong, and a review of code CI rejects
anyway spends money for nothing. The operator's workflow reviews a pull request whose checks have finished, and
labels a passed review with failing CI `review: ci-failed`.

## Goal

A per-repository setting **"Start review"**: `immediately` (today's behaviour, the default) or `after checks
finish`. With `after checks finish`, a review of a head commit starts only once every check on that commit has
finished, and the verdict takes the CI result into account.

## Behaviour with "after checks finish"

1. **Wait.** When a review would start for head H (opened, new commit, re-run), the orchestrator records it as
   waiting for checks and starts nothing. A sweep reads H's checks (GitHub commit statuses and check runs, the same
   reading the factory land path already does, `GitHubCheckReduction`) every 30 s.
2. **No checks.** A commit with no statuses and no check runs at the first read is reviewed at once (a repository
   without CI must not wait forever).
3. **Finished.** When nothing is pending, the review starts as today, with the CI result attached.
4. **Verdict with CI.**
   - CI passed: the verdict is unchanged.
   - CI failed and the review would approve: the review is posted as a Comment (never an Approve), the summary says
     which checks failed, and with Verdict labels on the label is `review: ci-failed`.
   - CI failed and the review requests changes: unchanged (`review: changes-requested`); the summary also names the
     failed checks.
5. **Superseded.** A new commit while waiting replaces the wait for the old head (the existing supersede rule).
   A closed pull request stops the wait.
6. **Timeout.** Checks still pending after 60 minutes: the review starts anyway, never approves (posted as Comment),
   and says CI had not finished. The dashboard shows the review as "waiting for checks" with the time waited until
   then.

## Non-goals

- GitLab and Bitbucket (GitHub first, as with labels; the setting is hidden for them).
- Webhook-driven wake-up (`check_suite` events). Polling first; a webhook can shorten the wait later.
- Required versus optional checks: every check counts.

## Where

- Orchestrator: the repository setting (next migration), the waiting state on the review, the sweep, and the CI
  result carried to the decision (`ReviewVerdictDecision`).
- SCM port: a read of a commit's check summary for reviews, reusing the GitHub land reader.
- Worker/labels: `review: ci-failed` joins the verdict labels.
- UI: the setting beside "Verdict labels"; a "Waiting for checks" status in the review list and detail (status union
  rule in CLAUDE.md).

## Tests (CI only)

Wait then start on success; immediate start with no checks; failure turns an approve into a Comment with
`review: ci-failed`; failure keeps request changes; supersede and close end the wait; timeout starts a non-approving
review; `immediately` behaves exactly as today. Live proof on spired.dev with code-spire.

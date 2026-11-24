# Review waits for CI

Status: design, 2026-11-24, operator decisions recorded the same day. Part of #391.

## Why

On the first production reviews the bot reviewed the moment a pull request opened. On code-spire #444 it approved
while CI was still running, and CI then failed. An approval on a red build is wrong, and a review of code CI rejects
anyway spends money for nothing.

## Goal

A per-repository setting **"Start review"**: `immediately` (today's behaviour, the default) or `after checks
finish`. With `after checks finish`, a review of a head commit starts only once every check on that commit has
finished green; a red build gets no review until a new commit (operator decision: option B, skip on red).

## Behaviour with "after checks finish"

1. **Wait.** When a review would start for head H (opened, new commit, re-run), the orchestrator records the review
   as waiting for checks on H and starts nothing.
2. **Wake-up by webhook.** GitHub sends the repository webhook `check_suite` (action `completed`) and `status`
   events. On either, for a head that has a waiting review, the orchestrator reads H's checks at once. The read is
   the one the factory land path already does (commit statuses plus check runs, `GitHubCheckReduction`). The webhook
   only wakes; the read decides, so a forged or out-of-order event cannot start or skip a review by itself.
3. **Fallback polling.** A sweep reads every waiting head's checks every 5 minutes, so a lost or never-configured
   webhook event only delays a review.
4. **No checks.** A commit with no statuses and no check runs at the first read is reviewed at once (a repository
   without CI must not wait forever). A short grace read (60 s after the wait starts) guards against checks that
   register a few seconds late.
5. **Green.** Nothing pending and nothing failed: the review starts as today.
6. **Red.** Nothing pending and at least one check failed: no review runs and nothing is charged. The review is
   recorded as `skipped: checks failed` with the failed check names (dashboard), and with Verdict labels on the pull
   request gets `review: ci-failed`. No comment is posted. A new commit starts a new wait.
7. **Superseded / closed.** A new commit while waiting replaces the wait for the old head (existing supersede rule);
   a closed pull request ends the wait.
8. **Timeout.** Checks still pending after 60 minutes: the review starts anyway, never approves (posted as a Comment),
   and its summary says CI had not finished. With Verdict labels on it sets no `review: approved` (a blocking
   review still labels `review: changes-requested`; ADR-047, amended 2026-11-24).
9. **One resolver per run.** Webhook and sweep may read one wait at once. Claiming the waiting row, keyed by the run's
   round, is the permit to start or skip; a skip ends the run under the review's round lock and only while its round is
   the review's, so it never cancels a started run or a re-run of the same head. A claim survives a crash and the
   sweep finishes it (a FetchDiff or label may be sent twice, never zero times).

## Webhook setup

The Reviewer webhook gains two events: **Check suites** and **Statuses**. The dashboard's webhook instructions list
them; an existing webhook without them still works through the fallback polling. Both are delivered to repository
webhooks (`check_suite` only for `completed`, which is the one needed).

## Non-goals

- GitLab and Bitbucket (GitHub first; the setting is hidden for them).
- Required versus optional checks: every check counts.
- `workflow_run` events: `check_suite` already covers Actions and other check providers.

## Where

- Gateway: accept `check_suite` and `status` on the Reviewer webhook and publish a "checks changed for commit H"
  integration event (repository + head SHA only).
- Orchestrator: the repository setting (next migration), the waiting/skipped state on the review, the wake-up
  handler, the 5-minute sweep, the 60-minute timeout, and the not-approving path for a timeout.
- SCM port: a read of a commit's check summary for reviews, reusing the GitHub land reader.
- Worker/labels: `review: ci-failed` joins the verdict labels; a skipped review sets it without posting anything.
- UI: the setting beside "Verdict labels"; statuses "Waiting for checks" and "Skipped: checks failed" (status union
  rule in CLAUDE.md); webhook instructions list the two new events.

## Tests (CI only)

Wait then start on green; webhook wake-up starts it without waiting for the sweep; sweep starts it when no webhook
arrives; immediate start with no checks; red skips the review, charges nothing and sets `review: ci-failed`; a new
commit after red waits again; supersede and close end the wait; timeout starts a non-approving review; `immediately`
behaves exactly as today; an event for a head with no waiting review does nothing. Live proof on spired.dev with
code-spire.

# GitLab + Bitbucket Full-Flow Parity — Design

**Date:** 2026-07-23
**Status:** Approved (brainstorm), pending implementation plan
**Roadmap:** item 13 (SCM parity live-testing for the full loop)

## Goal

Bring the GitLab and Bitbucket Cloud SCM adapters to full functional parity with the finalized
GitHub adapter so an operator can **manually test the complete review loop** on both platforms:
**webhook ingress → review → in-thread conversation → re-review reconciliation** — the same flow
proven live on GitHub (`artyomsv/spire-test` PRs #8–#11).

## Reference implementation

`spire-scm-github` is the proven reference. Every behavior below is ported from it; where GitHub's
mechanism is platform-specific (GraphQL thread resolve), the GitLab/Bitbucket equivalent is used.
The GitHub adapter is **not modified** by this work.

## Approach (chosen: A)

Mirror the reference adapter-by-adapter, one workstream at a time, each independently reviewable
with WireMock unit coverage plus a runbook step for the human's live pass. No shared-base refactor
(rejected option B) — the conversation logic is genuinely per-provider and refactoring would risk
regressing the one live-proven adapter.

## Current state (verified by adapter inventory, 2026-07-23)

| Leg | GitLab | Bitbucket |
|---|---|---|
| Webhook ingress | Built + wired, never live-tested; no draft-skip | Live-verified (Mode B); no draft-skip; `AuthorReplied` always `topLevel=false` |
| Review (diff→LLM→inline+summary) | Live-verified (Mode D); single-line anchors only | Live-verified (Mode B) |
| Conversation | **NOT BUILT** — no `ThreadSource`, ingress emits no `AuthorReplied` | **NOT BUILT** — ingress emits `AuthorReplied` but `FollowUpWorker` no-ops (no `ThreadSource`) |
| Reconciliation | Built (compare + resolve + note-update), never live-tested | Built reply-only (`resolveThread`=UNSUPPORTED by API); compare direction unverified |

The shared `FollowUpWorker.doAnswer` dead-ends at `FollowUpWorker.java:91`
(`if (!(clients.comments() instanceof ThreadSource threadSource)) return;`) — the entire
conversation loop is gated on the CommentSink also implementing `ThreadSource`. The worker logic
(smart-1:1 `shouldAnswer`, per-comment idempotency, LLM call, `replyInThread`) is already
provider-neutral and needs no change.

## Non-goals / carried-forward limitations

- **Bitbucket reconciliation stays reply-only.** Bitbucket Cloud has no PR-comment resolve API;
  `resolveThread` remains `UNSUPPORTED` and re-reviews post a fresh reply instead of resolving the
  prior thread (ADR-019's documented degradation). Not a defect.
- **Bitbucket inline comments stay single-anchor.** The Bitbucket inline model has no start/end
  range; NEW-side multi-line ranges are not projected there (API constraint). Findings on
  off-diff lines continue to fold into the summary.
- No changes to the GitHub adapter, the orchestrator sagas, the `FollowUpWorker`, or the
  `ReviewWorker` reconciliation core — this is adapter + ingress + runbook work only.
- No shared `spire-scm-common` extraction.

---

## W1 — Conversation loop

The largest workstream. Splits into four independently-testable pieces.

### W1a — `GitLabCommentSink implements ThreadSource`

- Add `ThreadSource` to `GitLabCommentSink` (mirrors `GitHubCommentSink implements CommentSink,
  ThreadSource`).
- `fetchThread(repo, prId, threadRef)` reads the MR discussion:
  `GET /projects/{enc-path}/merge_requests/{iid}/discussions/{discussion_id}` → `notes[]`. Each
  note maps to a `ThreadMessage` (author = `note.author.username`/id, text = `note.body`,
  `fromBot` = author id matches the bot). Skip `system: true` notes (state changes, not human turns).
- Bot attribution: resolve the bot's numeric user id lazily via `whoami` (`GET /user`), GitHub-style
  (`GitHubCommentSink.botLogin()` degrades to best-effort on transient failure — same posture).
- `commit()` = the discussion's `position.head_sha` when present (inline discussion); `null` for a
  top-level note discussion (the worker then resolves the PR head — `FollowUpWorker.java:177`).
- GitLab models every note as a discussion, so one code path serves both inline and top-level threads.

### W1b — `BitbucketCloudCommentSink implements ThreadSource`

- Add `ThreadSource` to `BitbucketCloudCommentSink`.
- `fetchThread(repo, prId, threadRef)` pages PR comments
  (`GET …/pullrequests/{id}/comments`, `values[]`, follow `next`), rebuilds the parent→child tree,
  and collects the subtree rooted at the root comment id (`threadRef`). Each comment maps to a
  `ThreadMessage` (author = `user.account_id`/nickname, text = `content.raw`, `fromBot` = account id
  matches the bot). Honour the existing `MAX_THREAD_PAGES`-style page cap with a truncation warn.
- Bot attribution: resolve the bot's `account_id` via `whoami`.
- `commit()` = `null` (Bitbucket inline comments carry no commit sha; worker resolves head).

### W1c — `GitLabIngress` emits `AuthorReplied`

- Currently parked (non-command notes emit nothing). For a Note event with
  `noteable_type == "MergeRequest"` whose body does **not** start with `/`:
  - Threaded note (`object_attributes.type` is `DiffNote`/`DiscussionNote`, or a `discussion_id`
    that is not the note's own top-level id) → `AuthorReplied(topLevel=false)` keyed to the
    `discussion_id` as the thread root.
  - Top-level MR comment (individual note, no discussion thread) → `AuthorReplied(topLevel=true)`.
  - `triggeringCommentId` = the note id; `author` = the note author's numeric id as
    `providerUserId`; `mentioned` = the bot's `@username` appears in the body.
- The bot's own replies fire Note webhooks too; the orchestrator self-loop guard (ADR-013) drops
  bot-authored events downstream, exactly as for GitHub — the ingress does not special-case them.

### W1d — `BitbucketCloudIngress` sets `topLevel` correctly

- Today every plain-comment `AuthorReplied` is built with the 7-arg ctor → `topLevel=false`.
- Set `topLevel=true` when the comment is a plain top-level PR comment (no `inline` field, no
  `parent`) — mirrors GitHub's top-level `issue_comment` handling. Inline/threaded replies stay
  `topLevel=false` keyed to the thread root.

---

## W2 — Bitbucket compare-diff direction

- Verified against Bitbucket's REST docs: `diff/{spec}` uses `spec = {source}..{destination}`, and
  additions come from the **source** (first token). The adapter builds `diff/{head}..{base}`
  (`BitbucketCloudDiffSource.java:90`), so new code appears as additions relative to `base` —
  **correct**. Do **not** flip it.
- Harden the WireMock reconciliation test to document the expected semantic (source=head=additions),
  and add a **mandatory live-verify gate** to the runbook: after a Bitbucket re-review, confirm the
  reconcile diff shows the new commit's lines as additions (not reversed). The documented remedy if
  live shows reversal is to swap to `base..head` — but the analysis says the current orientation is
  right.

---

## W3 — Draft / WIP skip (both adapters)

Reuse the existing provider-neutral flag `spire.review.draft-prs` (default `false` = skip drafts),
already wired for GitHub via `SPIRE_REVIEW_DRAFT_PRS`. Both ingresses gain a `reviewDrafts` ctor
arg exactly like `GitHubIngress`.

- **GitLab:** skip `open`/`reopen`/`update` when `object_attributes.work_in_progress == true` (or a
  `Draft:`/`WIP:` title) unless `reviewDrafts`. A draft→ready transition (an `update` whose
  `changes` clears the WIP flag / drops the `Draft:` title prefix) emits `OPENED` so the
  now-ready MR is reviewed even without a new commit — matching GitHub's `ready_for_review`.
- **Bitbucket:** skip `pullrequest:created`/`pullrequest:updated` when `pullrequest.draft == true`
  unless `reviewDrafts`. A `pullrequest:updated` whose payload is now non-draft is reviewed
  normally.

---

## W4 — Rate-limit / Retry-After intelligence (both adapters)

Adapters only **classify**; the worker's existing backoff (`FollowUpWorker.backoff`, review-worker
retry budget) consumes the signal. Mirror GitHub's exception-carried approach.

- **GitLab:** `GitLabApiException` overrides `retryAfterSeconds()`; `GitLabClient` parses the
  `Retry-After` header (and GitLab's `RateLimit-Reset` epoch → seconds-from-now) on a 429.
  (GitLab does not use GitHub's 403-secondary-limit shape, so 429 is the only rate-limit trigger.)
- **Bitbucket:** `BitbucketApiException` overrides `retryAfterSeconds()`; `BitbucketCloudClient`
  parses `Retry-After` on a 429.
- `isRateLimited()` already returns true for 429 via the `ScmApiException` default on both — no
  change needed there.

---

## W5 — GitLab NEW-side multi-line inline ranges

GitLab's inline discussion `position` supports a `line_range` (start/end on the new side), so the
one review-leg gap on GitLab can be closed (Bitbucket cannot — see non-goals).

- `GitLabCommentSink.postInline` projects an `InlineAnchor` NEW-side range
  (`endNewLine != null && endNewLine > newLine`) into `position.line_range` (start = `newLine`,
  end = `endNewLine`, both `new_line`), matching GitHub's `start_line`+`line` behaviour.
- Single-line and OLD-side anchors are unchanged. On a GitLab 4xx rejecting the range (interior
  lines not all on the diff), fold the finding into the summary rather than aborting the batch —
  same degradation as GitHub's 422 handling.

---

## W6 — Runbook (`docs/SMOKE-TEST.md`)

- **New Mode F — real GitLab MR via webhook.** Parallels Mode E (GitHub webhook) but for GitLab:
  `X-Gitlab-Token` secret, Merge Request + Note events, the same per-repo keyed edge
  `POST /webhooks/gitlab/{key}`, Tailscale-Funnel exposure.
- **Conversation + reconciliation steps** added to the Bitbucket (Mode B) and GitLab (Mode D + new
  Mode F) walkthroughs: reply in a bot thread → the bot answers in-thread; push a follow-up commit →
  reconciliation runs (verdicts, resolve[GitLab] / reply[Bitbucket], summary updated in place).
- **Bitbucket compare live-verify step** (from W2).
- **Updated "known limits":** draft-skip now applies to all three SCMs; Bitbucket reconciliation is
  reply-only; Bitbucket inline is single-anchor.

---

## Testing strategy

- **Per-adapter WireMock unit suites** for CI (matching the current pattern — each adapter already
  has `*ApiTest` / `*ReconciliationTest` / `*IngressTest`). New coverage: GitLab/Bitbucket
  `fetchThread` (transcript shape, bot attribution, page-cap truncation), GitLab `AuthorReplied`
  emission (threaded vs top-level, `@`-mention, command-vs-reply disambiguation), Bitbucket
  `topLevel` flag, draft-skip both ways per adapter, `retryAfterSeconds` parsing per adapter,
  GitLab NEW-side multi-line range inline.
- **Live verification is the human's runbook pass** (W5) — the core problem is "built but never run
  live," which unit tests cannot close. Success = the operator runs the full loop end-to-end on a
  real GitLab MR and a real Bitbucket PR and observes review → conversation → reconciliation, plus
  the Bitbucket compare-direction check.

## Success criteria

1. A reply in a GitLab MR discussion and in a Bitbucket PR thread each gets an in-thread bot answer.
2. A follow-up commit on a GitLab MR and a Bitbucket PR each runs reconciliation
   (GitLab resolves fixed-finding discussions + updates the summary note; Bitbucket replies to
   fixed-finding threads + updates the summary comment).
3. Draft MRs/PRs are skipped until marked ready on both platforms (unless `SPIRE_REVIEW_DRAFT_PRS`).
4. GitLab webhook auto-registers MRs on open/update/reopen/close via Mode F.
5. The Bitbucket compare direction is confirmed correct against a live workspace.
6. All new WireMock suites green; existing suites unaffected; the GitHub adapter untouched.

## Risks

- **Bitbucket compare direction** — analysis says correct, but only a live check settles it. Gated
  in the runbook; the flip is a one-line documented remedy if live shows reversal.
- **GitLab draft→ready transition** — the `changes` payload shape for un-drafting needs confirming
  against a live event during the runbook pass; the fallback (review on the next commit) is safe.
- **Bitbucket thread reconstruction** — parent-chain paging must handle a thread spanning multiple
  comment pages; covered by the page-cap + truncation warn.

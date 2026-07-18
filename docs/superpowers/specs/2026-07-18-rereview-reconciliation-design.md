# Re-review Reconciliation on Follow-up Commits — design spec

**Status:** APPROVED design (brainstormed 2026-07-18). Supersedes the earlier draft in this file.
Next step: implementation plan via `superpowers:writing-plans`.

**Date:** 2026-07-18

## Problem

When a fix commit is pushed to an already-reviewed PR, today's behavior is a blind full re-review:

- Comment idempotency is keyed by `(review_id, commit, anchor_key)` — per commit, so a still-open
  issue is re-posted as a duplicate top-level comment on the new commit.
- Nothing detects that an issue was fixed — threads stay open forever; `resolveThread` was specced
  (S8) but never built.
- The re-review is stateless w.r.t. the previous run.

## Decisions (locked)

1. **No intermediate deterministic-dedup phase.** Build the LLM reconciliation directly; a
   skip-if-anchor-exists dedup would be throwaway.
2. **Resolved findings: reply + auto-resolve the thread.** The reply ("fixed in `<sha>` — …")
   documents the closure; a human can always unresolve.
3. **Human pushback: concede when reasonable.** A fourth verdict `acknowledged` — the LLM sees the
   thread transcript; if the human's case holds, the bot replies a short concession and resolves.
   If it doesn't hold (e.g. a real security hole), the finding stays `still-open` with a reply
   explaining why.
4. **Prior-run state is command-carried.** The orchestrator (single source of truth, ADR-010) packs
   prior findings into the follow-up command, like it already brokers credentials (ADR-015) and
   ships findings inline in `PostComments` (ADR-011 precedent). No worker-side snapshot of domain
   state; no sync REST between services. Size is a non-issue: 5–20 findings ≈ 1–2 KB.
5. **Two focused LLM calls, not one combined call.** A small reconcile call plus the existing
   review call. Total input tokens ≈ the combined call (inputs are partitioned, not duplicated);
   each call does one job; the battle-tested review prompt and `FindingsParser` survive nearly
   untouched.
6. **Summary comment is updated in place** (new `updateComment` capability); fall back to posting a
   fresh summary if the old one is gone.
7. **Bitbucket Cloud degrades to reply-only** — it has no thread-resolve API for PR comments;
   `supportsResolve()` capability flag gates the resolve action.

## Flow

The existing pipeline is unchanged in shape — `FetchDiff → GatherContext → GenerateReview →
ReviewGenerated → PostComments → CommentsPosted` — including the ADR-013 stale-run guard between
generate and post. Reconciliation changes what flows through it:

- On a follow-up commit the lifecycle already emits `ReviewSuperseded + ReviewRequested`. At
  command-brokering time the orchestrator checks whether the review has a prior run that reached
  `CommentsPosted`. If yes, it packs into `GenerateReview`:
  - `priorHead` — the last-reviewed commit sha,
  - `priorFindings` — each with severity, path, line, message, `threadRef`,
  - `priorSummaryRef` — the summary comment id (from `review_thread` `is_summary`).
- **First review** (no prior posted run): fields empty → worker behaves exactly as today.
- Prior run = the most recent run that actually posted comments. A run superseded mid-flight
  contributes nothing.
- **Baseline carry-forward (refinement):** `priorFindings` comes from `posted_findings_json`, which
  snapshots `open_findings_json` (falling back to `findings_json` when absent) — a baseline that
  unions each round's brand-new findings with every prior finding still `STILL_OPEN`/`UNCHANGED` (or
  unmatched by any verdict), carrying its original `threadRef` forward. Without this, a still-open
  finding would drop out of `priorFindings` after one round and get re-posted as "new" the round
  after. The dashboard's reconciliation view (`reconciliation_json`) is a merge-upsert across rounds
  for the same reason: a finding resolved in an earlier round must stay visible, not vanish once a
  later round's verdicts overwrite the column.

## Worker: reconcile call + review call + deterministic merge

When `GenerateReview` carries prior findings:

1. **Reconcile call** (clip ~12k tokens). Inputs: prior findings, their thread transcripts
   (re-fetched live via `fetchThread` — ADR-011, nothing persisted), and the **incremental diff**
   (`priorHead → head`, new SPI below). Output — one verdict per prior finding:
   - `resolved` — the fix addresses it,
   - `still-open` — the author attempted something relevant but the issue remains, with *what is
     still missing*,
   - `acknowledged` — human pushback conceded,
   - `superseded` — the flagged code was deleted/rewritten so the finding no longer applies,
   - `unchanged` — the changes do not touch or affect this finding at all; a deterministic backstop
     also downgrades any `still-open` verdict to `unchanged` when its finding's path is absent from
     the incremental diff's touched paths (skipped when the incremental diff is unavailable).
2. **Review call.** Today's full-diff prompt (24k-token clip) plus one new section: "already
   reported — do not re-report" listing the prior findings. Output: new findings via the existing
   lenient parser.
3. **Merge (plain code, no LLM):** drop any new finding whose `path:line` anchor collides with a
   still-open or unchanged prior finding — the safety net against semantic re-finds at the same spot.

Each call has its own LLM idempotency claim (`LLM:reconcile`, `LLM:review` — extending today's
single `LLM` claim, same mark-before-emit + persisted-result re-emit semantics). No duplicate spend
on redelivery.

`ReviewGenerated` carries findings **plus verdicts**; after the stale-run check, `PostComments`
carries both. Posting acts per verdict:

| Verdict | Action |
|---|---|
| `resolved` | Reply in the existing thread ("Fixed in `<sha>` — …") + resolve the thread |
| `acknowledged` | Short concession reply + resolve |
| `still-open` | Reply in the existing thread ("Still open after `<sha>`: …") — never a new top-level comment |
| `superseded` | Reply noting the code changed + resolve |
| `unchanged` | No reply, no resolve — no thread interaction at all; counted as open, badge-only in the dashboard |
| new finding | Fresh inline comment (today's path) |

Summary: `updateComment` on `priorSummaryRef` with resolved / still-open / new counts; fresh post
if missing. Every reply/resolve gets its own `comment_idempotency` claim under the new commit
(anchor keys `reply:<threadRef>`, `resolve:<threadRef>`) — crash-safe, at-least-once-proof. If the
SCM reports the thread already resolved (a human beat us to it): skip both the resolve and the
reply for `resolved` / `acknowledged` / `superseded` verdicts (nothing to add); for `still-open`,
post the reply anyway — a resolved-but-unfixed thread is exactly what the author needs to see.

## New SCM capabilities (SPI)

- `DiffSource.fetchCompare(repo, base, head)` — incremental diff. GitHub
  `GET /repos/{o}/{r}/compare/{base}...{head}`, GitLab `GET /projects/:id/repository/compare`,
  Bitbucket `GET /2.0/repositories/{ws}/{slug}/diff/{base}..{head}`.
- `CommentSink.resolveThread(repo, prId, threadRef)` — GitHub GraphQL `resolveReviewThread`,
  GitLab resolve-discussion REST, Bitbucket: unsupported → `supportsResolve()` returns false,
  actions degrade to reply-only.
- `CommentSink.updateComment(repo, prId, commentRef, body)` — summary edit; plain PATCH/PUT on all
  three providers.

**Force-push fallback:** if the compare fails (prior head unreachable after history rewrite), the
reconcile call runs with the full diff only and the prompt states "incremental diff unavailable —
judge from current state." The pipeline never aborts because of a force-push.

## Orchestrator: events, read model, UI

- `ReviewGenerated` and `CommentsPosted` (contract events) extended with per-finding verdicts and
  per-thread outcomes (replied / resolved).
- ResultSaga marks `review_thread` rows resolved and stamps each finding with its verdict in the
  read model.
- Review detail UI: a reconciliation banner (N resolved · N still open · N new) and a verdict badge
  per prior finding; resolved threads render collapsed/checked.
- Docs ride along: new EVENT-MODEL slice (S11 Reconciliation), a new ADR — **re-reviews post
  deltas, not the full finding set** (changes the `PostComments` contract), CONTRACT §5 updates for
  the extended commands/events.

## Testing

- **Unit:** reconcile prompt builder; verdict parser (lenient, like `FindingsParser`); merge /
  anchor-filter; lifecycle prior-run resolution (superseded-mid-flight runs excluded).
- **Worker split test (Testcontainers Kafka + Postgres, WireMock SCM):** command with prior
  findings → stub LLM returns verdicts + findings → exactly the delta actions hit the SCM
  (replies, resolves, one new comment, summary PATCH); redelivery posts nothing twice.
- **E2E:** PR reviewed → follow-up commit webhook → threads replied/resolved, new finding posted
  once, summary updated, zero duplicate top-level comments.

## Out of scope

- Bitbucket/GitLab-specific resolve parity work beyond the capability flag (GitLab gets resolve;
  Bitbucket reply-only until its API allows more).
- Cross-PR or cross-review memory (each review reconciles only against its own prior run).
- Special handling for same-commit re-runs (manual "/review" on an unchanged head): the same
  reconciliation path runs with an empty incremental diff — verdicts then rest on the thread
  transcripts and full diff, and the exclusion list + thread-reply path still prevent duplicate
  top-level comments. No separate code path.

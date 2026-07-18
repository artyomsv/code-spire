# Nest Finding-Linked Conversations Under Their Findings (review detail)

**Status:** Design proposed, pending review
**Date:** 2026-07-18
**Scope:** Review-detail **display only** — reshape the flat Conversation panel so a conversation
about a specific finding renders *inside* that finding (collapsible), and conversations not tied to a
finding move to a separate "General discussion" section. No change to conversation behaviour, policy,
or the SCM.

Builds directly on the shipped S8 conversation loop
([2026-07-16-interactive-review-conversations-design](./2026-07-16-interactive-review-conversations-design.md))
and its **Invariant 3** (`CommentsPosted` inline entries and `AuthorReplied` carry the *same*
`ThreadRef` — the linchpin of threadRef↔finding matching).

## 1. Motivation

The review detail shows every conversation turn in one flat "Conversation" panel, grouped only into
question→answer exchanges by event order. A reader can't tell *which* finding a given exchange is
about without cross-referencing the text. When several findings each spawn a thread, the panel reads
as "one ball of messages" (the operator's words).

GitHub, GitLab and Bitbucket all thread review replies **under the diff line they anchor to**. This
spec brings that spatial grouping to the dashboard: a finding's discussion lives with the finding.

**Goal:** on the review detail page, a finding that has a conversation shows that conversation nested
beneath it (collapsible, with a turn-count badge); threads not rooted on a finding (replies to the
summary comment, `@`-mention threads) render in a separate "General discussion" section.

## 2. Scope

**In scope**
- A read-model addition that maps each finding to its SCM thread, and exposes each conversation
  turn's `threadRef` + a thread *classification*.
- Review-detail UI: per-finding nested conversation; a separate General-discussion section; retire
  the standalone flat Conversation card.

**Out of scope**
- No change to *what* the bot answers or *whether* it answers (policy is the S8 `ConversationSaga`).
- No SCM writes, no level-3 verdict/`FindingReassessed` work.
- No live-updating nesting over WebSocket beyond what the existing review-detail refresh already does
  (a nested exchange appears on the next detail load / push, same as today's flat panel).

## 3. What exists today (grounding)

| Fact | Where |
|---|---|
| Conversation turns are `AuthorReplied` / `FollowUpGenerated` events, **each carrying `threadRef`**. | `IntegrationEvent.java` |
| The read-model `EventView(ts, at, lane, type, det)` **drops `threadRef`** — so the UI groups turns only by event order, never by thread. | `ReviewDetail.java` |
| `conversationCard` reshapes those events into question→answer exchanges (shipped 2026-07-18). | `render.tsx` |
| Findings are `FindingView(sev, loc, msg)` where `loc = "path:line"`; stored as a **Tink-encrypted** `findings_json` blob written at `ReviewGenerated` (**before** comment ids exist). | `ReviewProjection.recordOutcome` |
| `CommentsPosted.PostedInline(commentId, path, line)` + `summaryCommentId` — the source of the finding↔thread link. | `IntegrationEvent.java` |
| At `CommentsPosted`, each inline finding thread is already recorded: `threads.markOurThread(reviewId, ThreadRef(commentId))` → a `review_thread(review_id, thread_ref, is_ours, turn_count, last_comment_id)` row. The **summary** thread is *not* marked. | `ResultSaga` / `ReviewThreadView` |

**The link already exists in the events; it is simply never projected for the UI.** A finding's
`loc` is `path:line`; a `PostedInline` carries `commentId` + `path` + `line`; a reply thread's
`threadRef` is that same `commentId` (GH/BB) / `discussion_id` (GitLab, per Invariant 3). So
**finding → threadRef** is `join on (path, line)` and **turn → finding** is `group by threadRef`.

## 4. Read-model changes

Two additions, both fed by events we already emit — the aggregate is untouched (ADR-010 holds; this
is projection + UI only). We do **not** mutate the encrypted `findings_json` blob (it is written at a
different lifecycle point and is encrypted); the mapping lives in the thread table we already
populate.

### 4.1 Record `(path, line)` on finding threads, and mark the summary thread

Extend the existing `CommentsPosted` handling:

- `review_thread` gains three **nullable/defaulted** columns: `path TEXT`, `line INT`,
  `is_summary BOOLEAN DEFAULT FALSE` (Flyway migration, additive).
- `markOurThread` becomes `markFindingThread(reviewId, threadRef, path, line)` for inline entries —
  it already inserts the row; it now also sets `path`/`line`.
- The **summary** thread is marked too: `markSummaryThread(reviewId, ThreadRef(summaryCommentId))`
  sets `is_summary = TRUE` (with `path/line` NULL). This is required so summary replies classify as
  "general" rather than "orphan".

A finding thread is thus a `review_thread` row with non-null `path/line`; the summary thread is the
`is_summary` row; anything else `is_ours` (an `@`-mention we adopted) is "other".

### 4.2 Expose `threadRef` on conversation turns

`EventView` gains `threadRef` (nullable — non-conversation events leave it null). `loadEvents`
already selects the event row; the `AuthorReplied` / `FollowUpGenerated` appends must persist the
`threadRef` alongside the preview `detail` so `loadEvents` can return it.

> **Storage note.** `review_event` currently stores only `detail` (the ≤160-char preview). Add a
> nullable `thread_ref` column (additive migration) written by the two conversation appends. Older
> rows have `thread_ref = NULL` → their turns fall through to General discussion (§6), which is the
> correct graceful degradation for pre-migration data (e.g. PR #6's existing turns).

### 4.3 Classify + attach in the projection (not the UI)

`loadDetail` assembles the classification so the UI stays a dumb renderer:

- **`FindingView` gains `threadRef` + `conversationTurns: int`** — filled by joining findings
  (`path:line`) to `review_thread` (`path`,`line`) and counting matching conversation events.
- **Each conversation `EventView` gains `threadKind`**: `finding` | `summary` | `mention`, derived
  from its `threadRef` against the `review_thread` rows (non-null `path/line` ⇒ `finding`;
  `is_summary` ⇒ `summary`; else ⇒ `mention`). A turn whose `threadRef` matches no current finding
  (withdrawn / code moved) still classifies as `mention` by fallthrough, never lost.

## 5. Matching key & re-run robustness

**Match findings to threads by stable `path:line`, not by raw commentId.** The findings read model
reflects the **latest** run; the comment-idempotency store *reuses* the same `commentId` when the
same `(path, line)` finding is re-posted, so `threadRef` is stable across runs and the latest run's
finding correctly adopts its earlier thread.

Edge cases and their defined behaviour:

| Case | Behaviour |
|---|---|
| Finding at `path:line` persists across a re-run | Same `threadRef`; conversation stays nested. ✓ |
| Finding withdrawn / code moved so no current finding matches a thread | Thread falls to **General discussion** (never dropped). |
| Reply on the **summary** comment | General discussion (`summary`). |
| `@`-mention thread not on a finding | General discussion (`mention`). |
| Orphan bot answer whose question predates persistence (already handled standalone in `conversationCard`) | General discussion. |
| Two findings on the **same line** (`path:line` collision) | Rare; both would claim the thread. Tie-break by keeping the thread on the **first** finding at that loc and note the ambiguity in a code comment. Acceptable — the alternative (persisting a per-finding id at post time) is a bigger change tracked as a follow-up. |

## 6. UI design

### 6.1 Findings section — nested, collapsible conversation

Each `FindingView` with `conversationTurns > 0` renders a collapsible conversation panel beneath the
finding body, reusing the **existing** `toConversationExchanges` + exchange rendering (question→answer,
speaker labels, absolute timestamp + friendly delta — all shipped). The finding header gets a small
badge, e.g. `💬 3`, so a collapsed finding still signals it has discussion. Default collapsed to keep
the findings list scannable; expand on click.

```
CRITICAL  src/main/java/.../TestJava.java:9        💬 2  ▸
  `main` is declared `void` but attempts to return a value …
  └─ (expanded)
     ↩ @artyomsv · Jul 18, 00:05 · +23h 57m
         @code-spire-bot what do you think about this method?
        └ 🤖 Code Spire · Jul 18, 00:05 · +23h 57m
             Making `getFibonacciNumber()` public static would work, but …
```

### 6.2 General discussion section

The current standalone **Conversation** card is replaced by a **General discussion** card holding only
`summary` + `mention` (+ orphan) exchanges, rendered exactly as today. **Hidden entirely when empty**
(most reviews will have only finding-linked threads, so this section often won't render).

### 6.3 Data plumbing (frontend)

- `api.ts`: `Finding` gains `threadRef?: string` + `conversationTurns: number`; `ReviewEvent` gains
  `threadKind?: 'finding' | 'summary' | 'mention'`.
- `render.tsx`: `findingsCard` filters `r.events` to the finding's `threadRef` and renders the nested
  panel; a new `generalDiscussionCard` renders the non-finding remainder; the review-detail page swaps
  `conversationCard` for `generalDiscussionCard`.

## 7. Where the code lands

- **spire-orchestrator (read model only):**
  - Flyway migration: `review_thread.path/line` (nullable) + `is_summary` (default false),
    `review_event.thread_ref` (nullable).
  - `ReviewThreadView`: `markFindingThread(reviewId, threadRef, path, line)`,
    `markSummaryThread(reviewId, threadRef)`.
  - `ResultSaga` `CommentsPosted`: pass `path`/`line` per inline; mark the summary thread. The
    `AuthorReplied` / `FollowUpGenerated` appends persist `thread_ref`.
  - `ReviewProjection`: `FindingView` + `EventView` gain the new fields; `loadDetail` joins + classifies.
  - `ReviewDetail` DTO: the two record additions.
- **spire-ui:** `api.ts` type additions; `findingsCard` nested panel; `generalDiscussionCard`; retire
  the standalone `conversationCard` usage (keep the exchange helpers — they're reused).
- **spire-contract:** *no change* — `PostedInline` already carries `path`/`line`; events already carry
  `threadRef`.

## 8. Testing

- **Projection (unit, ephemeral Postgres):**
  - `CommentsPosted` maps each inline `(path,line)` to the finding at that `loc` and records
    `path/line` on `review_thread`.
  - A reply on a finding thread → that `FindingView.conversationTurns` increments and the turn's
    `threadKind = finding`.
  - A reply on the summary thread → `threadKind = summary`; an `@`-mention → `mention`.
  - A thread with no matching current finding → classified `summary`/`mention` (not lost).
  - Pre-migration events (`thread_ref = NULL`) → General discussion, nothing crashes.
- **UI (vitest + tsc):**
  - A finding with N turns renders a collapsible panel + `💬 N` badge.
  - General-discussion section hides when there are no non-finding threads.
  - `path:line` collision keeps the thread on the first finding (documented tie-break).

## 9. Out of scope / future

- **Per-finding stable comment id** (to kill the same-line collision cleanly) — bigger change,
  deferred.
- **Level-3 reassessment display** — when `FindingReassessed` ships, a nested thread can show the
  verdict change inline; this spec leaves room for it but doesn't build it.
- **Live nesting over WS** beyond the existing detail refresh cadence.

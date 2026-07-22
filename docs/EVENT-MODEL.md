# Event Model

> The concrete slices for Code Spire v1, in [Event Modeling](https://eventmodeling.org/) form.
> Companion to [ARCHITECTURE.md](ARCHITECTURE.md). Method references:
> [eventmodeling.org](https://eventmodeling.org/), [Fraktalio modeler](https://modeler.fraktalio.com/),
> [Event Modeling by Example](https://jeasthamdev.medium.com/event-modeling-by-example-c6a4ccb4ddf6).
>
> **Visual board:** open [diagrams/event-model.html](diagrams/event-model.html) in a browser — the
> swimlane timeline (actors + wireframes up top, the time axis, and command/event/view nodes with
> automation gears) rendering the slices below.

## Swimlanes

```
 UI / WebSocket      │ dashboard timeline, PR comments (rendered by SCM)
 ────────────────────┼──────────────────────────────────────────────────────────
 Read Models (green) │ ReviewStatusView · ReviewThreadView · RulesView · MetricsView(later)
 ────────────────────┼──────────────────────────────────────────────────────────
 Events (orange)     │ ───────────────── the append-only log ─────────────────────►
 ────────────────────┼──────────────────────────────────────────────────────────
 Commands (blue)     │ RequestReview · CancelReview · FetchDiff · GatherContext · GenerateReview · PostComments · AnswerFollowUp
 ────────────────────┼──────────────────────────────────────────────────────────
 External (grey)     │ Bitbucket webhooks · Jira · Confluence · LLM provider
```

## The four Event Modeling patterns, as used here

1. **Translation** (external → event): a webhook or external API result becomes a domain event.
2. **State Change** (command → event): a Decider validates a command and appends events.
3. **State View** (event → read model): a View folds events into a queryable projection.
4. **Automation** (event/read-model → command): a Saga reacts and issues the next command.

## Vocabulary

> **The authoritative catalog is CONTRACT §4/§5** — this list is a narrative summary and defers to it.

**Commands** (intent): `RequestReview{force}`, `CancelReview`, `FetchDiff`, `GatherContext`,
`GenerateReview`, `PostComments`, `AnswerFollowUp` (+ the Record commands, CONTRACT §5).

**Integration events**: `PullRequestEventReceived`, `PullRequestClosed`, `ManualCommandReceived`,
`AuthorReplied`, `DiffFetched`, `ContextRequested`, `ContextContributed`, `ContextAssembled`,
`ReviewGenerated`, `ReviewFailed`, `CommentsPosted`, `FollowUpGenerated`, `FollowUpPosted`.

**Domain events** (aggregate-sourced): `ReviewRequested`, `ReviewSuperseded`, `ReviewOutcomeRecorded`,
`ReviewCompleted`, `ReviewFailedTerminally`, `ReviewCancelled`, `ThreadOpened`, `FollowUpRecorded`.

**Aggregate (Decider):** `ReviewLifecycle` (stream id = `review::{workspace}/{repoSlug}#{prId}`, per CONTRACT §2).

## Slices

### S1 — Translation: webhook → event
- **Trigger:** Bitbucket `pullrequest:created` / `:updated` (external).
- **Adapter:** `ScmIngress` verifies the signature, **drops bot-authored events** (ADR-013), and
  **emits the integration event directly** — no command, no decider (translation pattern, ADR-010):
  `PullRequestEventReceived {repo, prId, action, author, diffRefs, title, description}`.
- **Note:** the HTTP handler returns `202` here and does nothing else.

### S2 — Automation: start a review
- **Saga:** on `PullRequestEventReceived` → `RequestReview {repo, prId, commit}`.
- **Decider `ReviewLifecycle`:** if this commit not already reviewed → `ReviewRequested`.
  (Idempotency: re-delivered webhook for the same commit is a no-op.)

### S3 — Automation + State Change: fetch the diff
- **Saga:** on `ReviewRequested` → `FetchDiff`.
- **Plugin (`DiffSource`):** calls SCM API, parses into canonical `FilePatch[]` (ported diff lib).
- **Event:** `DiffFetched {prId, commit, changedFiles, languages, truncated}` — **metadata only, no
  diff content** (ADR-011; content is re-fetched by commit at generate time).

### S4 — Fan-out: gather context (the pluggable heart)
- **Saga:** on `DiffFetched` → `GatherContext`; the **context-worker** computes `expectedSources` and
  emits `ContextRequested {prId, hints, expectedSources}` (CONTRACT §8 — the decider knows nothing
  about context providers).
- **Plugins (`ContextProvider`, each independent, parallel):**
  - Jira: parse ticket key from branch/description → fetch issue → `ContextContributed{source=JIRA}`.
  - Confluence: resolve links in description → fetch pages → `ContextContributed{source=CONFLUENCE}`.
  - Rules: load `.codespire` repo rules → `ContextContributed{source=RULES}`.
  - RAG (later): retrieve repo snippets → `ContextContributed{source=RAG}`.
- **Aggregator (View + completeness policy):** collects `ContextContributed` for `prId` until the
  registered-provider set responds **or** a timeout elapses → emits `ContextAssembled {prId, context}`.
  (This is the "information completeness" rule of Event Modeling, made async.)

### S5 — Automation + State Change: generate the review
- **Saga:** on `ContextAssembled` → `GenerateReview`.
- **Plugin (`LlmProvider`, config-selected, no default):** renders Qute prompt (ported templates)
  from diff + context, calls the model → `ReviewGenerated {prId, findings, summary}`.
- **Fallback:** a saga on `ReviewFailed{retryable}` → `GenerateReview{provider=next}` walks the
  configured fallback chain.

### S6 — Automation + State Change: post the comments
- **Saga:** on `ReviewGenerated` → `PostComments`.
- **Plugin (`CommentSink`):** posts inline findings + a summary comment as the **one bot account**.
- **Event:** `CommentsPosted` → Decider → `ReviewCompleted {prId, commit}`.

### S7 — State View: live status
- **View `ReviewStatusView`:** folds S2–S6 events into per-PR progress; pushed over WebSockets to
  the dashboard so the review timeline animates in real time.

### S8 — Conversational loop (Translation + Automation)
- **Trigger:** Bitbucket `pullrequest:comment_created` where the parent is a bot comment / @mention
  (bot-authored comments dropped at ingress; a `/command` body becomes `ManualCommandReceived` instead).
- **Adapter:** → `AuthorReplied {prId, threadRef, authorText}`.
- **View `ReviewThreadView`:** maintains the thread's conversation history.
- **Saga:** on `AuthorReplied` → `AnswerFollowUp` → `LlmProvider` (worker fetches thread history from
  the SCM) → `FollowUpPosted` → `CommentSink` replies **in the thread** (first-class port).

### S9 — Cancellation (Translation + Automation)
- **Trigger:** Bitbucket `pullrequest:fulfilled` / `pullrequest:rejected` (PR merged/declined), or an
  operator cancel from the dashboard.
- **Adapter:** → `PullRequestClosed {repo, prId, reason}`.
- **Saga:** on `PullRequestClosed` → `CancelReview` → Decider emits `ReviewCancelled` → CANCELLED.
  In-flight workers see the stale/cancelled run at their next stage boundary and abandon (ADR-013) —
  no LLM spend completes, no comment lands on a closed PR.
- `PullRequestClosed` also stamps the read-model's `pr_state` (MERGED/CLOSED, by `reason`) — this
  is independent of the cancel-only-when-in-flight rule above, which is unchanged (a review already
  COMPLETED just gets its `pr_state` updated, no `ReviewCancelled`). Symmetrically, a
  `PullRequestEventReceived` (S1) stamps `pr_state=OPEN` on the registered review.
- Manual re-review: `ManualCommandReceived{review}` → (saga) → `RequestReview{force=true}` (FR-12).

### S10 — (later) Memory & analytics
- **View `MemoryView`:** projects `ReviewCompleted` + `AuthorReplied` (accepted/rejected findings)
  into learned per-repo preferences.
- **Plugin `MemoryContextProvider`:** on `ContextRequested`, contributes learned preferences as
  another `ContextContributed` — closing the loop with zero change to S4.
- **View `MetricsView`:** per-author / per-repo stats from the same log (author is on every event
  since S1 — the "collect per-user later" hook is free).

### S11 — Reconciliation review
- **Trigger:** `ReviewRequested` for a PR that already has a posted prior run
  (`review_status.last_posted_commit` set) — the same S3–S5 fetch/context path runs first.
- **Saga:** on `ContextAssembled` → `GenerateReview {..., priorRun}` — the orchestrator packs
  `PriorRun {headCommit, summaryCommentId, findings}` from `review_status.posted_findings_json`
  (snapshotted at the prior `CommentsPosted` behind a commit-match guard, ADR-019).
- **Worker:** two claim-guarded LLM calls. A **reconcile call** (`LLM:reconcile` claim; prior findings
  + best-effort thread transcripts + the incremental diff via `DiffSource.fetchCompareDiff`, full-diff
  fallback on a force-push/compare failure) produces one `FindingVerdict{RESOLVED|STILL_OPEN|
  ACKNOWLEDGED|SUPERSEDED|UNCHANGED}` per prior finding — `UNCHANGED` means the follow-up commit does
  not touch or affect that finding at all; a deterministic backstop also downgrades any `STILL_OPEN`
  verdict to `UNCHANGED` when its finding's own anchor line doesn't fall inside a changed hunk of the
  incremental diff — hunk-level, not file-level, so fixing one part of a file no longer keeps every
  other prior finding in that file "open" (no downgrade when the incremental diff is unavailable).
  Then the standard **review call** (`LLM` claim) runs with an "already reported" exclusion section
  built from the same prior findings, followed by a deterministic filter that drops any new finding
  whose anchor collides with a `STILL_OPEN` or `UNCHANGED` verdict.
- **Event:** `ReviewGenerated {..., verdicts, reconcileUsage}`.
- **Saga:** on `ReviewGenerated` → `PostComments {..., verdicts, priorSummaryRef}`.
- **Plugin (`CommentSink`):** acts per verdict — closing verdicts resolve-first (`resolveThread`;
  GitHub GraphQL, GitLab discussion PUT, Bitbucket Cloud `UNSUPPORTED` → reply-only); a thread a human
  already resolved (`ALREADY_RESOLVED`) skips the reply; `STILL_OPEN` always replies without resolving;
  `UNCHANGED` findings get no thread interaction at all — no claim, no reply, no resolve; genuinely new
  findings post fresh inline comments; the summary is rewritten in place (`updateComment`, fresh-post
  fallback). Every reply/resolve holds its own `comment_idempotency` claim (`reply:<threadRef>`,
  `resolve:<threadRef>`) so redelivery repeats zero external calls.
- **Event:** `CommentsPosted {..., threadOutcomes}` → Decider → `ReviewCompleted`.
- **View:** `ReviewThreadView` marks resolved threads; `review_status` re-snapshots
  `posted_findings_json` (commit-guarded) for the *next* follow-up and carries a transient
  `answering` flag (set when a follow-up reply is dispatched, cleared on post or terminal failure)
  that the summary/detail API surface as a "responding" indicator while the bot works; the detail
  API/UI render a reconciliation card (closed/still-open counts, verdict rows, resolved-thread check
  icons; `UNCHANGED` renders as a muted dashed-circle badge, counted as open, with no thread
  affordance since none exists).
- **Note:** a first review (no prior posted run) takes the untouched S3–S6 path — `priorRun` is null
  and the exclusion/verdict machinery never engages.

## Given / When / Then (a couple of examples)

```
GIVEN  PullRequestEventReceived{prId=42, commit=abc}
WHEN   RequestReview{prId=42, commit=abc}
THEN   ReviewRequested{prId=42, commit=abc}

GIVEN  ReviewRequested{prId=42, commit=abc}     // same commit already reviewed
WHEN   RequestReview{prId=42, commit=abc}
THEN   (no event — idempotent)

GIVEN  DiffFetched{prId=42} ; ContextContributed{JIRA} ; ContextContributed{RULES}
WHEN   all registered providers responded (or timeout)
THEN   ContextAssembled{prId=42, context={jira, rules}}
```

## Why this shape serves the goals

- **No sync processing:** every slice is `event → saga → command → event`. Nothing blocks.
- **Plugin-first:** S4/S5/S6 attach behavior by subscribing to events; new providers/capabilities
  are new subscribers (S9 adds memory + RAG without editing S1–S8).
- **Replayable & auditable:** the whole review history is the event log — rebuild any view,
  answer "why did the bot say X", and derive analytics after the fact.
- **One bot, all PRs, author-agnostic:** S1 carries `author` as data; nothing gates on it.

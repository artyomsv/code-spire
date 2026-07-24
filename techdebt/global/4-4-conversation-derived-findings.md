# Register a finding when a conversation surfaces one (not just answer)

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Large |
| Location | `spire-review-worker/.../pipeline/FollowUpWorker.java`; `spire-llm/.../FollowUpPrompt.java` + `PromptCatalog` (FOLLOWUP contract); `spire-contract/.../event/IntegrationEvent.java` + `command/*` (a new "finding raised in conversation" signal); `spire-orchestrator/.../pipeline/ResultSaga.java` + the `ReviewLifecycle` aggregate (finding set, blocker/open counts); `spire-orchestrator/.../readmodel/ReviewProjection.java` (findings read model) |
| Found during | Live SCM testing — operator opened a NEW inline thread on a random line, @-mentioned the bot; the bot answered and acknowledged a real issue, but nothing was recorded as a tracked finding |
| Date | 2026-07-25 |

## Issue

The conversational-reply feature (scope A/B) is **answer-only**: `FollowUpWorker` fetches the thread,
calls the LLM, and posts the reply verbatim (`replyInThread`). Findings are produced **exclusively**
by reviewing the diff (`ReviewGenerated` → `CommentsPosted`); a follow-up answer never becomes a
tracked finding, so it does not count toward the review's finding/blocker totals, does not appear in
the Findings card, and is invisible to re-review reconciliation (ADR-019).

Observed behaviour (GitHub, `spire-test#11`): a human opened a new thread on an unrelated line,
`@code-spire-bot do you think variable name is ok?`. The bot answered and agreed there was an issue —
but the review's finding count did not change. This is **by design today**, not a defect: the
follow-up contract is "plain reply to post in the thread," and findings ride only in the review
events.

The desired capability: when a conversation genuinely surfaces a defect (the bot, or the human via
the bot, identifies a fix-worthy issue on a specific line), register it as a first-class finding so
it flows through status, blocker counts, the Findings card, and reconciliation like a review finding.

## Risks / why it's worth doing

- **Lost signal.** A real, agreed-upon defect raised in conversation leaves no durable, trackable
  record — it lives only in a comment thread and never gates the PR or shows in the finding totals.
- **Inconsistent mental model.** Operators reasonably expect "the bot acknowledged a bug" to mean
  "the bot filed a finding." The gap is surprising (this debt was found exactly that way).

## Design questions to resolve first (why it's Large, not a quick fix)

- **Trigger.** Who decides a turn is a finding — a structured LLM signal in the follow-up response, an
  explicit human command (`/finding`), or a heuristic? The current FOLLOWUP contract is deliberately
  free-text (see `4-*` prompt debt) — adding a structured side-channel must not regress the reply.
- **Anchor.** A conversation finding needs a `(path, line, commit)` anchor. Inline threads have one
  (the thread's anchor); summary/PR-level threads do not — those may not be eligible.
- **Aggregate write.** Domain events are appended ONLY by the aggregate (ADR-010). This needs a new
  command/event path so the finding lands in `ReviewLifecycle`'s finding set with a single writer,
  keyed by `reviewId`, idempotent on redelivery.
- **Dedup.** A conversation finding must not double-count an existing review finding on the same
  `(path, line)`; reconciliation must treat it consistently on the next re-review.
- **Counts + read model.** Blocker/open-finding counts, the Findings card, and cost/turn tracking all
  need to include conversation-derived findings without corrupting the "reviewed this commit" snapshot.

## Not doing now

Answer-only conversation is the intended behaviour for the current scope. This entry captures the
enhancement so a future design pass (its own spec → plan) can add finding registration deliberately,
rather than bolting it onto the reply path.

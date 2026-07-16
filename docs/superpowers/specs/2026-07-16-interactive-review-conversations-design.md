# Interactive Review Conversations (Slice S8, extended)

**Status:** Design approved, pending implementation plan
**Date:** 2026-07-16
**Scope:** Un-park EVENT-MODEL slice **S8 — Conversational loop** and extend it with a
configurable interaction level whose top rung lets a conversation change a recorded review outcome.

## 1. Motivation

Today the bot posts findings and ignores every reply. We want it to **discuss, explain, and be
convinced** in the PR review threads — so a reviewer can push back on a finding and either get a
justification or have the bot concede. The two hard problems the operator raised are already solved by
the existing architecture and are restated as invariants here:

- **Conversation context** is never stored in Code Spire. It is re-fetched from the SCM on demand
  (ADR-011's "don't persist, re-fetch by reference", applied to threads).
- **Code anchoring** is free: a reply rides on a `ThreadRef` that the SCM already binds to a specific
  `(path, line, commit)`; replies inherit the parent's anchor on every provider (SCM-MAPPING §6).

## 2. Interaction ladder + configuration

A single dial, set as a **global default with per-provider override** (a provider's value is nullable →
inherits the global default):

| Level | Behavior |
|---|---|
| **1 · Report-only** | Posts findings, ignores replies. Today's behavior. |
| **2 · Explain** | Answers questions and defends findings in-thread. **Verdict is immutable.** |
| **3 · Interactive** | Can be convinced → withdraws/downgrades a finding, (optionally) resolves the SCM thread, and updates the recorded outcome. |

Each level is a superset of the one below. Storage mirrors the existing `review-mode` setting: a global
`conversation_level` row plus a nullable `conversation_level` column on the provider registry. Surfaced in
Settings (a global toggle + a per-provider override dropdown).

## 3. Scope of participation

The bot engages in a thread when **either** holds (scope "A+B"):

- **A** — the thread is rooted on one of *its own* review comments (an inline finding or the summary), or
- **B** — a human explicitly `@`-mentions the bot in a new thread.

Triggers inside those threads are **both**: plain natural-language replies **and** explicit inline
`/commands` (e.g. `/explain`, `/why`, `/resolve`). Anything outside A+B is ignored.

## 4. Event flow — the orchestrator holds all policy

The internet-facing gateway stays **stateless and dumb**: each `ScmIngress` emits `AuthorReplied` for any
human comment carrying a thread/parent (or a parsed inline `/command`), and nothing else. **All policy runs
in the orchestrator**, the only component that holds the review state, the bot identity, and the config:

```
human reply / @-mention
  → ScmIngress.translate → AuthorReplied{repo, prId, reviewId, threadRef, commentId, text, author}
  → orchestrator ConversationSaga applies, in order:
       1. bot-self?            → drop (ADR-013)
       2. author on the provider allowlist? → else ignore
       3. thread ∈ our findings (aggregate thread map) OR text @-mentions the bot? → else ignore  (scope A+B)
       4. effective level (provider override ?? global default): 1 → drop; 2/3 → proceed
       5. thread turn count < cap? → else post "deferring to the team" and stop
  → AnswerFollowUp{reviewId, repo, prId, threadRef, question}
  → worker: fetchThread(threadRef) + fetchDiff(commit) → follow-up LlmProvider call
  → FollowUpGenerated{reviewId, threadRef, answerText, verdict}
  → worker CommentSink.replyInThread(...) → FollowUpPosted{reviewId, threadRef, commentId}
  → (level 3, verdict = downgrade|withdraw) orchestrator appends FindingReassessed (see §6)
```

Levels 1–2 are **exactly the parked S8 slice** (`AuthorReplied → AnswerFollowUp → FollowUpGenerated →
FollowUpPosted → replyInThread`). Level 3 is the new extension.

## 5. Context & anchoring

Nothing conversational is persisted. At answer time the worker re-fetches:

- the **thread** from the SCM via `fetchThread` (§7) → full message history plus the anchored
  `(path, line, commit)` and surrounding diff hunk;
- the **diff by commit** via the existing `DiffSource` for wider file context.

Every answer is therefore grounded in (a) the exact code the thread is anchored to and (b) the live
conversation — both pulled fresh, nothing to keep in sync. `ReviewThreadView` holds only lightweight state:
`threadRef → {status, lastCommentId, turnCount}`.

## 6. Level-3 "concede" mechanics

The follow-up LLM call returns the answer **plus a structured verdict**, produced under the same
injection-fenced discipline as the review prompt:

```
verdict = stand | downgrade{newSeverity} | withdraw   (+ reason)
```

- `FollowUpGenerated` carries the verdict.
- On `downgrade`/`withdraw`, the orchestrator maps **threadRef → finding**. It already has this mapping:
  `CommentsPosted` records each inline finding's `ThreadRef` (see §7, invariant 3) + `path/line`. It then
  appends a new domain event **`FindingReassessed{threadRef, newSeverity|withdrawn, reason}`**.
- Effects:
  - the outcome read-model recomputes `findings` / `blockerCount` (the dashboard reflects reality);
  - `CommentSink.resolveThread(...)` collapses the thread **if the provider supports it** (§7, capability);
  - the concession reply is posted via `replyInThread`.
- On `stand`: reasoning is posted, the thread stays open, **no state change**.
- **Deferred:** live-editing the summary comment's tally on reassessment (edit-noise). The dashboard is the
  source of truth. `FindingReassessed` is also the event a future memory slice (S9/S10) consumes.

Single-writer discipline (ADR-010) holds: `FindingReassessed` is appended by the review aggregate, not by
the worker; the saga translates the worker's verdict into the command.

## 7. Ports — held to the opaque-`ThreadRef` discipline

The core conversational ports are already provider-neutral and verified across GitHub, GitLab, Bitbucket
Cloud and DC (SCM-MAPPING §6). The two operations this design *adds* must follow the same discipline so
GitLab and Bitbucket slot in as pure adapters with no redesign.

| Port method | Status | Neutral mapping |
|---|---|---|
| `replyInThread(repo, prId, ThreadRef, bodyMd) → CommentRef` | **exists** | BB/DC `parent:{id}`, GitHub `.../comments/{id}/replies`, GitLab `.../discussions/{discussion_id}/notes`. Replies inherit the anchor — never resend it. |
| `fetchThread(repo, prId, ThreadRef) → ThreadTranscript` | **new** | GitHub: review-comment reply chain + `diff_hunk`; GitLab: `GET discussions/{id}`; BB: comment tree by `parent`. `ThreadTranscript = { messages: [{author, text, createdAt}], anchor: {path, line, commit} }`. |
| `resolveThread(repo, prId, ThreadRef)` | **new, capability-gated** | GitLab `PUT .../discussions/{id}?resolved=true`, BB `.../comments/{id}/resolve` — simple REST. GitHub is **GraphQL-only** (`resolveReviewThread`, needs the thread **node id**, not the REST comment id) — the GitHub adapter resolves that internally. |

**Invariant 1 — `ThreadRef` stays opaque.** It is a comment id on GitHub/Bitbucket and a `discussion_id`
on GitLab. No caller ever inspects its shape.

**Invariant 2 — resolution is optional.** `resolveThread` is a `Capability` a sink advertises (the existing
`Capability`-bean pattern). If a provider can't/won't resolve, level 3 **degrades gracefully**: Code Spire
still appends `FindingReassessed` and posts the concession reply; only the SCM-side thread-collapse is
skipped. Level 3's neutral core never depends on the single hardest op.

**Invariant 3 — thread-handle consistency.** `CommentsPosted` inline entries record the **`ThreadRef`** (the
`discussion_id` on GitLab, the root comment id on GH/BB) — **not** a bare note/comment id — and
`AuthorReplied` carries the **same** `ThreadRef`. This is the linchpin of threadRef↔finding matching; the
GitLab `discussion_id ≠ comment id` distinction is the specific trap this invariant closes.

## 8. Guardrails

- **Turn cap per thread:** configurable, default **~3–4** bot replies. On reaching it the bot posts a brief
  "deferring to the team" note and goes quiet. `ReviewThreadView.turnCount` enforces it.
- **Who may trigger:** the existing per-provider **author allowlist**, plus ADR-013 bot-drop (the bot never
  answers itself — the same guard verified against the GitHub summary-comment loop on 2026-07-16).
- **Cost:** one bounded LLM call per follow-up (the existing `maxTokens` cap), accrued to the review's
  `costMillicents`. The turn cap is the hard ceiling.
- **Level 1 = zero cost/behavior change:** the orchestrator drops the ingress-emitted `AuthorReplied`
  outright, so report-only providers are unaffected.

## 9. Where the code lands

- **spire-contract:** `FindingReassessed` domain event; a `verdict` field on `AnswerFollowUp` /
  `FollowUpGenerated`; `fetchThread` + capability-gated `resolveThread` on the SCM ports; a
  `ThreadTranscript` value type; `conversationLevel` on the provider config. The `ReviewLifecycle` decider
  gains thread/finding state and the reassess transition. **New ADR** (a conversation mutating a recorded
  outcome).
- **SCM ingresses:** GitHub & GitLab emit `AuthorReplied` for non-command human comments and parse inline
  `/commands` (Bitbucket already emits `AuthorReplied`). GitHub additionally must handle the
  `pull_request_review_comment` webhook (and the operator subscribes to it).
- **CommentSink ×3:** implement `replyInThread` (partly present), `fetchThread`, and (capability-gated)
  `resolveThread`.
- **spire-orchestrator:** `ConversationSaga` (policy → `AnswerFollowUp`; `FollowUpGenerated` → post /
  reassess); the aggregate's thread↔finding map + `FindingReassessed`; `ReviewThreadView`; the global +
  per-provider level config and its REST surface.
- **spire-review-worker:** the follow-up handler (fetch thread + diff → follow-up LLM → post / resolve).
- **spire-llm:** a follow-up prompt (conversation + anchored code → answer + structured verdict),
  injection-fenced like the review prompt.
- **spire-ui:** Settings — a global conversation-level toggle + a per-provider override dropdown.

## 10. Testing

- **Ingress:** `AuthorReplied` emission for natural-language and inline `/command` replies per SCM; bot-self
  produces nothing.
- **Orchestrator (GWT decider):** the full policy matrix — own-thread / @-mention / allowlist / level /
  turn-cap; `FindingReassessed` recomputes the outcome; interplay with the ADR-013 stale-run guard.
- **Worker:** the follow-up handler against a mock SCM thread + mock LLM → `FollowUpPosted`; a `withdraw`
  verdict calls `resolveThread` when the capability is present and skips it (without failing) when absent.
- **Per-SCM E2E (Testcontainers):** reply → answer posted in the thread; convince → finding withdrawn +
  thread resolved (where supported) + `blockerCount` decremented.
- **Guardrails:** the cap stops at N; level 1 is a no-op.

## 11. Dependencies / out of scope

- **Bot identity (parked, tracked separately).** S8 assumes a real bot identity exists per provider (a
  GitHub App, a GitLab group/project access-token bot user, a Bitbucket workspace access token); without it
  the bot converses under a human account. This is a cross-cutting prerequisite with its own design.
- **No auth / multi-user (P2).** "Who may trigger" uses the provider allowlist, not per-human preferences.
- **Memory / learning slice (S9/S10).** Consumes `FindingReassessed` later; out of scope here.
- **Summary-comment live-editing on reassessment.** Deferred; the dashboard is the source of truth.

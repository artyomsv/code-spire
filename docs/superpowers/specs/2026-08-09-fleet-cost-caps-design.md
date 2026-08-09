# Spend caps and the refused-review lifecycle

**Status:** draft, revised after review. Not approved, not implemented.
**Builds on:** ADR-023 (the `llm_charge` ledger) and ADR-024 (archival).
**Scope:** this is **Spec A** of two. Spec B — the per-repo admission rate limit — is scoped out at
the end and depends on nothing here.

## Problem

The ROADMAP records a deferred, known operator-facing gap: no per-repo rate limit, no daily spend
cap, no hard giant-PR skip. Precisely stated, the codebase has **no admission or spend limiting** —
there are outbound protections (the worker's Retry-After posting throttle, the per-host circuit
breakers) but nothing bounds how much a deployment can spend or how large a pull request can be
before reviewing it stops being worthwhile.

The bounds that do exist are narrower than they sound:

- `maxTokens` per LLM call, per provider — bounds one call's output, nothing aggregate
- `SPIRE_REVIEW_MAX_ATTEMPTS` — the ADR-016 auto-retry budget, not a spend budget
- the conversation turn cap — per **thread**, default 4, and **an @-mention removes it entirely**
- the diff token clip — truncates a large diff rather than refusing it

## The two findings that reshaped this design

### The conversation path is unbounded, and the code already says so

`ConversationSaga.planFollowUp` emits a paid `AnswerFollowUp` guarded only by `isSpendable`
(`ConversationSaga.java:114-118`). Threads are free to open, the turn cap is per-thread, and an
@-mention removes the cap — `CallRefs.java:76-77` states it outright: *"the default turn cap is 4, and
an @-mention removes the cap entirely, so the loss was unbounded."*

The comment immediately above that guard records the last time this path was assumed safe:

> *"...an author replying in a live thread still made the bot spend, up to the turn cap or unbounded
> with an @-mention. ADR-023 argued this path was safe by construction because the registry guard
> makes an unpriceable provider impossible; V30 falsifies that."*

The first draft of this spec repeated that assumption. **Any spend cap that does not cover the
conversation path leaves the abuse case it exists for half-open** — the `/review` path gated while
comment-driven spend runs free. This, not the deferral of per-actor limits, is what would have broken
the multi-tenant story.

### A refused review is stuck, and now also unarchivable

The pre-spend refusal this design models on writes a note and nothing else
(`ResultSaga.skipUnspendable`, `:399-403`). No status change, no lifecycle record. The consequence is
documented in `AttentionQueries`' own javadoc (`:97-103`): a refused review *"sits in REVIEWING until
REVIEW_STUCK eventually fires — a symptom one level away from the cause, minutes later"*, and
`REVIEW_STUCK`'s message blames *"a webhook delivery path or a worker"* — which is a lie when the
truth is a deliberate policy refusal.

ADR-024 made it worse: `archiveRow` refuses `lower(status) <> 'reviewing'`
(`ReviewProjection.java:761-766`), so a cap-refused review **cannot even be archived**.

That is tolerable for `MODEL_NOT_PRICEABLE`, a one-time configuration error an operator fixes once.
It is fatal for a cap that refuses **routinely and by design**.

## Decisions

### 1. A refused review reaches a terminal state, `refused`

Every refusal drives the aggregate terminal through the existing shape of `ResultSaga.onReviewFailed`
(`:342-356`): `updateStatus` plus `RecordFailure(retryable=false)`.

**A new terminal status, not a reuse of `failed`.** The archive guard, the attention queries and the
reviews-list filters all key on status, so conflating a deliberate policy refusal with an
infrastructure failure would file it in the same bucket as a genuine outage. This project already
split `pr_state` out of `status` for exactly that reason — a merged PR and a passed review are
different facts one badge could not carry. A refusal and a failure are the same kind of distinction.

`refused` is terminal and archivable, so the operator can clear it.

### 2. Two spend gates, plus the conversation gate

| Gate | Location | Checks |
|---|---|---|
| **Post-diff** | `ResultSaga`, on `DiffFetched` | diff size |
| **Pre-spend** | `ResultSaga`, before `GenerateReview` | spend / usage cap |
| **Conversation** | `ConversationSaga.planFollowUp` | spend / usage cap |

Each sits where its inputs already are. The diff check must be at `DiffFetched`, because
`changedFiles`, `sizeBytes` and `truncated` exist on that event (`IntegrationEvent.java:153-155`) and
**nowhere afterwards** — `ContextAssembled` does not carry them (`:170-171`) and `review_status` does
not persist them. Checking size at the pre-spend point would need either new columns or new fields on
a Kafka wire type, and would run `GatherContext` — per-issue API calls, a 20-second fan-out, an
encrypted blob write — before discarding the result.

The conversation gate goes beside the `isSpendable` check whose own comment narrates the last time
this path was left out.

### 3. One refusal vocabulary, in a new type

All three gates describe a refusal through one type: an enum reason plus `detail()` (a timeline line)
and `note()` (the review's note), modelled on `DefaultLlm`.

Deliberately **not** an extension of `DefaultLlm.Refusal`, which answers "can this LLM be used" and
carries `NO_DEFAULT_PROVIDER` and `MODEL_NOT_PRICEABLE`. A cap refusal is a budget policy decision;
folding it in would drag budget logic into credential resolution — two concerns that change for
different reasons.

### 4. Both axes, with their units named

Every cap is *"money **or** calls, whichever trips first"*, because ADR-023 established that a
money-denominated cap is **inert by design on an `UNMETERED` deployment** where every charge is an
asserted zero.

The axes must be named precisely, because "calls" is ambiguous:

- **Money**: `SUM(cost_millicents)` over the window.
- **Calls**: `COUNT(DISTINCT call_ref)` — one per *LLM call*, so a review, its reconcile and each
  follow-up each count separately (`CallRefs.java:37`). This is deliberate: the call axis is what
  bounds the conversation path, since a thread can generate many calls against one review.

The two combine to close the ADR-023 hole exactly: an `UNKNOWN`-priced row has a NULL cost that `SUM`
skips, and the call axis counts it anyway.

### 5. The spend read must NOT filter `archived_at`

Ten ledger reads added by ADR-024 filter `archived_at IS NULL`, and they are sitting there to be
copied. **The cap's read must not.** Archiving a review must not refund its budget — otherwise
archiving becomes a way to reset the cap, and an operator who archives to tidy up silently buys
themselves more spend.

This is the single most likely implementation error in the whole design, because the surrounding code
establishes the opposite pattern.

### 6. The spend cap is a soft cap, and the spec says so

Charges are recorded after a call completes, so N reviews already in flight all pass the pre-spend
check before any of their charges land. Overshoot is bounded by *in-flight reviews × per-review cost*
and is not eliminable without a reservation protocol, which is disproportionate here.

Stating it is the point. A cap documented as exact and observed to overshoot destroys trust in the
number; a cap documented as soft with a bounded overshoot is simply true.

### 7. Refusals are loud

A tripped cap writes a timeline entry, sets the review's note, moves the review to `refused`, and
raises an **attention row**. Nothing is posted to the pull request — the `/review` refusal path
already established that replying confirms to a prober that the command is wired and costs an API
call per probe (`IntegrationSaga.java:250-256`).

Silence is the failure this project has paid for twice: the turn cap recorded a dashboard note and
posted nothing, so the bot simply stopped replying; and `/review` on a deleted review refused
correctly to a surface with no page to appear on.

The attention row needs no acknowledgement watermark, unlike ADR-023's two cost rows, because a cap
describes **current state** — the row clears when the window rolls or the operator raises the limit.

### 8. Rolling window, not fixed bucket

A fixed bucket's only advantage is predictability, and it is a false one. With admission timestamps
available, the exact instant capacity returns under a rolling window is computable — the oldest
in-window entry plus the window length — so the attention row can say *"capacity returns at 14:23"*,
which is strictly more precise than *"resets at 14:00"*. The fixed bucket keeps its boundary-gaming
weakness (twice the limit across two adjacent minutes) and buys nothing.

## Configuration

Limits live in the existing `app_setting` store, surfaced under **Settings → General** beside the
review and conversation settings.

Every limit is **optional, and unset means unlimited** — the default. A deployment that sets nothing
behaves exactly as it does today. Shipping non-null defaults would silently change a running
deployment's behaviour on upgrade, which is the mistake V30 made by leaving legacy models rateless
and is still the most operator-visible consequence of ADR-023.

## Testing

1. **An unset cap never refuses.** If the default is not a no-op, every existing deployment changes
   behaviour on upgrade.
2. **Each gate refuses for its own reason and says so** — asserted on the message text, not on a
   boolean. Three refusals must not be distinguishable only by which line threw.
3. **A refused review is terminal and archivable.** Refuse one, then archive it. Without this the
   operator is left with a row that is stuck in `reviewing` and that `archiveRow` rejects — the exact
   state ADR-024 created and this design has to avoid re-creating.
4. **A refused review does not raise `REVIEW_STUCK`.** It is terminal, so the stuck query must not
   match it — otherwise every routine refusal produces a row blaming a webhook or a worker.
5. **The call-count cap fires on an `UNMETERED` deployment where the money cap cannot.** The test for
   the entire dual-axis decision, and the one that fails if someone later simplifies the cap to a
   single money figure.
6. **Archiving a review does not restore budget.** Charge against the cap, archive the review, assert
   the cap still counts those charges. This is the test for §5, and it fails the moment someone copies
   the `archived_at IS NULL` filter from any of the ten reads beside it.
7. **A follow-up is refused when the cap is reached, including with an @-mention.** The @-mention path
   bypasses the turn cap by design, so it must not also bypass the spend cap — that combination is the
   unbounded case.
8. **A cap refusal raises an attention row, and the row clears when the window rolls.**

## Spec B — the per-repo admission rate limit (scoped out)

Split at the state seam: everything above needs **no new storage**, reading the ledger and the events
it already has. The rate limit needs a counter table, which is the only new state in the whole
feature and the only part that needs pruning.

What Spec B must carry, recorded here so it is not lost:

- **Two gate sites, not one.** `IntegrationSaga` does *not* see every inbound request: the REST re-run
  endpoint reaches `ReviewRerunService.rerun` directly (`ReviewsResource.java:145-151`). But webhook
  and Register PR both arrive as `PullRequestEventReceived`, and `/review` funnels through the same
  `rerun`, so `onPullRequestEvent` plus `rerun` covers everything — unlike ADR-024's six paths, a
  funnel exists.
- **The gate must precede `lifecycle.handle(RequestReview)`** (`IntegrationSaga.java:353`), or a
  refused admission starts a run anyway and reproduces the stuck-review problem.
- **The counter keys on `(provider_type, workspace, slug)`.**
  `techdebt/spire-orchestrator/3-3-…` predicts this feature inheriting the collision where one
  workspace name registered on two SCMs shares a budget.
- **A dedicated table is justified — but not by "the only pre-hoc source".** `event_log`'s
  `ReviewRequested` rows *are* appended synchronously at admission and `ReviewRuns` already counts
  them. The real justifications are the provider key (not derivable from `stream_id`), the index shape
  a windowed count needs, and prunability.
- **Pruning**: delete rows older than the longest configured window, on write.
- **The retry scheduler must not re-count** — it re-dispatches an admission already counted.
- **Observe-mode registrations must not count** — gate on the `started` path, after the observe branch.
- **A rate-refused first-contact PR has no `review_status` row at all**, so "set the review's note"
  is a silent no-op there. Spec B must decide between creating a row in `refused` and recording only
  to the timeline and the attention panel.

## Deliberately not built

- **Per-actor limits.** Per-repo bounds total volume regardless of who; per-actor adds isolation that
  matters when many legitimate actors share a repo. Real for multi-tenant, speculative at three
  repositories — and the counter key leaves room for the column.
- **Per-repo spend caps.** They need `provider_type` on `llm_charge`, which is `techdebt/3-3`'s own
  recommendation and belongs with that entry.
- **Queuing or deferral.** A refused review is refused, not held. A backlog would itself need bounding.
- **Cost estimation before the call.** Refusing on a *predicted* price needs a token estimate the
  system cannot validate — the fabricated-number problem ADR-023 exists to prevent. These caps refuse
  on measured history and measured input only.

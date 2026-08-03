# The circuit breaker covers SCM reads and LLM calls, not comment posting

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-review-worker/.../adapters/ProviderCircuits.java`, `spire-review-worker/.../pipeline/ReviewWorker.java` (posting path) |
| Found during | Wave 3 — successor to `4-3-circuit-breaker-does-not-cover-the-llm-or-posting-paths` |
| Date | 2026-08-03 |

## Issue

Two of the three call paths are now guarded:

- **SCM reads** — `RetryingDiffSource` wraps every worker `DiffSource`, keyed by `DiffSource.apiHost()`.
- **LLM calls** — `CircuitBreakingLlmProvider` wraps the provider built in `WorkerLlmProvider.clientFor`,
  so both the review and follow-up paths are covered by one wrap. Health is
  `LlmFailures.isProviderUnwell`: LangChain4j's `RetriableException` hierarchy (rate limit, 5xx,
  timeout) plus I/O and timeouts count as illness; a rejected key or an invalid request is an
  **answer** and never opens the circuit.

**Comment posting is still unguarded**, and deliberately so. `CommentSink` writes sit outside
`RetryingDiffSource` because they carry their own `comment_idempotency` claim and a
Retry-After-aware backoff budgeted against Kafka's `max.poll.interval.ms`.

## Risks

Low. Posting is the last phase, so an outage there fails a review that has already paid for its diff
and its LLM call — the expensive work is done and the ADR-016 budget re-drives it. The waste a
breaker would save is the smallest of the three paths.

## Why this is not a drop-in

Short-circuiting a write mid-run interacts with the idempotency claim: the claim is taken *before*
the post (insert-before-post, so a crash cannot double-post), so refusing the call after the claim
exists leaves a row whose comment was never posted. That has to be reclaimable, which is the same
recovery question the original design answered for crashes — it is answerable, but it is a design
question rather than a wrap.

Decide that first. Until then the honest position is that the two paths where an outage actually
burns money and time are covered, and the third is bounded by the attempt budget.

## Suggested Solutions

1. **Decide the claim semantics first**, then wrap. A refused post must leave the claim in the same
   reclaimable state a crashed post does, or a recovered provider gets a review with a silently
   missing comment.
2. **Surface an open circuit in the attention panel.** It is exactly the "true right now" condition
   that panel exists for, and an outage the operator cannot see gets diagnosed as "reviews stopped
   working". Deferred because the breaker's state lives in the worker, and the panel today merges
   two feeds (orchestrator + gateway) — a third would need its own socket and UI merge. This is
   worth more than wrapping the posting path, and is independent of it.
3. Leave posting as is. Defensible on the risk above.

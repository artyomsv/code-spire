# The circuit breaker covers SCM reads only, not the LLM or comment-posting calls

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-review-worker/.../adapters/ProviderCircuits.java`, `spire-llm/.../LangChain4jLlmProvider.java`, `spire-review-worker/.../pipeline/ReviewWorker.java` (posting path) |
| Found during | Wave 3 — successor to `3-3-no-circuit-breaker-on-a-degraded-provider` |
| Date | 2026-08-02 |

## Issue

The predecessor entry is closed: `ProviderCircuits` opens per API host after five consecutive
unhealthy calls, refuses traffic for a 30s cooldown, admits exactly one probe, and classifies an
open circuit as retryable so ADR-016 re-drives the review. It is wired into every worker
`DiffSource` through `RetryingDiffSource`, which is where every SCM **read** goes.

Two call paths are still unguarded:

- **LLM calls.** The expensive one. A degraded LLM provider is billed for on every attempt, and
  `spire-llm` has no breaker — only the LangChain4j client's own behaviour and the per-review
  idempotency claim that stops a redelivery paying twice.
- **Comment posting.** `CommentSink` writes are deliberately outside `RetryingDiffSource` (they
  carry their own `comment_idempotency` claim and a Retry-After-aware backoff budgeted against
  Kafka's `max.poll.interval.ms`). A breaker there is not a drop-in: short-circuiting a write mid-run
  interacts with that claim, and the interaction needs its own thought.

## Risks

Low, and lower than the predecessor's. The SCM read path is where a review spends most of its calls
and is the first thing to fail in an outage, so the breaker already absorbs the bulk of the waste.
An LLM outage still costs a full retry ladder per review, but LLM calls are far fewer per review
than SCM calls and the attempt budget still bounds them.

## Suggested Solutions

1. **LLM first** — it is the one with money attached. `ProviderCircuits` is deliberately generic
   (`guard(host, call, unhealthy)`) and takes the health predicate from its caller, so `spire-llm`
   can reuse it by supplying its own definition of "the provider is unwell" rather than the SCM's.
   Note that `spire-llm` wraps LangChain4j's untyped runtime exceptions, so that predicate is the
   real work.
2. **Posting last, if at all.** Decide first what a short-circuited post means for a half-posted run
   — the claim is already taken, so it must be reclaimable, which is the same recovery question the
   original idempotency design answered for crashes.
3. Consider surfacing an open circuit in the attention panel. It is exactly the "true right now"
   condition that panel exists for, and an outage the operator cannot see gets diagnosed as "reviews
   stopped working". Deferred here because the breaker's state lives in the worker, and the panel
   today merges two feeds (orchestrator + gateway) — a third would need its own socket and UI merge.

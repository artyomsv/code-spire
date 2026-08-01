# No circuit breaker when a provider is degraded rather than blipping

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-review-worker/.../adapters/RetryingDiffSource.java`, `WorkerScmClients`, `spire-llm/.../LangChain4jLlmProvider.java` |
| Found during | Wave 1/2 debt pass — successor to `3-3-missing-retry-and-circuit-breakers` |
| Date | 2026-08-01 |

## Issue

The predecessor entry tracked two missing things. **Call-level retry is done**: `RetryingDiffSource`
decorates every worker `DiffSource` at the `WorkerScmClients.Clients` constructor, retrying 5xx reads
up to three attempts with jittered exponential backoff. Writes are deliberately excluded — comment
posts already carry a `comment_idempotency` claim and a Retry-After-aware backoff budgeted against
Kafka's `max.poll.interval.ms`, and a second retry layer underneath would silently double the sleep
that budget is bounding.

**No circuit breaker exists.** A provider returning 5xx for an hour still gets a full retry ladder per
call, per review, from every command that arrives.

## Risks

Lower than when the predecessor was filed, which is why this was separated rather than built in the
same pass:

- The retry above absorbs the single-blip case that used to burn a whole ADR-016 attempt.
- Scheduled retry backoff (V25 `review_retry_at`, 2026-07-23) already stopped failed reviews from
  re-driving immediately, so a degraded provider is no longer hammered by instant whole-pipeline
  re-runs — the original entry predates that work.

What remains uncovered is sustained degradation: many *different* reviews arriving during an outage
each pay their own retries before failing. Wasteful, and it prolongs recovery for a provider that is
already struggling, but it is bounded by the retry ceiling and the review attempt budget.

## Suggested Solutions

1. **Decide the failure semantics first — this is the actual work, not the mechanism.** An open
   circuit is a new outcome the pipeline has to classify: is a short-circuited command retryable
   (back to ADR-016's budget), terminal, or DLQ'd? Getting that wrong turns a provider outage into a
   pile of permanently-failed reviews, which is worse than the waste it replaces.
2. Once decided, keep the state where it can be shared: the breaker must live in an
   `@ApplicationScoped` bean keyed per provider host, since `Clients` is rebuilt per command and a
   per-instance breaker would never open.
3. Prefer SmallRye Fault Tolerance's programmatic API over a hand-rolled breaker (rolling window,
   half-open probes and their races are easy to get subtly wrong), and confirm the API surface for
   the Quarkus version in use — it changed between SmallRye FT 5 and 6.
4. Consider whether the attention panel should surface an open circuit. It is exactly the kind of
   "true right now" condition that panel was built for, and an outage the operator cannot see is one
   they will diagnose as "reviews stopped working".

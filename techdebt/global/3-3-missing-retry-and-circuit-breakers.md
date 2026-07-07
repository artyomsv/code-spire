# No retry/backoff or circuit breakers on external SCM/LLM calls

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-scm-bitbucket/.../BitbucketCloudClient.java`, `spire-scm-github/.../GitHubClient.java`, `spire-llm/.../LangChain4jLlmProvider.java`, callers in `spire-review-worker/.../pipeline/` |
| Found during | Full-project code review (4-agent) |
| Date | 2026-07-07 |

## Issue

Outbound SCM and LLM HTTP calls have correct timeouts (SCM 10s, LLM 60s) but no
retry-with-backoff and no circuit breaker. Transient-failure handling currently relies
entirely on the review-level bounded auto-retry (ADR-016, `spire.review.max-attempts`) —
a whole-review re-run — rather than call-level retries. Already listed as pending P1 scope
in CLAUDE.md ("SmallRye Fault Tolerance retry budgets"); this entry makes it a tracked debt
item per the resilience rule.

## Risks

A single transient network blip on one of several sequential SCM calls fails the attempt and
burns a slot of the review retry budget; a degraded provider (slow 5xx storms) gets hammered
with full re-runs instead of being short-circuited.

## Suggested Solutions

1. Add SmallRye Fault Tolerance (`@Retry` with jittered backoff on 5xx/429/IO, `@CircuitBreaker`
   per provider host) at the adapter boundary in the worker. Keep the review-level ADR-016
   budget as the outer guard.
2. Ensure call-level retries stay idempotent: GETs are safe; comment POSTs are already guarded
   by the comment-idempotency store, and mutations must not retry on ambiguous outcomes
   (connection dropped after send).

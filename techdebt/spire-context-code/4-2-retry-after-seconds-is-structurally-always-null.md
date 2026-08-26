# `CodeContextApiException.retryAfterSeconds()` is structurally always `null`

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-context-code/src/main/java/dev/codespire/context/code/CodeContextApiException.java`, `spire-http/src/main/java/dev/codespire/http/PinnedJsonClient.java` |
| Found during | Task 15 — repository knowledge base rung 1, final verification pass |
| Date | 2026-08-26 |

## Issue

`CodeContextApiException.retryAfterSeconds()` exists and is documented, but its body is a bare
`return null;`:

```java
/**
 * Seconds the provider asked us to wait; always {@code null} here. {@link
 * dev.codespire.http.PinnedJsonClient}'s failure callback does not surface response headers to the
 * exception it builds, so there is nothing to parse a {@code Retry-After} value from yet.
 */
public Integer retryAfterSeconds() {
    return null;
}
```

This is not a bug the method's own javadoc hides — it says exactly why — but the design spec this
module was built from (`docs/superpowers/specs/2026-08-25-repository-knowledge-base-design.md` §8.1)
originally listed the rate-limited failure mode as "Existing `Retry-After` ladder; shares the
per-host circuit", which overstated what this path actually does: the SCM adapters
(`RetryingDiffSource` and friends) have a real `Retry-After`-aware backoff; `spire-context-code` does
not, because the shared `PinnedJsonClient` its `SourceFileReader`s are all built on
(`GitHubSourceFileReader`, `GitLabSourceFileReader`, `BitbucketSourceFileReader`) never gives its
failure-mapping callback (`CodeContextApiException::new`) the response headers it would need to
populate the field. The spec has been corrected to state this plainly; this entry tracks closing the
actual gap.

A second, compounding fact worth recording here: `CircuitBreakingSourceFileReader.isUnhealthy` only
counts `status >= 500` toward the shared per-host circuit, so a 429 neither backs off with a delay
(this entry) nor opens the circuit (by design, per that class's own javadoc — a rate limit is "this
repository, not the host"). A repeatedly-429'd source-file read today just fails immediately, over
and over, once per snippet candidate, for as long as the rate limit lasts.

## Risks

Low. The 20-second context-aggregation budget already bounds the blast radius — a rate-limited `code`
provider degrades to "resolved fewer snippets than it could have," not to a stuck or hung review, and
`ContribStatus.ERROR` only fires when the whole contribution ends up empty. The cost is missed
context on a rate-limited host, silently indistinguishable from a host that genuinely has nothing to
resolve, which is a correctness-of-completeness gap rather than a reliability one.

## Suggested Solutions

1. **Thread response headers through `PinnedJsonClient`'s failure callback.** The callback currently
   receives `(status, method, path, detail)`; extending it to also carry a `Retry-After` header value
   (when present) lets `CodeContextApiException.retryAfterSeconds()` do what its javadoc already
   promises, and gives every other `PinnedJsonClient` consumer (Jira, Confluence, GitHub/GitLab issue
   context) the same capability for free. The natural place to prove it end-to-end is a per-adapter
   test asserting a 429 with a `Retry-After` header populates the field — mirroring how the SCM
   adapters' own retry-after extraction is tested.
2. **Add a backoff at the `CircuitBreakingSourceFileReader` layer** once (1) lands, so a 429 pauses
   retrying that one host for the stated duration without opening the circuit outright — distinct
   from a 5xx, which should still open it.
3. Leave as is. Defensible today because the 20s aggregation budget already caps the cost of repeated
   immediate failures, and a `code` provider is additive — its absence degrades a review to "context
   as if this provider were not configured," never to a failed one.

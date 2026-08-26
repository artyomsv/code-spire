# The `code` provider's platform-from-host heuristic is duplicated with no build guard

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-review-worker/src/main/java/dev/codespire/worker/adapters/WorkerContextClients.java` (`readerFor`, `hostOf`), `spire-orchestrator/src/main/java/dev/codespire/orchestrator/context/ContextKeyValidator.java` (`codePlatform`, `codeProbe`, `codeCheckPath`) |
| Found during | Task 15 — repository knowledge base rung 1, final verification pass |
| Date | 2026-08-26 |

## Issue

A single generic `code` context-provider type covers all three raw-content APIs (GitHub, GitLab,
Bitbucket) — task 13's Settings UI offers one "Repository code" option, not three — so nothing in the
registered credential says which platform it is. Both sides of the module boundary that need to know
infer it independently from the same signal, the baseUrl's host substring:

```java
// WorkerContextClients.readerFor (spire-review-worker) — picks the SourceFileReader
if (host.contains("gitlab")) { reader = new GitLabSourceFileReader(config); }
else if (host.contains("bitbucket")) { reader = new BitbucketSourceFileReader(config); }
else { reader = new GitHubSourceFileReader(config); }

// ContextKeyValidator.codePlatform (spire-orchestrator) — picks the connectivity-check route
if (h.contains("gitlab")) { return "gitlab"; }
if (h.contains("bitbucket")) { return "bitbucket"; }
return "github";
```

Both copies' own javadoc already names the other as the reason a shared home would be nice
(`WorkerContextClients.readerFor`: *"the orchestrator's `ContextKeyValidator` connectivity check for
this same type has to infer it independently, on the other side of the module boundary"*;
`ContextKeyValidator.codePlatform`: *"kept independently here because a 'code' credential carries no
platform field to read instead, and the orchestrator and worker modules do not share code across this
boundary"*) — the duplication was noticed at write time, not missed.

This is the same shape as the SCM-client redirect-loop duplication already tracked in
`techdebt/global/4-4-scm-clients-duplicate-the-pinned-json-client-redirect-loop.md`, with one
material difference: that entry's three copies are each covered by `RedirectHandlingHasOneHomeTest`
(`spire-arch`), so a fourth hand-rolled loop — or a copy that stops looking like one — fails the
build. **No equivalent guard exists here.** Nothing fails if `WorkerContextClients.readerFor` and
`ContextKeyValidator.codePlatform` drift — an added platform, a changed substring, a reordered
if/else — because nothing asserts the two heuristics agree.

## Risks

The concrete failure mode: an operator's **Check** in Settings → Context reads the credential as one
platform (say, a self-managed GitLab host that happens not to contain "gitlab") and reports success,
while a real review's `WorkerContextClients.readerFor` reads the *same* baseUrl as a different
platform and calls the wrong raw-content API — 404s that look like an absent file rather than a
platform mismatch, so context is silently never contributed. "Check passed, reviews never resolve
anything" is a confusing, hard-to-diagnose symptom precisely because nothing signals *which* platform
each side picked.

Today both copies happen to agree (same three-branch order: gitlab, bitbucket, else-github), so there
is no live defect — this is a maintenance/drift risk, not a current bug, which is why it is Medium
rather than High.

## Suggested Solutions

1. **Extract a shared, framework-free `CodePlatform.of(String baseUrl)`** into `spire-contract` (both
   `spire-review-worker` and `spire-orchestrator` already depend on it, and it needs nothing beyond
   `java.net.URI`), and have both call sites delegate to it. Cheapest fix, and it removes the drift
   risk entirely rather than just detecting it.
2. **If the two must stay separate** (e.g. one is considered too link-time-sensitive to add a new
   cross-module dependency for), add a `spire-arch` test asserting the two heuristics agree for a
   fixed set of representative hosts — the idiom `RedirectHandlingHasOneHomeTest` and
   `PureModulesAreFrameworkFreeTest` already use, so a break is caught at build time instead of in a
   confused operator's bug report.
3. Leave as is. Defensible only as long as nobody has yet added a fourth platform or changed either
   heuristic's branch order without the other.

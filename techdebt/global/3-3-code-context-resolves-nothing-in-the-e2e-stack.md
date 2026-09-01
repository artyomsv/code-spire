# The code context provider resolves nothing against the containerised GitLab, and we do not know why

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-context-code`, `spire-review-worker/.../ContextWorker.java:306`; reproduction is `spire-e2e/.../CodeContextProbeTest` (`@Disabled`) |
| Found during | Building the GitLab end-to-end suite (`spire-e2e`), 2026-08-30 |
| Date | 2026-08-30 |

## Issue

The `code` context provider runs against the e2e stack's containerised GitLab, extracts identifiers,
and resolves **none** of them. Its own instrumentation says so:

```
Context resolution for CODE: extracted=17 resolved=0 contributed=0 droppedForBudget=0
```

`worker.context_blob` and `worker.code_symbol` are both empty across every review the suite has run.
`CodeContextProbeTest`'s rung-1 assertion — that a definition reached the model — fails.

**The cause is not established.** That is the honest state, and this entry exists to record the
symptom, the reproduction, and one hypothesis already ruled out, rather than to assert a mechanism.

## The hypothesis that was wrong, recorded so nobody repeats it

The first diagnosis, which reached a techdebt entry, this suite's design spec, `CLAUDE.md` and the
pull request before being caught, was: *`PinnedJsonClient`'s SSRF guard refuses site-local addresses
on every request, so a Docker-network GitLab can never be fetched.*

That is **false**, and reading the code says so plainly. `isPrivateAddress`
(`PinnedJsonClient.java:196`) is reachable only from `requireSafeRedirectTarget`, which runs only on a
**3xx** response, and which returns immediately when the redirect host matches the base host. Its own
javadoc states the intent: *"Same-host targets skip the check — the base host is operator config, not
attacker data, and dev/test legitimately run against WireMock on localhost."* A direct request to a
private base URL is never checked. `SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS` is genuinely a
different control (`PublicHttpsGuard`, orchestrator, registration-time), but that distinction — true in
itself — was used to support a conclusion the code does not support.

Two things are worth carrying forward from how this happened. The symptom was real and reproducible,
and a plausible mechanism was reached for without testing it, which is the failure mode this
repository already documents for the LLM circuit breaker recording a failed future as a success. And a
four-lens review round did **not** uniformly catch it: the security lens read the claim and called the
diagnosis accurate, while the QA lens read `PinnedJsonClient` and found the guard unreachable on the
direct path. Agents agreeing is not evidence.

## What is actually known

- The provider is registered, enabled and brokered — a `code` credential reaches the worker, or no
  `Context resolution for CODE` line would be logged at all.
- Extraction works: `extracted` is consistently non-zero (4, 17, 23, 31 across runs).
- `resolved=0` is **correct** for the chain fixtures, which import nothing — so those log lines are
  not themselves evidence of a defect. Only a probe run's counters are diagnostic, and they have not
  been isolated.
- Nothing throws. Context providers fail soft by design, so a failure and an empty result are
  indistinguishable from outside.

## Risks

The operator-facing risk is the one that outlives this entry: a context provider that resolves nothing
is **indistinguishable from a pull request that genuinely had no context**. The review completes,
`ContextRequested` / `ContextContributed` / `ContextAssembled` all fire for the timeline, and
`context_blob` is simply empty. Whatever the cause here turns out to be, an operator would not learn
that retrieval was failing — the same shape as
`techdebt/global/3-2-code-extension-map-duplicated-with-no-drift-guard.md`, which notes that both
directions of its drift *"read as 'the code provider contributed nothing for this file', which is also
what a genuinely dependency-free file looks like"*.

It also leaves ADR-026 rung 1 and rung 2 covered only by worker-level seam tests. The e2e probes are
the only check that a retrieved definition and a cited caller reach a real model call.

## Suggested Solutions

1. **Isolate a probe run's counters first.** Re-enable `CodeContextProbeTest` against a live stack and
   read the `Context resolution for CODE` line for that specific review. `extracted=0` means the
   identifier extraction never saw the changed file; `extracted>0, resolved=0` means candidate-path
   resolution or the fetch is failing, and those are different investigations. This is cheap and
   should precede any code change.
2. **Make a failed fetch observable.** `ContextResolutionCounts` distinguishes extracted / resolved /
   contributed / droppedForBudget, but not *attempted-and-failed*. A `failed` counter, and a
   `LOG.warn` when a resolution attempt throws, would have turned this whole investigation into one
   log line — and would help an operator whose token expired just as much.
3. **Check the fixture before the product.** The probe puts the definition at
   `src/main/java/e2e/probe/pricing/Pricer.java` and imports it from
   `src/main/java/e2e/probe/Changed.java`. `JavaLanguageSupport.candidatePaths` builds
   `<ownRoot>e2e/probe/pricing/Pricer.java`, which should match — but "should" is what put the wrong
   diagnosis in five documents, so verify it against a real run rather than by reading.
4. Leave it. Defensible only while nobody relies on code context, which ADR-026 shipped specifically to
   make people rely on.

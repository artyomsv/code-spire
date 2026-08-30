# Context providers cannot reach an SCM or tracker on a private network

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-http/src/main/java/dev/codespire/http/PinnedJsonClient.java:186-201`; every caller — `spire-context-code`, `spire-context-jira`, `spire-context-confluence`, `spire-context-github`, `spire-context-gitlab` |
| Found during | Building the GitLab end-to-end suite (`spire-e2e`), 2026-08-30 |
| Date | 2026-08-30 |

## Issue

`PinnedJsonClient` refuses loopback, link-local, site-local and any-local addresses on **every
request**:

```java
if (address.isLoopbackAddress() || address.isLinkLocalAddress()
        || address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
```

That is a correct SSRF guard for an operator-supplied base URL. It also means every context provider
built on `spire-http` is unable to reach anything on RFC1918 — a self-hosted GitLab, a self-hosted
Jira or Confluence, a GitHub Enterprise Server, or any of them behind a private ingress. Which is to
say: the deployments most likely to want a self-hosted context source are the ones that cannot use
one.

**There is no configuration escape.** `spire.security.allow-insecure-provider-urls` looks like one and
is not: it governs `PublicHttpsGuard`, which the ORCHESTRATOR consults at provider create/update
(`ProviderResource`, `LlmProviderResource`, `ContextProviderResource`). The worker's per-request guard
is a different control, in a different module, with no such flag. Relaxing the first lets an operator
*register* a private-address context provider; the provider then fails on every fetch.

**The failure is silent.** Context providers fail soft by design — a retrieval problem must not turn a
review that would otherwise succeed into a terminal failure. So the review completes normally,
`ContextRequested` / `ContextContributed` / `ContextAssembled` are all emitted for the dashboard
timeline, and `worker.context_blob` simply stays empty. Nothing in the UI, the timeline or the
attention panel distinguishes "this provider is structurally unable to fetch" from "this pull request
genuinely had no context to add".

This was found empirically, not by reading: a registered, enabled `code` context provider pointed at a
containerised GitLab produced zero `context_blob` rows and zero `code_symbol` rows across dozens of
reviews, with no error anywhere.

## Risks

Two, and the second is the worse one.

An operator on a private network configures a context provider, sees it saved and enabled, sees
reviews complete, and never learns that no context was ever retrieved. The feature appears installed
and is inert — the same shape as the LLM circuit breaker that recorded a failed future as a success,
and as the code-extension-map drift entry beside this one: *"both read as 'the provider contributed
nothing for this file', which is also what correct behaviour looks like"*.

It also blocks the `spire-e2e` code-context probes (`CodeContextProbeTest`, `@Disabled` with this
entry cited), which are otherwise complete and correct. Those probes are the only automated check that
a retrieved definition and a cited caller actually reach the model, so rung 1 and rung 2 of ADR-026
remain covered only by the worker-level seam tests.

## Suggested Solutions

1. **Give `PinnedJsonClient` the same opt-in relaxation the orchestrator guard has.** One flag,
   defaulted closed, read once at construction and carried on the client rather than consulted per
   request, so it is visible at the composition root. This is a change to a security control and
   deserves its own reviewed commit — the flag must be genuinely off by default, and the
   `%prod` refusal that already exists for `SPIRE_TRUSTED_PROXIES` is the precedent for how to keep it
   from being switched on casually.
2. **Allow an explicit host allow-list instead of a blanket relaxation.** Narrower and better: an
   operator naming `gitlab.internal` is asserting something specific, where a boolean re-opens
   everything the guard protects. More work, and it needs a home — probably beside the provider's own
   registry row rather than as global config.
3. **Make the silence visible without changing the guard.** A context provider that refuses every
   fetch for a structural reason should raise an attention row rather than contributing nothing
   quietly. This does not unblock anyone, but it converts an invisible failure into a visible one,
   which is the panel's whole contract — and it is worth doing even if 1 or 2 lands, because the same
   silence hides an expired token or a moved host.
4. Leave it. Defensible only while every deployment's context sources are public, which is exactly the
   assumption an operator running a self-hosted stack is least likely to satisfy.

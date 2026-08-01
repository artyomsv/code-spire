# Three SCM clients duplicate the redirect loop `spire-http` already owns

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Large |
| Location | `spire-scm-bitbucket/.../BitbucketCloudClient.java`, `spire-scm-github/.../GitHubClient.java`, `spire-scm-gitlab/.../GitLabClient.java`, `spire-context-jira/...`, `spire-context-confluence/...` |
| Found during | Wave 1/2 debt pass — successor to `3-3-scm-clients-still-carry-their-own-unguarded-redirect-resolve` |
| Date | 2026-08-01 |

## Issue

The predecessor entry tracked a real defect: all three SCM clients called `target.resolve(location)`
bare, so a malformed `Location` header threw a raw `IllegalArgumentException` that bypassed every
caller's `ScmApiException` classification. **That is fixed** — each client now has its own
`redirectTarget` guard mirroring `PinnedJsonClient.redirectTarget`, with a regression test per client
(`refusesAnUnparseableRedirectTarget`, verified by mutation to fail without the guard).

What remains is duplication, not a bug. Five manual redirect loops now exist with the same shape —
host-pinned auth, 3xx handling, `requireSafeRedirectTarget`, and now the parse guard: the three SCM
clients plus `spire-context-jira` and `spire-context-confluence`, against the one in `spire-http`
that `spire-context-github` and `spire-context-gitlab` use. A future fix to the redirect handling
still has to land in six places, and the next one may not be caught as cheaply as this one was.

## Risks

Maintenance only, and it is the risk that produced the predecessor entry: `spire-http` was extracted
specifically to give the guard one home, then the migration covered two of the seven callers, and the
gap went unnoticed until a review checked the "one home" claim against the code. The same drift can
recur — a hardening change lands in `PinnedJsonClient` and silently misses the five hand-rolled
copies.

No correctness gap is known today: the three SCM clients and the two older context adapters now
carry equivalent guards.

## Suggested Solutions

1. **Migrate the SCM clients onto `PinnedJsonClient`** when they are next opened for other work. Not
   a drop-in swap: they support POST/PUT and per-client `Retry-After` extraction that the read-only
   context use never needed, so `PinnedJsonClient` would have to grow those first.
2. **Migrate `spire-context-jira` and `spire-context-confluence` first** — they are read-only JSON
   clients, which is exactly what `PinnedJsonClient` was built for, so they should be close to a
   straight swap and would cut five copies to three at a fraction of the cost.
3. Leave as is and accept the duplication, provided any future change to redirect handling is
   explicitly applied to all six sites.

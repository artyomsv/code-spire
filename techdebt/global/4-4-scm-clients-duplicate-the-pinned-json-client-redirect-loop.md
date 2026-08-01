# Three SCM clients duplicate the redirect loop `spire-http` already owns

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Large |
| Location | `spire-scm-bitbucket/.../BitbucketCloudClient.java`, `spire-scm-github/.../GitHubClient.java`, `spire-scm-gitlab/.../GitLabClient.java` |
| Found during | Wave 1/2 debt pass — successor to `3-3-scm-clients-still-carry-their-own-unguarded-redirect-resolve` |
| Date | 2026-08-01, corrected 2026-08-02 |

## Issue

The predecessor entry tracked a real defect: all three SCM clients called `target.resolve(location)`
bare, so a malformed `Location` header threw a raw `IllegalArgumentException` that bypassed every
caller's `ScmApiException` classification. **That is fixed** — each client now has its own
`redirectTarget` guard mirroring `PinnedJsonClient.redirectTarget`, with a regression test per client
(`refusesAnUnparseableRedirectTarget`, verified by mutation to fail without the guard).

What remains is duplication, not a bug.

**Correction (2026-08-02).** This entry previously claimed five hand-rolled loops remained and
recommended migrating `spire-context-jira` and `spire-context-confluence` first as the cheap half.
Both were already on `PinnedJsonClient` — they were migrated in the same pass that extracted
`spire-http`, exactly as CLAUDE.md records. The count was wrong when the entry was written. Verified
against the code:

- **One** shared implementation: `spire-http/PinnedJsonClient`, used by the Jira, Confluence,
  GitHub-issues and GitLab-issues context adapters.
- **Three** hand-rolled loops: the SCM clients below.
- **Two** clients that refuse redirects outright (`ContextKeyValidator`, `LlmKeyValidator`): they set
  `Redirect.NEVER` and never read `Location`. That is the safest posture available and carries no
  guard of its own, so it is not a copy and is not counted.

## Risks

Maintenance only. The entry's actual fear — a hardening change lands in `PinnedJsonClient` and
silently misses the copies — is now **build-enforced** by `RedirectHandlingHasOneHomeTest`
(`spire-arch`). A fourth hand-rolled loop fails the build, and an allowlist entry that stops
describing a redirect loop fails too, so the count can only go down. What the check cannot do is
force the existing three across; that is the work below.

No correctness gap is known today: all three carry guards equivalent to the shared client's.

## Suggested Solutions

1. **Grow `PinnedJsonClient`, then migrate the SCM clients** — in that order, and preferably when one
   of them is next opened for other work rather than as a standalone change. They are not a drop-in
   swap: they need POST/PUT with JSON bodies (comment posting), non-JSON GETs (`getRaw` for GitHub's
   raw file content, `getText` for GitLab's), per-provider `Retry-After` extraction, and GitHub's
   GraphQL POST plus its 403-with-zero-remaining rate-limit heuristic. The shared client is read-only
   JSON today because that is all the context adapters ever needed.
2. Leave as is. The guard above makes the duplication visible and bounded, which addresses the drift
   risk without touching the comment-posting write path — the highest-consequence code in the system,
   and the reason this is rated Large despite being Low criticality.

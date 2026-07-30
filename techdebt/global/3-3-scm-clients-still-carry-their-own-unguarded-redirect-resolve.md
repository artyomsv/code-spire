# Three SCM clients still carry the unguarded redirect-target resolve `spire-http` already fixed

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-scm-bitbucket/.../BitbucketCloudClient.java:85`, `spire-scm-github/.../GitHubClient.java:136`, `spire-scm-gitlab/.../GitLabClient.java:79` — all `target = target.resolve(location);` |
| Found during | Final fix wave for the issue-context-providers branch (blocker review of `spire-http`'s "one home" claim) |
| Date | 2026-07-30 |

## Issue

`spire-http`'s `PinnedJsonClient` (built for the GitHub/GitLab Issues context providers) wraps the
`Location`-header resolve in a try/catch that turns a malformed value (`Location: http://`) into the
adapter's own classified exception type instead of letting `URI#resolve`'s `IllegalArgumentException`
escape (see `PinnedJsonClient.redirectTarget`, with a regression test —
`refusesAnUnparseableRedirectTarget`). The three SCM clients predate that fix and still call
`target.resolve(location)` bare, with no try/catch around it:

- `BitbucketCloudClient.java:85`
- `GitHubClient.java:136`
- `GitLabClient.java:79`

Each of these sits inside the same manual-redirect loop shape `PinnedJsonClient` now handles safely
(host-pinned auth, 3xx handling, `requireSafeRedirectTarget`) — the SCM clients were the original
pattern `spire-http` was extracted from, but the extraction only ever covered the context adapters
(`spire-context-github`, `spire-context-gitlab` today; `spire-context-jira`,
`spire-context-confluence` predate `spire-http` and were not migrated either). `spire-http`'s own
doc comment, its `build.gradle.kts` header, and `LICENSING.md` were corrected in the same pass that
filed this entry to stop claiming the SCM clients already use it — they don't.

## Risks

A malformed or adversarial `Location` header on a redirect response from (or spoofing) Bitbucket,
GitHub, or GitLab throws a raw, unclassified `IllegalArgumentException` out of `send()`. Every caller
of these three clients — the diff sources, comment sinks, identity resolvers — only catches and
classifies `ScmApiException` (retryable vs. not, 401/404/406 handling, `Retry-After`, etc.), per
ADR-020's provider-neutral error handling. The unchecked exception bypasses all of that: it is not
retried, not logged with the adapter's own context, and surfaces as an unhandled failure instead of
a normal `ScmApiException` the review pipeline already knows how to degrade from.

## Suggested Solutions

1. **Preferred, once the SCM adapters are due for their own maintenance pass:** migrate
   `BitbucketCloudClient`, `GitHubClient`, and `GitLabClient` onto `spire-http`'s `PinnedJsonClient`
   directly, retiring the three duplicated manual-redirect-loop implementations entirely. This was
   explicitly out of scope for the issue-context-providers branch (a separate, larger piece of work —
   the SCM clients also support POST/PUT and per-client `Retry-After` extraction that `PinnedJsonClient`
   does not need for its read-only context use, so the migration is not a drop-in swap).
2. **Minimal, in place:** wrap each `target.resolve(location)` call in its own client in a
   try/catch(IllegalArgumentException), mirroring `PinnedJsonClient.redirectTarget`, throwing that
   client's own `*ApiException` instead. Three near-identical small patches, easy to land
   independently of the full migration, with a regression test per client matching
   `PinnedJsonClientTest.refusesAnUnparseableRedirectTarget`.

# WebhookRepoResourceTest still asserts the pre-one-time-reveal response shape

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Trivial |
| Location | `spire-gateway/src/test/java/dev/codespire/gateway/WebhookRepoResourceTest.java` |
| Found during | Task 12 full-verification run (docs + build) for the re-review reconciliation feature |
| Date | 2026-07-18 |

## Issue

`WebhookRepoResource.create()` / `update()` return a `WebhookRepoSecret(WebhookRepoView repo, String
secret)` — the fields the test asserts (`scope`, `target`, `providerType`, `hasSecret`, `webhookKey`)
are nested under `repo.*`, and `secret` is intentionally populated (plaintext, one-time reveal) rather
than null. This shape was introduced by commit `4bed9eb` ("Generate webhook secrets server-side with
one-time reveal"), which predates this feature by several commits, but `WebhookRepoResourceTest` was
never updated to match — it still asserts the OLD flat contract (`.body("scope", equalTo("repo"))` at
the JSON root, `.body("secret", nullValue())`).

6 of 9 tests in the class fail deterministically as a result (`createRepoScopeGeneratesAKeyAndNever
EchoesTheSecret`, `createOrgScopeAcceptsAnOwnerTarget`, `deletesAndThenIsGone` — whose setup step also
extracts `id` from the wrong nesting level — `rejectsMissingSecretOnCreate`, `updateKeepsTheKeyAndRota
tesSecretOnlyWhenSupplied`, `eachRegistrationGetsADistinctKey`). Confirmed unrelated to the ADR-019
reconciliation feature: `git diff` across that feature's full commit range (`2084eab..0e74ac5`) touches
zero files under `spire-gateway`, and the failure reproduces identically running the class in isolation.

## Risks

`./gradlew build` is red on a clean checkout independent of any other work, which masks real
regressions in this module going forward (nobody can tell "did my change break this" from "was this
already broken"). `PUT /api/webhook-repos/{id}` (update) is NOT documented as returning the wrapped
shape in the javadoc — worth double-checking whether `update()` should return a plain `WebhookRepoView`
(no secret to reveal on a rename) rather than reusing `WebhookRepoSecret`; if so the test failures for
`updateKeepsTheKeyAndRotatesSecretOnlyWhenSupplied` may point at a real product-shape question, not
just a stale assertion.

## Suggested Solutions

1. Update the six failing tests' JSON paths to `repo.scope`, `repo.target`, `repo.providerType`,
   `repo.hasSecret`, `repo.webhookKey`, and extract `repo.id` instead of `id`; assert `secret` is
   present (non-null, non-blank) on create rather than `nullValue()`.
2. Before (1), confirm `WebhookRepoResource.update()`'s actual current return type/shape (it calls
   `registry.update(...)` which returns `Optional<WebhookRepoView>`, not `WebhookRepoSecret` — so the
   update assertions may need the OLD flat paths, and only the two `create()`-based tests need the
   `repo.*` nesting). Fix per actual shape, not by symmetry assumption.
3. Add a quick regression note in the PR/commit message pointing at `4bed9eb` so the "why now, why not
   caught at the time" question has an answer in history.

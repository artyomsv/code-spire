# A prompt scope carries no provider type

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-orchestrator/.../prompt/PromptScope.java` (`of`), `.../prompt/WorkerPromptTemplates.java` (`forKind`), `prompt_template.scope` (V34) |
| Found during | PR #61 review — final fix wave for `feat/prompt-scope-and-conversation-findings` |
| Date | 2026-08-24 |

## Issue

`PromptScope.of(RepoRef repo)` is `repo.workspace() + "/" + repo.slug()`, and `RepoRef` itself is just
`{workspace, slug}` — no SCM or provider component, the same shape `ReviewIds.reviewId` uses for a
review's own address.

That is the exact collision class the project already has an incident on record for
(`techdebt/spire-orchestrator/3-3-the-charge-ledger-is-keyed-on-an-id-two-scms-can-share.md`, and before
that the 2026-07-25 parity run that found workspace `artyomsv` registered as both a GitHub org and a
Bitbucket workspace). A `PromptScope` inherits it identically: an admin who scopes a prompt override to
`acme/widgets` on GitHub silently changes the instruction set every review of the same-named
`acme/widgets` on GitLab or Bitbucket runs under too, because `PromptRegistry.effective` resolves the
override by `(scope, kind)` alone — it has no provider to disambiguate against, unlike
`ReviewProviderResolver`, which was built for exactly this shape of problem on the read side.

## Risks

Scoped honestly, in line with the sibling entry:

- **Not attacker-driven.** It requires the operator to have registered the same workspace/slug pair on
  two platforms themselves — a correctness bug under a supported (if unusual) configuration, not an
  exploit.
- **Both sides are admin-controlled.** Unlike the charge ledger (money, unrecoverable once merged), a
  prompt override is admin-authored on both platforms; the worst case is one admin's intended
  instructions silently apply to a repository they did not mean to touch, which is confusing rather than
  destructive — a re-save at the correct scope (once the collision is understood) fully corrects it.
- **Compounds with the charge-ledger issue.** A deployment that has already hit the reviewId collision
  (dual-registered workspace) is exactly the deployment where this also fires, so the two are likely to
  surface together rather than independently.

Filed at Medium/Small: the fix is narrow (one field, one comparison), but worth doing before per-repo
prompt scoping sees more use — the more `.codespire`-adjacent, operator-facing this feature becomes, the
more surprising a cross-platform silent instruction swap gets.

## Suggested Solutions

1. **Add `providerType` to the scope key** (the narrow fix, mirroring the charge-ledger entry's
   preferred option): extend `prompt_template`'s primary key from `(scope, kind)` to
   `(scope, provider_type, kind)` — or fold `providerType` into the stored `scope` string itself
   (`github:acme/widgets`) to avoid a migration touching the primary key. `RepoRef` would need a
   provider-type field threaded in from `PullRequestEventReceived`/the review row, the same value
   `ReviewProviderResolver` already reads.
2. **Leave it and document the constraint**, the same fallback the charge-ledger entry records: state
   that one workspace/slug pair must not be registered on two platforms if either is going to carry a
   per-repository prompt override. Weakest option — nothing warns, and the project has already learned
   operators do this by accident.

Whichever is chosen, do it in the same pass as (or right after) the charge-ledger fix — both are the
same root cause (`workspace/slug` treated as globally unique when it is only unique per platform), and
fixing one without the other leaves the next reader assuming the collision class was closed everywhere.

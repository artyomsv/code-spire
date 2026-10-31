# Harness reviews, PR 1: the review's LLM provider per repository — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A repository can name the LLM provider its reviews and replies use; a repository that names none keeps
using the global default, exactly as today.

**Architecture:** One nullable column on `repository` (`review_llm_provider_id`). The two places that resolve a
review's credential (`ResultSaga` on `ContextAssembled`, `ConversationSaga` on a reply) ask `WorkerLlmCredentials`
for the review's repository instead of the global default; the review row already carries `repository_id` (V60).
The repository's Settings tab gets a "Review with" select. This is the "API" half of the design's "Review with"
setting; the harness half comes in later PRs.

**Tech Stack:** Java 25 / Quarkus (orchestrator), Flyway, React 19 + React Query (dashboard).

**Spec:** `docs/superpowers/specs/2026-10-30-harness-reviews-design.md` (operator decision 1, delivery order step 1).

## Global Constraints

- No silent change of payer: a repository's named provider that is switched off refuses the review (the existing
  `DefaultLlm.Refusal` path, with a note and an attention item) rather than falling back to the default.
- A provider a repository names cannot be deleted (409 naming the repositories); switching it off is allowed and
  refuses as above.
- The priceability guard and `SpendGate` apply unchanged to a named provider.
- No local build, test or lint (CI only). `TEST-` placeholders. Commits: imperative, ≤ 72 chars, no AI attribution.

## Decisions

- **Schema:** `V105__repository_review_llm_provider.sql`: `ALTER TABLE repository ADD COLUMN review_llm_provider_id
  UUID REFERENCES llm_provider(id)` (no ON DELETE action: the delete is refused in code first, and the FK backs it).
- **Registry:** `RepositoryInput` and `RepositoryView` gain `reviewLlmProviderId` (nullable UUID); the settings update
  writes it with the revision bump the other settings use. An id naming no provider is a 400.
- **Resolution:** `WorkerLlmCredentials.resolveForReview(String reviewId)` reads `review_status.repository_id` →
  `repository.review_llm_provider_id`. Null (or no repository row, e.g. a legacy review) → `resolveDefault`. Set →
  `LlmProviderRegistry.resolve(id)`: enabled → packed as today; disabled → `DefaultLlm.Refusal.REPOSITORY_PROVIDER_OFF`
  with a note naming the provider. `ConversationSaga` uses the same method with the thread's review id.
- **Delete guard:** `LlmProviderResource.delete` answers 409 "used by repositories: …" when any repository names it.
- **UI:** Repositories → repository → Settings: a "Review with" select listing "Global default (<name>)" and every
  enabled provider (`llmProvidersKey`); saving writes through the repository settings endpoint and updates
  `repositoriesKey`. The repository list's row shows a non-default choice beside the reviewer account.
- **Attention:** the existing "no usable default provider" attention item gains the per-repository case: a
  repository whose named provider is switched off.

## Review Focus

1. A legacy review row with no `repository_id` must keep the global default.
2. A reply must use the same provider as its review's repository, not the default.
3. Switching a named provider off must refuse, never fall back.
4. Deleting a named provider must be refused before any row changes.
5. The UI must never offer a disabled provider as a choice.

---

### Task 1: Failing tests

- [ ] `WorkerLlmCredentialsTest` (orchestrator, Testcontainers tier as its neighbours): named provider used; null →
  default; no repository row → default; named provider disabled → `REPOSITORY_PROVIDER_OFF`.
- [ ] `ResultSaga`/`ConversationSaga` tests: the command carries the named provider's packed credential.
- [ ] `LlmProviderResource` test: delete of a named provider → 409, row intact.
- [ ] UI: `RepositorySettings` test — the select lists default plus enabled providers, saves the id.
- [ ] Push; draft PR; CI fails on exactly these.

### Task 2: Implementation as decided; CI green.

### Task 3: Mutation check (named-provider disabled falls back to default → exactly the refusal test fails), revert,
docs (CLAUDE.md status bullet for reviews, `docs/DATA-MODEL.md` repository columns, ADR note that ADR-018's
`scm_provider.llm_provider_id` phase-3 item is delivered per repository).

## Changes after the plan check (they override the sections above)

1. **Every saga test fake moves to the new method.** `ConversationSagaTest` (ten fakes), `ConversationSpendCapTest`,
   `ResultSagaPricingTest` and `SpendCapGateTest` override `resolveDefault(String)`; once the sagas call
   `resolveForReview(String reviewId)` they would fall through to the real method and open a database (CLAUDE.md's
   un-overridden-fake trap). Each fake overrides `resolveForReview` instead; a grep for fakes overriding only
   `resolveDefault` comes back empty.
2. **The select never hides a switched-off choice.** The currently named provider is always an option, labelled
   "<name> (switched off)" when disabled, so a repository naming it shows it and a save keeps it. Other repositories
   cannot pick a disabled provider. Test: a repository naming a disabled id renders that option selected and saves the
   id unchanged.
3. **The delete guard lives in the registry's transaction.** `LlmProviderRegistry.delete` selects the repositories
   naming the id and throws a typed `ProviderInUse` (mapped to 409 by the resource, as `RepositoryResource.conflict`
   does) before deleting, in one `@Transactional` method, so a concurrent save cannot turn it into a 500.
4. **Repository writes name the column; an unknown id is a 400.** `insert`, `update`, `normalize`, `toView`,
   `RepositoryView`, `RepositoryInput` and their convenience constructors gain `reviewLlmProviderId`. The update looks
   the id up in `LlmProviderRegistry` first and throws `IllegalArgumentException` (already a 400) when it names nothing.
5. **Attention per repository.** Beside `buildDefaultsPricingRows`, with the repository as subject: a named provider
   switched off (BLOCKING), and a named provider whose model cannot be priced (the refusal `resolveForReview` would
   make). `LLM_DEFAULT_MISSING` still fires when no default exists even if every repository names its own provider;
   that is accepted and stated in the code.
6. **`DefaultLlm` gains `providerName`** and a `repositoryProviderOff(String providerName)` factory; both exhaustive
   switches (`note()`, `detail()`) get the branch.
7. **Test tiers.** `WorkerLlmCredentials` gains `ReviewProjection` and `RepositoryRegistry`, so its test is a
   `@QuarkusTest` on the services tier, seeding `review_status` through `claimRepository`.
   `RepositoryRegistryPage.test.tsx` spies `fetchLlmProviders` in its setup. The mutation step names the refusal test
   and the attention test as the two expected failures.
8. **Docs.** `docs/DATA-MODEL.md`'s repository paragraph gains `reviewer_may_approve` (V88) and
   `review_llm_provider_id` (V105).

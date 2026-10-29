# Live data step 6 (second batch): repository screens on React Query — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The repositories page, its pending mappings and its Factory tab (with the Build and Land steps) read through
`useQuery`; every save calls `invalidateQueries` or `setQueryData` instead of bumping a `refresh` counter.

**Architecture:** Each read gets one key, shared with every other screen that reads the same thing: the repositories
list (`repositoriesKey`), policies (`policyKeys`), the build setup (`buildDefaultsKey`) and the LLM catalogue
(`preparationKeys.llmModels`) already exist from PR #357. A save in the Factory tab invalidates the keys it changed, so
the list's "Factory setup" column and the work-item preparation form see it without a callback.

**Tech Stack:** React 19, `@tanstack/react-query` 5, vitest + Testing Library (CI only).

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` (§3.5, §5 step 6). First batch: PR #357
(`docs/superpowers/plans/2026-10-29-live-data-step6-work-item-read-once.md`).

## Global Constraints

- Screens with no live data use plain `useQuery` with their existing REST function; actions call `invalidateQueries`
  instead of bumping a `refresh` counter (spec §3.5).
- Tests render inside `test/queryWrapper.tsx` (a wrapper survives `rerender`).
- Test data uses `TEST-` placeholders only (no-synthetic-data rule).
- No local build, test or lint: CI runs them (no-local-builds rule).
- Commits: imperative, first line ≤ 72 chars, no AI attribution, no close/fix/resolve keyword before an issue number.
- Edit files with a script that keeps each file's line endings (some are CRLF, some LF).

## Scope

Moves: `RepositoryRegistryPage`, `RepositoryPending`, `factory/RepositoryFactory`, `factory/BuildStep` (its options),
`factory/LandStep` (its options). **Not moved** (Ruling 1): `RepositorySettings` (its effects report state to the
panel; it reads nothing), `factory/PeopleStep` (the person lookup is an action on a button, with its own request guard),
`RepositoryDetail`, `SourceStep`, `PolicySteps` (no reads). `SettingsWebhookRepos`, `SettingsProviders` and
`SettingsContextProviders` read the same endpoints but belong to the settings batch; they will reuse the keys below.

## Decisions this plan makes

- **New keys**, each next to its API function:
  - `providersKey = ['providers']` (`api.ts`), `webhookReposKey = ['webhook-repos']` (`api.ts`),
    `repositoryKindsKey = ['repository-kinds']` and `pendingMappingsKey = ['pending-mappings']` (`repositoriesApi.ts`),
    `workSourcesKey = ['work-sources']` (`work-items/workSourcesApi.ts`),
    `landSettingsKey(id) = ['land-settings', id]` and `landOptionsKey(id) = ['land-options', id]`
    (`factory/landSettingsApi.ts`), `buildOptionsKey(id) = ['build-options', id]` (`factory/buildDefaultsApi.ts`).
  - Reused: `repositoriesKey`, `policyKeys.profiles()`, `policyKeys.policy(id)`, `buildDefaultsKey(id)`,
    `preparationKeys.llmModels()`.
- **The registry page.** Repositories, providers and kinds keep their all-or-nothing load: "Loading repositories…"
  until the three answered, the error alert when one failed and nothing is on screen yet. Webhooks keep their own error
  and "Retry loading webhooks" (`refetch`). `setHooks` (passed to children as `onHooksChanged`/`changed`) becomes
  `setQueryData(webhookReposKey, rows)`. `replace`/`registered` write the saved repository into `repositoriesKey` with
  `setQueryData`. The "Factory setup" column reads `workSourcesKey` and `policyKeys.policy(id)` per repository
  (`useQueries`); a repository shows its readiness when the sources and its own policy answered, otherwise "unknown"
  (`null`), as a failed `Promise.all` did for every row.
- **The Factory tab.** Five `useQuery` calls on the shared keys. `reload` invalidates them; `changed` invalidates them
  and closes the form. `onChanged` and the page's `setupRefresh` counter are deleted: the column reads the same keys.
  The view shows the setup once all five answered, as before. A failed re-read after a success keeps the setup on
  screen and shows the error above it (before, the error replaced the whole tab).
- **Build and Land options** are read with `enabled: editing`, as before, so viewing the tab never calls the forge.
  Their failure fallbacks stay: Land turns a failed read into `{ checked: false, allowed: ['merge','squash','rebase'] }`
  (so "GitHub did not say…" still shows); Build offers choices only when both `buildOptions` and `fetchLlmModels`
  answered.
- **"Answered" means `data !== undefined`** everywhere (PR #357's lesson): a failed re-read keeps its data with
  `status: 'error'`, so `isSuccess` would blank a screen.
- **Writes into a key cancel a read of it first** (`cancelQueries` exact, then `setQueryData`), the cache rule of
  `liveCache.ts`: `setHooks`, `replace`/`registered` (`current => [...(current ?? []).filter(…), repository]`), and
  the pending row removal. Otherwise a read started before a create or link lands after it and removes the new row.
- **A save writes the row it got back, then invalidates.** `saveBuildDefaults`, `saveLandSettings` and `savePolicy`
  return the saved row: `setQueryData` with it, then invalidate the tab's keys, so the list column and the preparation
  form are right even when the re-read fails.
- **Pending mappings:** a linked row leaves with `setQueryData(pendingMappingsKey, rows => rows.filter(…))`, not an
  invalidate (whether the server drops a gateway-repaired registration at once is not established). The link error
  stays local; the load error comes from the query.
- **The column after a repository save** no longer re-reads the sources and every policy (the old effect keyed on the
  repositories array did); a new repository's policy is read once, and readiness is per row instead of all-or-nothing.
- **`refetchOnReconnect` keeps React Query's default** (the browser's online event). A re-read that brings a newer
  revision remounts an open form keyed on it; that only happens when someone else saved, where the open form is
  already stale. Turning it off globally belongs with the step 5 deferred minor of the same name, not this batch.
- **The build setup in the cache is now refreshed by the Factory tab's own save** (it invalidates `buildDefaultsKey`),
  so the preparation form's cached copy is current after a save in the same page load. PR #357's
  `isFetchedAfterMount && isSuccess` rule stays: a save in another tab or by another operator is still unseen.

## Review Focus

1. A repository panel moved to another repository while a read is out must never show the first repository's setup.
2. A link in "Mappings needing attention" must still re-read the webhooks before it repairs one (its deliberate fresh
   read), and the linked row must leave the list.
3. Registering a repository must add its row and open its panel at once, before any re-read.
4. Viewing the Factory tab must not call the forge (`landOptions`, `buildOptions`).
5. A failed re-read must not blank the repositories table or the Factory tab.

---

### Task 1: Failing tests

**Files:** `RepositoryRegistryPage.test.tsx`, `factory/RepositoryFactory.test.tsx`, `factory/LandStep.test.tsx`,
`factory/SourceStep.test.tsx` (every `render` gets `{ wrapper: queryWrapper() }`; `renderFactory` too, so its
`rerender` keeps the cache). `SourceStep.test.tsx` renders `RepositoryFactory` and passes `onChanged` too.

- [ ] **Step 1: Write the failing tests.**
  - Registry: `'shows the repositories at once when the page opens again'`: render with a wrapper, wait for the
    `TEST-repo` row, unmount, set `fetchRepositories` to a promise that never settles, render again with the same
    wrapper → the row is shown. Before: "Loading repositories…" for ever.
  - Factory: `'keeps the setup on screen when a reload fails'`: load, then make `buildDefaults` reject and click "Reload
    the saved setup" (reachable after a stale save refusal, as `'offers a reload after a refusal that says the setup
    changed'` reaches it) → the error text is shown (match the text: BuildStep keeps its own stale-save alert) and the
    outcome summary is still on screen. Before: only the error.
  - Factory: `'reads the build setup under the key the preparation form reads'`: a client that holds
    `buildDefaultsKey('TEST-repository')` = branch `TEST-old`; `buildDefaults` answers branch `TEST-new`; render the
    factory with a hand-built `QueryClientProvider` around `createQueryClient()` → after it settles, `client.getQueryData(buildDefaultsKey(...))`
    has `baseBranch: 'TEST-new'`. Before: the cache keeps `TEST-old`.
  - Factory, rewritten: `'ignores a load that answers after the panel moved to another repository'` defers the first
    repository's `buildDefaults` (not `fetchWorkSources`: the sources list is one key for every repository and is
    filtered at render), rerenders with `TEST-other`, resolves the deferred read last → the second repository's setup
    is shown and the first one's never is. It passes before and after; the mutation check does not cover it.
- [ ] **Step 2: Push; open a draft PR; CI fails** on exactly the three new tests.
- [ ] **Step 3: Commit** `Test the repository screens on React Query (red)`.

### Task 2: Implementation

- [ ] **Step 1:** Keys as listed above; `RepositoryRegistryPage`, `RepositoryPending`, `RepositoryFactory`,
  `BuildStep`, `LandStep` as decided above. Remove `onChanged` from `RepositoryFactory`'s props and its caller.
- [ ] **Step 2: Push; CI green**, including `'ignores a load that answers after the panel moved to another
  repository'` (rewritten in Task 1), `'repairs a gateway registration at its owner …'` and `'re-reads profiles after
  a failed apply …'`. Remove `onChanged` from all three test files that pass it.
- [ ] **Step 3: Commit** `Read the repository screens through React Query`.

### Task 3: Mutation check and docs

- [ ] **Step 1: Mutation commit** (reverted next): `RepositoryFactory` reads and reloads `buildDefaults` under a private
  key (`['factory-build', id]`), shows only the error after a failed re-read, and the registry goes blank while it reads
  its repositories again (`!isFetching` in `loaded`). `gcTime: 0` survived: the cache is dropped a tick after unmount,
  after the test has opened the page again; a private key would also break `replace`. Expected CI: exactly the three new tests from Task 1 fail.
- [ ] **Step 2: Revert commit.**
- [ ] **Step 3: Docs.** CLAUDE.md live-data bullet: the repository batch and that a Factory-tab save refreshes the
  list column and the preparation form through shared keys. Spec status: "… and its repository batch is PR #…".
- [ ] **Step 4: Commit** `Document the repository screens on React Query`.

## Rulings

1. Screens whose effects read nothing (`RepositorySettings`, `PeopleStep`'s lookup guard, the `live` refs) are left as
   they are — no read to move — cost if wrong: none; they stay correct.
2. `onChanged` is deleted rather than kept as a no-op — the shared keys make it redundant, and a counter is what §3.5
   replaces — cost if wrong: a caller outside the registry page would lose its notice (there is none: grep).

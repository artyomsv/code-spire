# Live data step 6 (sixth batch): the remaining read-once screens on React Query — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Analytics (overview, repository lens, my activity), the operator sign-in options (`ConnectOptions`), the
register-a-pull-request dialog's repository list (`RegisterPrDialog`), the people policy editor (`ActorPicker`) and the
webhook page's serving-account lookups (`useServingAccounts`) read through `useQuery`. Analytics' hand-written
`useLoaded` hook goes.

**Architecture:** Same pattern as the earlier batches (PR #357–#361): one key per read, beside its REST function; a
cached answer is shown at once; a failed re-read keeps the answer and shows its error. The dialog and the policy
editor's scope list read `repositoriesKey`, the key the repositories page and the profiles screen already share.

**Tech Stack:** React 19, `@tanstack/react-query` 5, vitest + Testing Library (CI only).

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` (§3.5, §5 step 6). Earlier batches: PR #357–#361.

## Global Constraints

- Plain `useQuery`/`useQueries` with the existing REST function; actions re-read with `invalidateLive` and write known
  results with `writeLive`/`updateLive` (`liveCache.ts`).
- "Answered" means `data !== undefined`; a failed re-read keeps the data and shows its error beside it.
- A composed query function is written in the component, calling the module exports, so tests' `vi.spyOn` applies.
- Tests render inside the query cache (`renderWithQuery`, `test/queryWrapper.tsx`). `TEST-` placeholders only. No local
  build, test or lint.
- Commits: imperative, ≤ 72 chars, no AI attribution, no close/fix/resolve keyword before an issue number.

## Scope

In: `Analytics.tsx` (`AnalyticsOverview`, `AnalyticsRepo`, the my-activity page), `ConnectOptions`, `RegisterPrDialog`
(the repository list only), `ActorPicker`, `useServingAccounts`.

Out (rulings):
1. `useMe`: the session read that decides login and roles before anything renders; `App` acts on its first answer
   (login redirect, return route, service sessions). It stays a one-shot read.
2. `useTicketTitles`: forge reads, throttled to three at a time across pages and never stored (ADR-043); the spec keeps
   forge-backed reads out of the cache's re-read paths (`FORGE_READS`).
3. `RegisterPrDialog`'s URL resolution: a debounced answer to typing, not server state.
4. `HarnessSubscriptionSignIn` and `FindingConversation`, as ruled in PR #360 and #361.

## Decisions this plan makes

- **Keys** (beside the REST functions): `analyticsOverviewKey = ['analytics', 'overview']`,
  `analyticsReposKey = ['analytics', 'repos']`, `analyticsRepoKey(workspace, slug) = ['analytics', 'repo', workspace,
  slug]`, `myActivityKey = ['my-activity']` (`api.ts`); `connectablePlatformsKey = ['connectable-platforms']` (`api.ts`);
  `actorsKey(path) = ['actors', path]` (`actorsApi.ts`); `servingAccountsKey(repositoryId) = ['serving-accounts',
  repositoryId]` (`api.ts`). The dialog and the scope list reuse `repositoriesKey`.
- **Analytics.** `useLoaded` is replaced by a small adapter from a query to the existing `LoadState` (`loading` until
  answered, `error` only while nothing is answered, else `ready`), so the `Lens`/table code is unchanged. A failed
  re-read with data keeps `ready` (the page has no error slot beside data; the screens are read-once summaries).
- **ConnectOptions.** `useQuery(connectablePlatformsKey)`; the same three renderings.
- **RegisterPrDialog.** `useQuery(repositoriesKey)`; a failed read with no data shows the existing message.
- **ActorPicker.** `useQuery(actorsKey(path))`. "Reload people and refresh names" calls `fetchActors(path, true)` and
  writes its answer with `writeLive`; a save awaits `invalidateLive(actorsKey(path))`. The policy's `revision` is sent
  back on save, so Save waits for a read made on this visit (`isFetchedAfterMount && isSuccess`), as in PR #359. The
  scope list reads `repositoriesKey` once the operator asks for it (`enabled` after "choose scope"), filtered in render.
  A path change keeps resetting the draft and resolution (the existing effect, minus the read).
- **useServingAccounts.** `useQueries` over the distinct repository ids, one key each; the returned
  `Record<string, ServingLookup>` keeps its shape (`{ data }` or `{ error }`, absent while loading). An unchanged id keeps
  its answer when the set changes (no re-read and no "loading…" flash for it).

## Review Focus

1. A people-policy save must never send a revision read on an earlier visit.
2. "Reload people and refresh names" must show the refreshed names without a second plain read replacing them.
3. The serving-account chips must not flash back to "loading…" for ids already answered when a row is added.
4. The dialog must not break the repositories page's shared entry (same query function, same shape).
5. Analytics must show a cached summary at once when the page opens again.

---

### Task 1: Failing tests

**Files:** `Analytics.test.tsx`, `ConnectOptions.test.tsx`, `RegisterPrDialog.form.test.tsx`, `ActorPicker.test.tsx`,
`RepositoryDetail.test.tsx` (renders `ActorPicker`), `useServingAccounts.test.tsx`, and any other test that renders these
without a provider, move to `renderWithQuery` (or `renderHook` with a `queryWrapper`).

- [ ] **Step 1: Write the failing tests.**
  - Analytics overview, my activity, ConnectOptions, ActorPicker: `'shows … at once when it opens again'`. RED.
  - ActorPicker: `'saves only from a read made on this visit'` (reopen with a never-settling read: Save disabled). RED.
  - ActorPicker: `'shows the refreshed names after Reload'` (the refresh answer is on screen; `fetchActors` called with
    `refresh = true` once and no plain read after it). Guard.
  - useServingAccounts: `'keeps an answered id when another is added'` (two ids → three ids: the first two are fetched
    once each). RED if the current hook re-reads every pair.
- [ ] **Step 2: Push; draft PR; CI fails** on exactly the RED tests.
- [ ] **Step 3: Commit** `Test the remaining read-once screens on React Query (red)`.

### Task 2: Implementation

- [ ] **Step 1:** keys and the five readers as decided.
- [ ] **Step 2: Push; CI green.**
- [ ] **Step 3: Commit** `Read the remaining read-once screens through React Query`.

### Task 3: Mutation check and docs

- [ ] **Step 1: Mutation commit** (reverted next): ActorPicker's Save gate drops `isFetchedAfterMount`. Expected: exactly
  the save-gate test fails.
- [ ] **Step 2: Revert** with `git revert --no-edit <sha>`; check the code equals GREEN before the next commit.
- [ ] **Step 3: Docs.** CLAUDE.md live-data bullet and the spec status line: step 6 done, with the reads that stay
  one-shot and why.
- [ ] **Step 4: Commit** `Document the remaining read-once screens on React Query`.

## Changes after the plan check (they override the sections above)

1. **The serving-accounts test changes its contract.** `useServingAccounts.test.tsx` "every pair is re-asked" (5 calls)
   becomes "an answered id is not asked again" (3 calls); that rewrite is the RED test. The hook's doc comment changes too.
2. **The ActorPicker save-gate test is a guard**, not RED: Save is already disabled until the read answers. It exists for
   the Task 3 mutant.
3. **Remove is gated too.** It sends a revision; Remove and Save are disabled until a read made on this visit answered.
   The mutant drops the gate from both.
4. **One error slot.** ActorPicker and RegisterPrDialog show `actionError ?? readError` in their existing alert, with
   the existing text (`String(error)` in ActorPicker; the dialog's fixed sentence), so a read failure and an action
   failure never make two alerts.
5. **The scope list re-reads on each click.** "Choose repository" sets `enabled` and calls `invalidateLive(client,
   repositoriesKey)`, as `chooseScope` re-fetched each click; cached repositories fill the select at once.
6. **Analytics keeps a summary beside a failed re-read**, with a test (`'keeps the summary when a re-read fails'`).
7. **Wording of ruling 2.** Tracker reads are cached elsewhere (`workKeys`) and only skipped on notices; `useTicketTitles`
   stays because of its throttle (three forge reads at a time across pages) and because titles are never stored.

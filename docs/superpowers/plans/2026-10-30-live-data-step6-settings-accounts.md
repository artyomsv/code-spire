# Live data step 6 (third batch): account and connection settings on React Query — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Settings → Accounts (`SettingsProviders`), Settings → Webhooks (`SettingsWebhookRepos`) and Settings → Context
(`SettingsContextProviders`, with its form's account list) read through `useQuery`; their saves and deletes re-read
through `invalidateLive` instead of a hand-written `load()`.

**Architecture:** Accounts and webhooks reuse the keys the repositories page reads (`providersKey`, `webhookReposKey`,
PR #358), so a webhook created from a repository panel is on the Webhooks page without a second read, and the other way
round. Context providers get `contextProvidersKey`. The accounts and context pages check each account's connection
after every completed read of their list; a small hook, `useEachRead`, runs that check once per completed read and never
for a cache write (a scope result written into the accounts list must not start another round of checks).

**Tech Stack:** React 19, `@tanstack/react-query` 5, vitest + Testing Library (CI only).

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` (§3.5, §5 step 6). Earlier batches: PR #357, PR #358.

## Global Constraints

- Plain `useQuery` with the existing REST function; actions re-read with `invalidateLive` (cancel, then invalidate) and
  write known rows with `writeLive`/`updateLive` (spec §3.5, `liveCache.ts`, the PR #358 review).
- "Answered" means `data !== undefined`; a failed re-read keeps the data and shows its error (PR #357 lesson).
- Tests render inside `test/queryWrapper.tsx`. `TEST-` placeholders only. No local build, test or lint.
- Commits: imperative, ≤ 72 chars, no AI attribution, no close/fix/resolve keyword before an issue number.

## Scope

Batch A of the settings area: `SettingsProviders`, `SettingsWebhookRepos`, `SettingsContextProviders` (page and form).
Batch B, a later PR: `SettingsGeneral`, `SettingsLlmProviders`, `SettingsMemory`, `SettingsOperators` (with
`ScmConnections`, which renders inside it), `SettingsDlq`, `SettingsHarnessCredentials`, `HarnessSubscriptionSignIn`.

## Decisions this plan makes

- **Keys.** Reused: `providersKey`, `webhookReposKey` (`api.ts`). New: `contextProvidersKey = ['context-providers']`
  next to `fetchContextProviders` in `api.ts`.
- **`hooks/useEachRead.ts`**: `useEachRead<T>(query: { data: T | undefined; isFetching: boolean; status: string },
  effect: (data: T) => void): void`. Calls `effect(data)` when the query goes from fetching to not fetching with
  `status === 'success'`, once per completed read. A cache write (`setQueryData`) does not toggle `isFetching`, so it
  never triggers; a failed read does not either. The effect is held in a ref, so the caller need not memoise it.
- **Accounts page.** The list is `useQuery(providersKey)`. After each completed read: drop the connection results of
  accounts no longer enabled (the existing comment's rule) and check every enabled account, as `load()` did. A check
  that returns scopes writes them into the list with `updateLive(providersKey, …)`; that write does not start new
  checks. Save and delete call `invalidateLive(providersKey)`. The page shows "Loading…" until the list answered, the
  error in place of the table while nothing is on screen, and the error above the table when a re-read fails after a
  success (before, the error always replaced the table).
- **Webhooks page.** `useQuery(webhookReposKey)`; save and delete `invalidateLive`. Errors as on the accounts page.
- **Context page.** `useQuery(contextProvidersKey)`; checks after each completed read for accounts that are enabled
  and set, as `load()` did. `act()` and the form's `onSaved` await `client.invalidateQueries` after a cancel
  (`invalidateLive` then `await client.refetchQueries({ queryKey, exact: true })`, or the awaited equivalent) so a
  caller that awaited `load()` still waits for the re-read; an action's own error stays local.
- **Context form's accounts.** `useQuery(providersKey)` — the accounts page's read, shared.

## Review Focus

1. A connection check must run once per account per completed read, never in a loop driven by its own scope write.
2. Disabling an account must drop its old green result (the existing comment's rule) on the next read.
3. A failed re-read after a success must not blank a list.
4. Save then immediate close (form unmounted) must still leave the list re-read.
5. A webhook created on the repositories page must appear on the Webhooks page through the shared key.

---

### Task 1: Failing tests

**Files:** `hooks/useEachRead.test.tsx` (new); `SettingsProviders.accounts.test.tsx`, `SettingsProviders.form.test.tsx`,
`SettingsWebhookRepos.form.test.tsx`, `SettingsWebhookRepos.serving.test.tsx`, `SettingsContextProviders.form.test.tsx`,
`SettingsContextProviders.usedby.test.tsx` (each `renderPage` gets `{ wrapper }`, a fresh `queryWrapper()` by default).

- [ ] **Step 1: Write the failing tests.**
  - `useEachRead`: `'runs once per completed read, not for a cache write or a failed read'` — a component with
    `useQuery` on a counting `queryFn` and `useEachRead` counting calls; after the first read 1; `setQueryData` → still 1;
    `invalidateQueries` → 2; a rejecting read → still 2. RED: the module does not exist.
  - Accounts: `'shows the accounts at once when the page opens again'` (render, wait for a row, unmount, make
    `fetchProviders` never settle, render with the same wrapper → the row is shown). RED.
  - Accounts: `'checks each enabled account once per read, not again when a check writes its scopes'` — the check
    answers `scopesCheckedAt`; after it settles, `checkProvider` (the check function the page calls) has run once per
    enabled account. Guard: passes before too; its mutant (use `dataUpdatedAt` as the trigger) must fail it.
  - Webhooks: `'shows the webhooks at once when the page opens again'`. RED.
  - Context: `'shows the context providers at once when the page opens again'`. RED.
- [ ] **Step 2: Push; open a draft PR; CI fails** on exactly the four RED tests (the hook test fails to import).
- [ ] **Step 3: Commit** `Test the account and connection settings on React Query (red)`.

### Task 2: Implementation

- [ ] **Step 1:** `useEachRead`, the key, and the three screens as decided.
- [ ] **Step 2: Push; CI green**, all existing tests in the six files unchanged apart from the wrapper.
- [ ] **Step 3: Commit** `Read the account and connection settings through React Query`.

### Task 3: Mutation check and docs

- [ ] **Step 1: Mutation commit** (reverted next): `useEachRead` triggers on every `dataUpdatedAt` change instead of a
  completed fetch, and the accounts page counts as loaded only while not fetching (`!isFetching`). Expected: exactly the
  hook test, the scope-loop guard and the accounts reopen test fail. The mutant must compile (an unused import fails
  TypeScript, and then no test runs).
- [ ] **Step 2: Revert.**
- [ ] **Step 3: Docs.** CLAUDE.md live-data bullet: the settings batch A and `useEachRead`. Spec status line.
- [ ] **Step 4: Commit** `Document the account and connection settings on React Query`.

## Rulings

1. `ScmConnections` moves to batch B: it renders inside `SettingsOperators` — cost if wrong: none, one more screen later.

## Changes after the plan check (they override the sections above)

1. **`useEachRead(key, effect)`** subscribes to the QueryCache in a layout effect (before the observer's first fetch
   starts) and runs `effect(data)` on each `updated` event for that exact key whose action is a non-manual `success`,
   or an `error` while the query still holds data (the last good list: without it, enabled rows would stay on
   "Checking…" after a failed re-read). A manual write (`setQueryData`) and a cancel (`setState`) never trigger it.
   The trigger is render-independent: a read that settles between renders is still seen.
2. **Scope results are written with `mergeLive`**, not `updateLive`: cancelling would discard a read started by a save.
   The server stores the scopes, so a later read carries them anyway.
3. **`invalidateLive` returns `client.invalidateQueries(...)`**: it resolves when the re-read settles and never rejects.
   `act()` and the context form's `onSaved` await it. No second `refetchQueries` (it would cancel and re-send).
4. Tests: the hook test builds its own `createQueryClient()` + provider and writes *different* data for the manual-write
   case (structural sharing keeps an equal write's reference). Its mutant: "fire on every `updated` event".
5. `useEditDeepLink` gets a module-level empty array while loading, not a fresh `[]` per render.
6. Wording: a webhook created on the repositories page is shown at once on the Webhooks page, then re-read.
   `refetchOnReconnect` stays on; a reconnect re-reads the list and re-checks the enabled accounts.

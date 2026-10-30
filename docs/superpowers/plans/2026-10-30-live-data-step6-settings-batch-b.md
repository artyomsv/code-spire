# Live data step 6 (fourth batch): the other settings screens on React Query — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Settings → General, LLM, Memory, People (`SettingsOperators` with `ScmConnections`), Dead-letter and Harness
credentials read through `useQuery`; their actions re-read through `invalidateLive` or write the known result with
`writeLive`/`updateLive`, instead of a hand-written `load()`/`reload()`.

**Architecture:** Same pattern as settings batch A (PR #359): one shared key per read, declared in `api.ts` beside the
REST function; a cached list is shown at once, and anything that sends a row's fields back (an Edit form, a deep link)
waits for a read made on this visit (`isFetchedAfterMount && isSuccess`). The LLM page reads models through the key the
preparation form and the build step already use (`preparationKeys.llmModels()`), so a model saved on the LLM page
reaches them with no callback. The LLM page's connection checks run through `hooks/useEachRead.ts`, as the accounts
page's do.

**Tech Stack:** React 19, `@tanstack/react-query` 5, vitest + Testing Library (CI only).

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` (§3.5, §5 step 6). Earlier batches: PR #357, #358,
#359.

## Global Constraints

- Plain `useQuery` with the existing REST function; actions re-read with `invalidateLive` and write known rows with
  `writeLive`/`updateLive` (`liveCache.ts`).
- "Answered" means `data !== undefined`; a failed re-read keeps the data and shows its error beside it (PR #357).
- A form fills only from a read made on this visit (`isFetchedAfterMount && isSuccess`, PR #357 M-1); a cached row may
  be shown, not edited (PR #359 M-2).
- Tests render inside `test/queryWrapper.tsx`. `TEST-` placeholders only. No local build, test or lint.
- Commits: imperative, ≤ 72 chars, no AI attribution, no close/fix/resolve keyword before an issue number.

## Scope

`SettingsGeneral`, `SettingsLlmProviders` (page; its two forms keep their props), `SettingsMemory`,
`SettingsOperators`, `ScmConnections`, `SettingsDlq`, `SettingsHarnessCredentials`.

Out of scope (ruling 1): `HarnessSubscriptionSignIn`. Its 2 s poll watches a vendor device sign-in that only the
server can see; there is no push channel for it, and moving it means a server socket. It stays as it is.

## Decisions this plan makes

- **Keys** (`api.ts`, next to each REST function):
  - `generalSettingsKey = ['general-settings']`, `queryFn` = `Promise.all` of review, conversation and cap settings,
    returning `{ review, conversation, caps }` (caps in wire form). One key: the page has one Save and one load.
  - `llmProvidersKey = ['llm-providers']`; models reuse `preparationKeys.llmModels()` (`['llm-models']`). `api.ts`
    must not import from `components/`; the LLM page imports `preparationKeys` from `work-items/workQueries.ts`.
  - `memoryKey = ['memory']`.
  - `operatorsKey = ['operators']`, `queryFn` = `Promise.all` of identities, candidates and seen operators, returning
    `{ links, candidates, operators }` (all three, or an error, as today).
  - `scmOAuthAppsKey = ['scm-oauth-apps']`.
  - `dlqKey = ['dlq', 'pending']` (`getDlqEntries(true)`).
  - `harnessCredentialsKey = ['harness-credentials']`.
- **General.** The editable copies (`review`, `conversation`, `caps`) and `loaded` stay component state. They fill once,
  from the first read that is `isFetchedAfterMount && isSuccess` (an effect guarded by a "filled" ref). A save writes
  what the server stored into `generalSettingsKey` with `writeLive` (so a reopened page shows it) and sets the local
  copies as today. A later re-read (reconnect) never overwrites the operator's typing.
- **LLM.** Providers and models are two `useQuery`s. "Loading…" until both answered; the error shown above the tables
  while data exists, in place of them when not. Connection checks: `useEachRead(llmProvidersKey, ...)` checks each
  enabled provider after each completed read, and drops the results of providers no longer enabled. `current` =
  both reads `isFetchedAfterMount && isSuccess`; Edit and Delete on both tables are disabled until `current`, with the
  batch A note; `useEditDeepLink(current ? providers : NO_PROVIDERS, ...)`. `act()` and both forms' `onSaved` await
  `Promise.all([invalidateLive(llmProvidersKey), invalidateLive(models key)])`.
- **Memory.** `useQuery(memoryKey)`; `decide` and `rescan` await `invalidateLive(memoryKey)`. The action error stays
  local and separate from the read error.
- **People.** `useQuery(operatorsKey)`; link and unlink await `invalidateLive(operatorsKey)`. The form fields stay
  local.
- **ScmConnections.** `useQuery(scmOAuthAppsKey)`; save and delete re-read with `invalidateLive`. The row's edit
  toggle (it opens a form filled from the row) is disabled until a read made on this visit answered.
- **Dead-letter.** `useQuery(dlqKey)`; replay and discard remove their row with `updateLive(dlqKey, rows =>
  rows.filter(...))`, as `removeEntry` did. No re-read on an action (the server just removed the row).
- **Harness credentials.** `useQuery(harnessCredentialsKey)`; `reload(message)` sets the notice and calls
  `invalidateLive`; the `refresh` counter goes. Actions are by id and send no row fields, so they are not gated.

## Review Focus

1. A General save then a reconnect re-read must not overwrite what the operator typed after the save.
2. An LLM provider check must run once per enabled provider per completed read (the batch A guard, on this page).
3. A model saved on the LLM page must reach the preparation form's model list through the shared key.
4. A Dead-letter replay must remove the row even when the page's read is still out (the write cancels it).
5. A failed re-read on any of these screens keeps the last list on screen with its error.

---

### Task 1: Failing tests

**Files:** each existing test file of the seven screens renders inside `queryWrapper` (a fresh one by default);
`SettingsDlq.test.tsx` is new.

- [ ] **Step 1: Write the failing tests** — one reopen test per screen: render, wait for a TEST- row or value, unmount,
  make the read never settle, render again with the same wrapper; the row is shown at once (General: the page shows
  "Loading…" again and fills nothing until the read answers — this one passes before too and guards the fill rule;
  its mutant is "fill from any `isSuccess`").
  - LLM: `'shows the providers at once when the page opens again, with Edit off until this visit's read answers'`.
  - LLM: `'checks each enabled provider once per read'` (guard; mutant: drop the `!action.manual` test is batch A's,
    here: run the checks from a plain effect on `data`).
  - Memory, People, Sign-in applications (`ScmConnections`), Dead-letter, Harness credentials: `'shows … at once
    when the page opens again'`.
  - Dead-letter: `'replay removes the row'` (passes before; guards `updateLive`).
- [ ] **Step 2: Push; open a draft PR; CI fails** on exactly the RED reopen tests.
- [ ] **Step 3: Commit** `Test the remaining settings screens on React Query (red)`.

### Task 2: Implementation

- [ ] **Step 1:** the keys and the seven screens as decided.
- [ ] **Step 2: Push; CI green**, every existing test in the changed files unchanged apart from the wrapper.
- [ ] **Step 3: Commit** `Read the remaining settings screens through React Query`.

### Task 3: Mutation check and docs

- [ ] **Step 1: Mutation commit** (reverted next): General fills from any `isSuccess`; the LLM page counts as current
  without `isFetchedAfterMount`. Expected: exactly the General fill guard and the LLM Edit-gating test fail. The mutant
  must compile.
- [ ] **Step 2: Revert.**
- [ ] **Step 3: Docs.** CLAUDE.md live-data bullet: settings batch B, and the sign-in poll that stays. Spec status line.
- [ ] **Step 4: Commit** `Document the remaining settings screens on React Query`.

## Rulings

1. `HarnessSubscriptionSignIn` keeps its poll: it watches a vendor flow with no push channel; a server socket is a
   separate change — cost if wrong: one screen still polls every 2 s while a sign-in is open.
2. General uses one key for its three reads: the page loads and saves them as one — cost if wrong: a later screen that
   reads one group alone needs its own key.

## Changes after the plan check (they override the sections above)

1. **Keys only in `api.ts`.** The `Promise.all` query functions for General and People are written in the component,
   which calls the `api` exports. A call inside `api.ts` to its own function would bypass the tests' `vi.spyOn`.
2. **ScmConnections fills its form when it opens.** The four field states move into a child rendered only while the row
   is open, so they start from the current `app`. Disabling the toggle until this visit's read answers stays.
3. **The LLM check guard re-reads.** After the first read, `invalidateLive` runs again with the same list, and
   `checkLlmProvider` must be called twice per enabled provider. A plain effect on `data` misses a deep-equal re-read
   (structural sharing keeps the reference), so that mutant fails the guard.
4. **Read errors keep `role="alert"`.** Memory and Harness credentials show `actionError ?? read.error` in the
   existing alert paragraph, as the context page does.
5. **General writes nothing into the cache on save.** The form never fills from the cache, so a write would buy
   nothing. This also removes the wire/field conversion problem with caps.
6. **Dead-letter re-reads after its row write.** Replay and discard first remove the row with `updateLive` (tolerating
   `undefined`), then `void invalidateLive(dlqKey)`, because the write cancels a read that is already out.
7. **People defaults.** `candidates` and `operators` are `read.data?.… ?? []`; `links` stays `null` until the read
   answers.

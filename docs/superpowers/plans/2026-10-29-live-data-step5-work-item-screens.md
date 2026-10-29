# Live data step 5: work-item screens on the change feed — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The work-item list, detail and draft read through React Query and re-read only when `/api/ws/work-items`
says something changed; `workPolling.ts` and every work-item poll timer are deleted.

**Architecture:** One hook, `useWorkItemChanges`, opens the work-item socket through `useLiveSocket` and turns each
`{"changed":[ids]}` / `{"changed":"*"}` frame (and every open) into re-reads of the matching work-item queries. A new
cache helper, `noticeLive`, does the re-read so that a burst of notices never starts a second read beside one still out
and never cancels a read for ever: a read already out finishes, then one more read follows. Every work-item read gets
a query key under `['work-items', …]` from one module, so a notice can name exactly the queries it concerns.

**Tech Stack:** React 19, `@tanstack/react-query` 5, vitest + Testing Library (CI only).

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` (§3.2, §3.3, §4.3, §5 step 5, §6).

## Global Constraints

- No screen polls for server data (spec §2). After this step no `setInterval`/`setTimeout` re-reads a work item.
- Sockets open only through `hooks/useLiveSocket.ts`; frames reach the cache only through `liveCache.ts` (CLAUDE.md).
- Change notice → re-read over REST for those ids; `'*'` → all of them (spec §3.3).
- `onOpen` re-reads every query the socket feeds: the server sends nothing on open (spec §3.3, §4.3).
- Tests render inside `test/queryWrapper.tsx`. jsdom has no `WebSocket`: every test that renders a work-item screen
  stubs it.
- Test data uses `TEST-` placeholders only (no-synthetic-data rule).
- No local build, test or lint: CI runs them (no-local-builds rule). "Run" below means "push and read the CI log".
- Commits: imperative, first line ≤ 72 chars, no AI attribution, no close/fix/resolve keyword before an issue number.

## Decisions this plan makes

- **Query keys** (`components/work-items/workQueries.ts`):
  `['work-items','list',filterId,offset]`, `['work-items','item',id]`, `['work-items','tracker',id]`,
  `['work-items','draft',id,version]`, `['work-items','steps',id]`.
- **What a notice re-reads.** For each named id: that item, its draft (every version) and its steps; plus every list
  page (a change to any item can move it between filters and changes the counts). `'*'` and every socket open: all
  `work-items` queries **except `tracker`**. The tracker read asks the forge for the ticket text; no database trigger
  covers it, and re-reading it on every notice would spend forge rate limit. It is still re-read by "Refresh workflow"
  and after an action, as today.
- **Draft and steps are re-read on an item notice** even when the item's events do not change: the draft carries the
  ticket comment's state (`work_tracker_outbox`) and the steps carry `work_step_summary`, and both tables have item
  triggers for exactly this (step 4, spec §4.2).
- **A panel open on the detail page closes the socket** (`enabled: !panel`). Today a poll never runs under an open
  panel, because a re-read under a decision the operator reads would move what they approve. When the panel closes the
  socket reopens and its `onOpen` re-reads, so nothing that changed meanwhile is lost. The list keeps its socket while
  its decision panel is open, as it keeps polling today: `DecisionPanel` reads its own copy.
- **"Live updates paused".** With no poll, a dropped socket would leave the screen silently stale. Both screens show a
  `chip warn` "Live updates paused" while the socket status is `down`.
- **Out of scope:** `DecisionPanel` keeps its own reads (it is a snapshot by design); `useTicketTitles` keeps its
  bounded title reads; the per-row tracker titles are not re-read on notices.

## Review Focus

1. **A burst of notices while a read is slow** (a build writing several tables): the screen must still show an answer;
   expected exactly one read out at a time and one read after the burst. Pinned in Task 1 (`noticeLive` tests) and
   Task 3 (`reads the item once more after a burst, never two at once`).
2. **Socket down after a deploy:** the screen must say it is not live rather than show old data as current. Pinned in
   Task 2 and Task 3 (`says live updates are paused while the feed is down`).
3. **A change while a panel is open:** it must appear when the panel closes. Pinned in Task 3
   (`reads nothing while a panel is open, and reads again when it closes`).
4. **A failed read** keeps the last item on screen with the error beside it, and the next notice clears it (PR 349).
   Pinned in Task 3.
5. **A notice for another item** must not re-read this one. Pinned in Task 3.

---

### Task 1: The feed hook and the notice helper

**Files:**
- Create: `spire-ui/src/test/fakeSocket.ts` (move `RunSocket`'s class body here as `FakeSocket`)
- Modify: `spire-ui/src/test/liveRuns.ts` (`export { FakeSocket as RunSocket } from './fakeSocket';`, class removed)
- Modify: `spire-ui/src/liveCache.ts` (add `noticeLive`)
- Create: `spire-ui/src/components/work-items/workQueries.ts`
- Create: `spire-ui/src/components/work-items/useWorkItemChanges.ts`
- Test: `spire-ui/src/liveCache.test.tsx`, `spire-ui/src/components/work-items/useWorkItemChanges.test.tsx`

**Interfaces:**
- Produces:
  - `noticeLive(client: QueryClient, filters: QueryFilters): void` in `liveCache.ts`
  - `workKeys` in `workQueries.ts`:
    `all = ['work-items'] as const`, `list(filterId: string, offset: number)`, `lists()` = `['work-items','list']`,
    `item(id: string)`, `tracker(id: string)`, `draft(id: string, version: string)`, `drafts(id: string)` =
    `['work-items','draft',id]`, `steps(id: string)`
  - `useWorkItemChanges(options?: { enabled?: boolean }): LiveSocketState` in `useWorkItemChanges.ts`
  - `FakeSocket` (same API as today's `RunSocket`: `sockets`, `latest`, `open()`, `push(value)`, `raw(data)`, `closed`)

- [ ] **Step 1: Write the failing tests**

`liveCache.test.tsx` (add; a real `QueryClient` from `createQueryClient()`, queries observed with `client.fetchQuery`
or a mounted `useQuery`):
- `noticeLive re-reads an idle query a screen shows` — read count goes 1 → 2.
- `noticeLive lets a read already out finish, then reads once more` — first read is a deferred promise; call
  `noticeLive` three times; assert the read function was called once while deferred; resolve it; await; assert exactly
  2 calls and the second answer is the data.
- `noticeLive leaves a query that matches nothing alone` — `{ queryKey: ['work-items','item','TEST-b'] }` does not
  re-read `['work-items','item','TEST-a']`.

`useWorkItemChanges.test.tsx` (stub `WebSocket` with `FakeSocket`; seed queries with `client.setQueryData` and spy with
`client.invalidateQueries` is NOT enough — observe re-reads through mounted `useQuery` hooks with counting `queryFn`s):
- `opens /api/ws/work-items` — `FakeSocket.latest.url` ends with `/api/ws/work-items`.
- `re-reads the named item, its draft, its steps and every list page` — push `{changed:['TEST-a']}`; item/draft/steps
  of `TEST-a` and two list pages re-read; item of `TEST-b` and tracker of `TEST-a` do not.
- `re-reads everything but the tracker on '*'` — push `{changed:'*'}`.
- `re-reads everything but the tracker on every open` — `FakeSocket.open()` twice → two re-reads of an item.
- `ignores a frame that is not a change notice` — push `{changed: 5}` and `[]`; no re-read.
- `opens no socket while disabled` — `{ enabled: false }` → `FakeSocket.sockets` empty.

- [ ] **Step 2: Push the RED commit (tests + empty shells that compile) and read CI**

Shells: `noticeLive` with an empty body, `useWorkItemChanges` returning `{ status: 'connecting', closeReason: null }`.
Expected: `dashboard` job FAILS on the new tests only, every other test green.

- [ ] **Step 3: Implement `noticeLive`**

```text
for each query in client.getQueryCache().findAll(filters):
  if query.state.fetchStatus !== 'fetching': client.invalidateQueries({ queryKey: query.queryKey, exact: true })
  else if query is not already waiting (module WeakSet):
    mark it waiting; subscribe to the query cache;
    on the first event for this query with fetchStatus 'idle': unsubscribe, unmark, invalidate it (exact)
```
Comment why: `invalidateQueries` cancels a read that is out and starts another, so a stream of notices faster than the
read would never show an answer; a read that started before the change may miss it, so one more read must follow.

- [ ] **Step 4: Implement `workKeys` and `useWorkItemChanges`**

`useWorkItemChanges` calls `useLiveSocket({ path: '/api/ws/work-items', enabled, onFrame, onOpen })`. A frame whose
`changed` is `'*'`, and every open, call `noticeLive(client, { queryKey: workKeys.all, predicate: q => q.queryKey[1] !== 'tracker' })`.
A frame whose `changed` is a string array calls `noticeLive` for `workKeys.lists()` and, per id, `workKeys.item(id)`,
`workKeys.drafts(id)`, `workKeys.steps(id)`. Anything else is ignored.

- [ ] **Step 5: Push GREEN and read CI** — Expected: `dashboard` green.

---

### Task 2: The list on React Query and the feed

**Files:**
- Modify: `spire-ui/src/components/work-items/WorkItems.tsx`
- Test: `spire-ui/src/components/work-items/WorkItems.test.tsx`

**Interfaces:**
- Consumes: `workKeys.list`, `workKeys.lists`, `useWorkItemChanges`, `FakeSocket` (Task 1)

- [ ] **Step 1: Write the failing tests; change the existing ones**

- In `beforeEach`: `FakeSocket.sockets = []; vi.stubGlobal('WebSocket', FakeSocket);`. Every `render(` of a work-item
  screen in the file passes `{ wrapper: queryWrapper() }` (the helper `showDetail` too).
- Delete `re-reads the list on a timer without blanking the rows`, `re-reads a list of finished items only every 15
  seconds`, `never starts a poll while a read is still out`.
- Add:
  - `re-reads the list when the feed names a change, without blanking the rows` — first answer `TEST-0`; open; push
    `{changed:['TEST-item-0']}`; `getWorkItems` called again; `TEST-0` stays; `Updated …` shown.
  - `re-reads the list when the feed reconnects` — open, then a second socket's open after `onclose` → one more read.
  - `never reads the list on a timer` — fake timers, advance 60 s, `getWorkItems` called once.
  - `says live updates are paused while the feed is down` — `FakeSocket.latest.onclose({code:1006, reason:''})` with
    `fetchMe` resolving a session and `ensureServiceSessions` resolving `false` (no sign-in started) → text
    `Live updates paused`.
- The existing race tests (`ignores an older list response/error after refresh`), `says the list is stale when a
  re-read fails, and keeps the rows`, the refresh/scan tests and `says it is refreshing while the list reloads` stay
  unchanged and must pass.

- [ ] **Step 2: Push RED and read CI** — Expected: the four new tests fail; the rest pass.

- [ ] **Step 3: Rewrite the list's state**

`useQuery({ queryKey: workKeys.list(filter.id, offset), queryFn: () => getWorkItems(offset, PAGE_SIZE, filter.statuses) })`
replaces `loaded`, `error`, `loading`, `updatedAt`, `sequence`, `inFlight`, `latest`, `refresh` and both timer effects.
`updatedAt` = `dataUpdatedAt` (when non-zero); `error` = the query error's text; the button's busy state =
`isFetching || scanning`. "Refresh work items" and `onDecided` call
`client.invalidateQueries({ queryKey: workKeys.lists() })`. Call `useWorkItemChanges()` and show the paused chip when its
status is `down`. Remove the `workPolling` import.

- [ ] **Step 4: Push GREEN and read CI** — Expected: `dashboard` green.

---

### Task 3: The detail on React Query and the feed; delete `workPolling.ts`

**Files:**
- Modify: `spire-ui/src/components/work-items/WorkItemDetail.tsx`
- Delete: `spire-ui/src/components/work-items/workPolling.ts`
- Test: `spire-ui/src/components/work-items/WorkItems.test.tsx`

**Interfaces:**
- Consumes: `workKeys.item`, `workKeys.tracker`, `useWorkItemChanges`, `FakeSocket`

- [ ] **Step 1: Write the failing tests; change the existing ones**

- Delete `reads the open work item again on its own`, `reads a finished work item again only every 15 seconds`,
  `drops a poll answer that a later refresh overtook`, `shows the item from a poll that overtook a slow first load`.
- Add (each opens the socket with `FakeSocket.open()` after the first read lands):
  - `reads the item again when the feed names it` — push `{changed:['TEST-item-0']}` → 2 reads.
  - `ignores a notice for another item` — push `{changed:['TEST-item-9']}` → still 1 read.
  - `does not ask the tracker again on a notice` — `getWorkItemTracker` stays at 1 after `{changed:'*'}`.
  - `reads the item once more after a burst, never two at once` — second read deferred; push three notices; 2 calls
    while deferred; resolve; exactly 3 calls; the newest answer on screen.
  - `shows a failed read beside the item until a later read succeeds` (PR 349) — reads: ok `TEST-first read`, reject
    `TEST-read failed`, ok `TEST-later read`; driven by two notices; alert shown with the first item still on screen,
    then alert gone and `TEST-later read` shown.
  - `shows the item after a failed first load once the feed names it` — first read rejects; alert; notice; item shown,
    alert gone.
  - `reads nothing while a panel is open, and reads again when it closes` — render with `?prepare=1`: the socket is
    closed (`FakeSocket.latest.closed` or no socket); close the panel; the new socket opens; one more `getWorkItem`.
  - `says live updates are paused while the feed is down`.
  - `never reads the item on a timer` — fake timers, advance 60 s, 1 read.
- Unchanged and must pass: `ignores an older workflow/tracker response/error after navigation`, `keeps the item and an
  open panel on screen while the page re-reads`, `shows a failed re-read beside the item it could not refresh`,
  `never commits one item under another item address`, the notice/recheck tests.

- [ ] **Step 2: Push RED and read CI** — Expected: the new tests fail; the rest pass.

- [ ] **Step 3: Rewrite the detail's reads**

Two queries: `workKeys.item(id)` → `getWorkItem(id)` and `workKeys.tracker(id)` → `getWorkItemTracker(id)`. They
replace `state`, `tracker`, `reads`, `shown`, `refresh`, the read effect and the poll effect. The page shows the
item only when `data.id === id` (keep the guard and its comment). An error shows beside a kept item.
`reread(message)` sets the notice and invalidates both keys of this id. The action-number logic (`action`, `start`,
`changed`) stays. `useWorkItemChanges({ enabled: !panel })`, paused chip when `down`. Delete `workPolling.ts`; nothing
else imports it (`isFinished` has no other caller — grep to confirm before deleting).

- [ ] **Step 4: Push GREEN and read CI** — Expected: `dashboard` green.

---

### Task 4: Draft and plan steps on React Query

**Files:**
- Modify: `spire-ui/src/components/work-items/DraftArtifact.tsx`, `spire-ui/src/components/work-items/WorkItemSteps.tsx` (`PlanSteps`)
- Create: `spire-ui/src/components/work-items/DraftArtifact.test.tsx`
- Test: `spire-ui/src/components/work-items/WorkItemSteps.test.tsx`

**Interfaces:**
- Consumes: `workKeys.draft`, `workKeys.drafts`, `workKeys.steps`, `useWorkItemChanges`, `FakeSocket`

- [ ] **Step 1: Write the failing tests**

`DraftArtifact.test.tsx` — the four PR 349 tests as written there (`reads one draft version once for both parts`,
`reads the draft again when the generation writes a newer artifact`, `shows nothing for a newer write until its own
read answers`, `asks the server nothing before the generation writes a draft`), plus:
- `reads the draft again when the feed names its item` — render `DraftArtifact` beside a component that calls
  `useWorkItemChanges()`; open; push `{changed:['TEST-item']}`; `workDraft` called twice; the comment state from the
  second answer is shown.

`WorkItemSteps.test.tsx` — every render gets `{ wrapper: queryWrapper() }` and the `FakeSocket` stub; add:
- `reads the plan steps once per item` — a stepped item rendered, rerendered with a new `phase`: `workSteps` called once
  (the item moving no longer re-reads; the notice does).
- `reads the plan steps again when the feed names the item` — open; push `{changed:[id]}` → 2 calls.

- [ ] **Step 2: Push RED and read CI** — Expected: `reads one draft version once for both parts`, both feed tests and
  `reads the plan steps once per item` fail.

- [ ] **Step 3: Implement**

`DraftArtifact`: `useQuery({ queryKey: workKeys.draft(item.id, version ?? ''), queryFn: () => workDraft(item.id), enabled: version !== null })`;
show nothing while `version === null` or there is no data for that key (a key per version means an older version's
answer never stands in). A failed read shows nothing, as today. Update the component comment: the "no React Query"
sentence goes. `PlanSteps`: `useQuery({ queryKey: workKeys.steps(item.id), queryFn: () => workSteps(item.id), enabled: stepped })`,
rows = `data?.rows ?? []`.

- [ ] **Step 4: Push GREEN and read CI** — Expected: `dashboard` green.

---

### Task 5: Docs, mutation check, live check

**Files:**
- Modify: `CLAUDE.md` (Conventions, the live-data bullet: work-item screens now on the feed, "no screen polls" true)
- Modify: `docs/superpowers/specs/2026-10-28-live-data-design.md` (§1 table: work items move from "Polling" to
  "WebSocket push"; step 5 delivered)
- Modify: `docs/UNVERIFIED.md` (step 4 entry: the screens now depend on the feed; a dropped feed shows "Live updates
  paused" and re-reads on reconnect)

- [ ] **Step 1: Docs commit.**
- [ ] **Step 2: Mutation check in CI** — one commit makes `noticeLive` call `invalidateQueries` straight away for a
  fetching query (drop the wait branch). Expected: exactly the `noticeLive lets a read already out finish…` and
  `reads the item once more after a burst…` tests fail. A second commit removes `enabled: !panel`. Expected: exactly
  `reads nothing while a panel is open…` fails. Revert both in the next commit.
- [ ] **Step 3: Live check on the dev stack** (rebuild `spire-ui` from the branch): open a work item on `spire-test`
  and the list; in the network panel, `/api/work-items/{id}` is requested only after a socket frame (plus once on open);
  a database change to the item gives one frame and one read; stopping the orchestrator shows "Live updates paused",
  starting it re-reads. No synthetic rows (a real item, or `TEST-` rows removed in the same session).

# Live data step 1: foundation + runs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add React Query and one shared socket hook to the dashboard, and move the runs list and run detail onto
them, deleting their hand-written socket and race code.

**Architecture:** `QueryClientProvider` wraps the app in `main.tsx`. `useLiveSocket` owns the socket lifecycle
(connect, parse, close handling, reconnect). Cache helpers in `liveCache.ts` write socket frames into React Query,
cancelling any in-flight REST read of the same key first. `useLiveRuns` and `useRunDetail` keep their public return
shapes, so `Runs.tsx` and `RunDetail.tsx` do not change.

**Tech Stack:** React 19.3, TypeScript 7, `@tanstack/react-query` 5.104.5, vitest 5, @testing-library/react 16.

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` (§3.1–§3.3, §5 step 1, §6). Read it first.

## Global Constraints

- **No builds, tests or linters on the host** (`no-local-builds.md`). Every "run" step below is a CI run on the PR.
  The order is fixed: write all tests (Tasks 1–5) → commit RED → push → read the failing CI run → implement →
  commit GREEN → push → read the green run. Report both run ids.
- One PR for this step, on branch `feat/live-data-runs`. Do not open a second PR (operator rule, 2026-10-28).
- `@tanstack/react-query` pinned exactly at `5.104.5` (`--save-exact`; published 2026-10-19, older than the 7-day
  cooldown in `.github/dependabot.yml`).
- Query client defaults: `retry: false`, `refetchOnWindowFocus: false` (spec §3.1).
- Reconnect delay stays 1500 ms. Socket path for runs stays `/api/ws/runs`.
- Test data uses `TEST-` values only (`no-synthetic-data.md`); reuse `runRow`/`runView` from `src/test/liveRuns.ts`.
- TypeScript: `interface` for object shapes; 2-space indent; match the surrounding comment style (why, not what).
- Commits: imperative, ≤72-char subject, body; no AI attribution; never a closing keyword before an issue number.

## Review Focus

1. **A socket frame lands while the REST snapshot is still out** — the frame must win and the late REST answer or
   failure must be dropped. Pinned by Task 3's helper tests and the existing `useLiveRuns` tests.
2. **`/api` signed in, `/gw` lapsed** — the shared hook must start the gateway sign-in, not report down or loop.
   Pinned in Task 2 (no screen in this step uses `/gw`, but the hook is shared from now on).
3. **React StrictMode double mount** — the first socket is disposed and its frames ignored. Existing
   `useLiveRuns` test 'ignores a disposed StrictMode socket' must stay green.
4. **Navigating from one run detail to another** — a push for the previous run must not refetch or show it.
   Pinned in Task 5.
5. **Unmount with a reconnect pending** — no socket opens after unmount. Existing `useLiveRuns` test 'cancels a
   pending reconnect…' plus Task 2's unmount test.

---

### Task 1: Query client, provider and test wrapper

**Files:**
- Modify: `spire-ui/package.json`, `spire-ui/package-lock.json` (`npm install --save-exact @tanstack/react-query@5.104.5 --ignore-scripts`; installing is allowed on the host)
- Create: `spire-ui/src/queryClient.ts`
- Modify: `spire-ui/src/main.tsx` (wrap `<HashRouter>` in `<QueryClientProvider client={createQueryClient()}>`)
- Create: `spire-ui/src/test/queryWrapper.tsx`
- Create test: `spire-ui/src/queryClient.test.ts`
- Modify tests: `spire-ui/src/App.routes.test.tsx` (5 `render` calls), `spire-ui/src/App.archived.test.tsx` (1) — add `{ wrapper: queryWrapper() }`

**Interfaces:**
- Produces: `createQueryClient(): QueryClient`; `queryWrapper(): (props: { children: ReactNode }) => JSX.Element` — one fresh client per call, so a wrapper survives `rerender` and the next test gets an empty cache.

- [ ] **Step 1: Write the failing test** `queryClient.test.ts`:
  `it('retries nothing and does not re-read on window focus')` — `const defaults = createQueryClient().getDefaultOptions().queries;` `expect(defaults?.retry).toBe(false)`; `expect(defaults?.refetchOnWindowFocus).toBe(false)`.
- [ ] **Step 2: Add the wrapper to the App tests** (they render `<App />`, which will render runs screens needing a provider).
- [ ] **Step 3: Implement** `queryClient.ts`, `test/queryWrapper.tsx`, `main.tsx`. Comment on `createQueryClient` says why: no screen retried before; a live screen is kept fresh by its socket, not by focus.

### Task 2: `useLiveSocket`

**Files:**
- Create: `spire-ui/src/hooks/useLiveSocket.ts`
- Create test: `spire-ui/src/hooks/useLiveSocket.test.tsx`
- Modify: `spire-ui/src/test/liveRuns.ts` — `RunSocket` gains `onopen: (() => void) | null = null` and `static open()` that calls `act(() => RunSocket.latest.onopen?.())`

**Interfaces:**
- Consumes: `fetchMe`, `needsLogin`, `goToFullLogin`, `ensureServiceSessions`, `isLeavingForAuth` from `src/auth.ts`.
- Produces (exactly the spec §3.2 contract):

```ts
export interface LiveSocketOptions {
  path: string;
  onFrame: (frame: unknown) => void;
  onOpen?: () => void;
  onClose?: (close: { code: number; reason: string }) => 'reconnect' | 'stop';
  enabled?: boolean;            // default true
}
export interface LiveSocketState {
  status: 'connecting' | 'live' | 'down' | 'refused';
  closeReason: string | null;
}
export function useLiveSocket(options: LiveSocketOptions): LiveSocketState;
export const RECONNECT_MS = 1500;
```

Callbacks are read through refs (the `useRunsSocket` pattern), so a new `onFrame` each render does not reopen the
socket; the effect depends on `path` and `enabled` only. URL: `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${path}`. Close order (spec §3.2): `onClose` → `fetchMe`/`needsLogin` → `goToFullLogin`; else `await ensureServiceSessions()` → true stops; else status `down` and reconnect after `RECONNECT_MS`. `onerror` closes the socket. Every step re-checks "unmounted or replaced" and `isLeavingForAuth()`.

- [ ] **Step 1: Write the failing tests** (stub `WebSocket` with `RunSocket`; mock the five auth functions; `ensureServiceSessions` resolves `false` unless stated):
  - `'opens wss for https and ws for http on the given path'`
  - `'passes each parsed frame and drops one that is not JSON'` — `RunSocket.raw('<html>')` calls `onFrame` zero times; `RunSocket.push([1])` once with `[1]`
  - `'calls onOpen on every open and reports live'` — `RunSocket.open()` twice across a reconnect → `onOpen` called 2×; `status` `'live'`
  - `'checks the session, then reconnects after 1500 ms and reports down'` — fake timers; after close: `fetchMe` 1×, `status` `'down'`, 1499 ms → 1 socket, +1 ms → 2 sockets
  - `'goes to login without reconnecting when the session is gone'`
  - `'starts a lapsed service sign-in instead of reporting down'` — `fetchMe` signed in, `ensureServiceSessions` resolves `true` → `status` not `'down'`, no second socket after 5000 ms, `goToFullLogin` not called
  - `'stays closed with the reason when onClose says stop'` — `onClose` returns `'stop'` for code 1008 → `status` `'refused'`, `closeReason` `'TEST-no such run'`, `fetchMe` not called, no reconnect after 5000 ms
  - `'ignores frames from a replaced socket'` — after reconnect, a frame on `RunSocket.sockets[0]` does not reach `onFrame`
  - `'closes on unmount and opens nothing after a pending reconnect'`
  - `'opens nothing while disabled'` — `enabled: false` → zero sockets
- [ ] **Step 2: Implement** `useLiveSocket.ts` to the interface above.

### Task 3: Cache helpers

**Files:**
- Create: `spire-ui/src/liveCache.ts`
- Create test: `spire-ui/src/liveCache.test.tsx`

**Interfaces:**
- Produces:

```ts
/** Cancels any in-flight read of `key`, then replaces its data. */
export function writeLive<T>(client: QueryClient, key: QueryKey, data: T): void;
/** Cancels any in-flight read of `key`, then updates its data (undefined when nothing is cached yet). */
export function updateLive<T>(client: QueryClient, key: QueryKey, update: (previous: T | undefined) => T): void;
/** Marks `key` stale and re-reads it if a screen shows it (React Query's invalidateQueries). */
export function invalidateLive(client: QueryClient, key: QueryKey): void;
/** Replaces the row with the same id, or appends it. Pure. */
export function upsertBy<T>(rows: readonly T[], row: T, idOf: (row: T) => string): T[];
```

Both writers call `void client.cancelQueries({ queryKey: key, exact: true })` **before** `setQueryData`. Verified on
`query-core` 5.104.5: `cancel` reverts the query synchronously (`query.ts` `onCancel` → `setState(#revertState)`),
so the frame written next is not overwritten, and the cancelled fetch's late result or rejection is ignored.

- [ ] **Step 1: Write the failing tests** (`renderHook(() => useQuery({ queryKey: ['TEST-key'], queryFn }), { wrapper })`, with access to the wrapper's client — expose it via a small test-local wrapper built on `createQueryClient()`):
  - `'keeps a frame written while the REST read is out, and drops the late answer'` — queryFn returns a pending promise; `writeLive(client, key, ['TEST-frame'])`; resolve REST with `['TEST-rest']` → data stays `['TEST-frame']`
  - `'drops a late REST failure after a frame'` — same, REST rejects → `error` null, data `['TEST-frame']`
  - `'clears an earlier read error when a frame arrives'` — REST fails first (error shown), then `writeLive` → `error` null
  - `'updates from undefined when nothing is cached'` — `updateLive` on an empty key receives `undefined`
  - `'re-reads an invalidated key that a screen shows'` — queryFn called 2× after `invalidateLive`
  - `upsertBy`: replaces a matching id in place; appends an unseen id; leaves the input array unchanged
- [ ] **Step 2: Implement** `liveCache.ts`.

### Task 4: Runs list on React Query

**Files:**
- Modify: `spire-ui/src/useLiveRuns.ts`
- Modify test: `spire-ui/src/useLiveRuns.test.tsx` — every `renderHook` gets `{ wrapper: queryWrapper() }`; the StrictMode case nests StrictMode inside the query wrapper
- Modify test: `spire-ui/src/components/Runs.test.tsx` — its 2 `render` calls get the wrapper

**Interfaces:**
- Consumes: `useLiveSocket` (Task 2), `writeLive`, `updateLive`, `upsertBy` (Task 3), `isRunEntry` (move it from `hooks/useRunsSocket.ts` into `useLiveRuns.ts`, since Task 5 deletes that file).
- Produces: unchanged `useLiveRuns(): { runs: RunListEntry[]; loading: boolean; error: string | null }`; `export const MAX_LIVE_RUNS = 200`; `export const RUNS_KEY = ['runs', 'live'] as const`; `export function isRunEntry(value: unknown): value is RunListEntry`.

Query: key `RUNS_KEY`, queryFn `getRuns({ limit: MAX_LIVE_RUNS })` filtered by `isRunEntry` and passed through the
existing `sortRuns`. Socket `/api/ws/runs`: an array frame → `writeLive(sortRuns(valid rows))`; an object frame
that `isRunEntry` → `updateLive(previous => sortRuns(upsertBy(previous ?? [], row, r => r.runId)))`; anything else
ignored. `loading` = no data and no error. `error` = the query error's message, or null while
`isLeavingForAuth()`. Delete `wsDelivered` and the hand-written effect.

- [ ] **Step 1: Update the tests** — wrapper only. All existing cases stay as they are; they are the contract.
- [ ] **Step 2: Implement** `useLiveRuns.ts`.

### Task 5: Run detail on React Query; delete `useRunsSocket`

**Files:**
- Modify: `spire-ui/src/hooks/useRunDetail.ts`
- Delete: `spire-ui/src/hooks/useRunsSocket.ts`
- Modify test: `spire-ui/src/components/RunDetail.test.tsx` — `show()` and the list-link test get the wrapper; add the test below

**Interfaces:**
- Consumes: `useLiveSocket`, `invalidateLive`, `isRunEntry` (from `useLiveRuns.ts`).
- Produces: unchanged `useRunDetail(runId: string): { run: RunView | null; loading: boolean; error: string | null }`; `export const runKey = (runId: string) => ['run', runId] as const`.

Query: key `runKey(runId)`, queryFn `getRun(runId)`. Socket `/api/ws/runs`: an array frame (a reconnect snapshot,
which may not contain an older run) or a row with `runId === runId` → `invalidateLive(client, runKey(runId))`;
other rows ignored. The query is keyed on `runId`, so the sequence counter and `active` ref go.

- [ ] **Step 1: Write the failing test** in `RunDetail.test.tsx`:
  `it('does not re-read or show the previous run after moving to another run')` — render at run A, wait for it, navigate (a `Link`) to run B with `getRun` answering B; push a row for A → `getRun` call count unchanged and A's status text absent.
- [ ] **Step 2: Implement** `useRunDetail.ts`; delete `useRunsSocket.ts`; `grep -rn useRunsSocket spire-ui/src` returns nothing.

### Task 6: RED, GREEN and the live check

- [ ] **Step 1: RED commit.** Contents: every test change from Tasks 1–5, plus the test infrastructure they need
  to compile against — the dependency, `queryClient.ts`, `test/queryWrapper.tsx`, the `RunSocket.onopen` addition.
  No change to `main.tsx`, `useLiveRuns.ts`, `useRunDetail.ts`, `useRunsSocket.ts`, and no `useLiveSocket.ts` or
  `liveCache.ts`. Push, open the PR as a draft (`gh pr create --draft`), read the dashboard job.
  Expected: `useLiveSocket.test.tsx` and `liveCache.test.tsx` fail (modules missing); the new RunDetail navigation
  test fails; `queryClient.test.ts` and every pre-existing test pass with the wrapper.
- [ ] **Step 2: GREEN commit.** Tasks 2–5 production code and `main.tsx`. Push. Expected: dashboard job green, all
  other jobs green. If a pre-existing runs test fails, the implementation is wrong, not the test.
- [ ] **Step 3: Mutation checks** (in a scratch branch pushed only if needed, or reasoned and recorded in the PR):
  remove the `cancelQueries` call in `writeLive` → `'keeps a frame written while the REST read is out…'` fails;
  remove the `ensureServiceSessions` step → `'starts a lapsed service sign-in…'` fails.
- [ ] **Step 4: Live check on the dev stack built from the branch:**
  `docker compose -p spire-dev -f docker-compose.yml -f docker-compose.dev.yml -f docker-compose.idp.yml -f docker-compose.auth.yml up -d --build --no-deps ui`
  Open `/runs` and one run's detail. Expected: rows load; the browser shows one open `/api/ws/runs` socket per
  screen; restarting the orchestrator shows the list again after the
  socket reconnects, with no error left on screen. The orchestrator container is `spire-orchestrator-dev`
  (`docker restart spire-orchestrator-dev`).
- [ ] **Step 5:** Mark the PR ready, set `review: requested`, and stop for the operator's review and word to merge.

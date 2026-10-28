# Live data step 2: reviews — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move the reviews list (`useLiveReviews`) onto React Query, `useLiveSocket` and `liveCache`, deleting its
hand-written socket client and its `wsDelivered` race flag.

**Architecture:** Two queries: live rows (`['reviews', 'live']`, fed by REST and `/api/ws/reviews`) and archived rows
(`['reviews', 'archived']`, REST only, enabled while "Show archived" is checked). The hook keeps its `LiveReviews`
return shape, so `App.tsx` does not change.

**Tech Stack:** as step 1 (React 19.3, `@tanstack/react-query` 5.104.5, vitest 5).

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` §3.3 (snapshot + rows), §5 step 2. Step 1's
`useLiveSocket`, `liveCache` and test wrapper are the base (`CLAUDE.md`, "One way for the dashboard to get server data").

## Global Constraints

- No builds or tests on the host; CI only, RED commit then GREEN commit, both run ids reported.
- One PR, branch `feat/live-data-reviews`. No second PR.
- `TEST-` data only. 2-space indent, `interface` for shapes, comments say why.

## Review Focus

1. **Archived rows survive a reconnect snapshot** — the socket's array frame replaces live rows only. Existing test
   'keeps archived rows through a socket snapshot that carries only live work'.
2. **A late REST snapshot or failure after a frame** — dropped. Existing tests 'ignores a REST snapshot…' and
   'ignores a REST failure…'.
3. **Expired session on close** — goes to the chained login, no reconnect. Existing test 'sends the operator to a login…'.
4. **Unchecking "Show archived" then checking it again** — archived rows return; while unchecked none show. Existing
   test 'fetches archived rows only once asked…'.
5. **An archived read that fails** — reported as an error, not as "no archived reviews". New test.

---

### Task 1: Tests (RED)

**Files:** `spire-ui/src/useLiveReviews.test.tsx`

- Every `renderHook(() => useLiveReviews())` gets `{ wrapper: queryWrapper() }`.
- The fake socket's `onclose` calls pass a browser-shaped close event `{ code: 1006, reason: '' }` (the shared hook
  reads `event.code`; a browser always passes one). The fake gains `onopen` like `RunSocket`.
- New test `'reports a failed archived read instead of showing no archived rows'`: live rows load; the
  `includeArchived=true` URL rejects with `TEST-archive unavailable`; after checking "Show archived", `error` is
  `'TEST-archive unavailable'` and the live rows stay.
- New test `'opens the reviews feed through the shared socket hook'`: after a close, `ensureServiceSessions` is
  consulted before the reconnect (spy on `auth.ensureServiceSessions`, resolve `false`; assert called once and a second
  socket opens after 1500 ms). Fails until the hook uses `useLiveSocket`.

### Task 2: `useLiveReviews` on React Query (GREEN)

**Files:** `spire-ui/src/useLiveReviews.ts`

**Interfaces:**
- Consumes: `useLiveSocket` (`hooks/useLiveSocket.ts`), `writeLive`, `updateLive`, `upsertBy` (`liveCache.ts`).
- Produces: unchanged `useLiveReviews(): LiveReviews`; `export const REVIEWS_KEY = ['reviews', 'live'] as const`;
  `export const ARCHIVED_REVIEWS_KEY = ['reviews', 'archived'] as const`.

Live query: `fetchReviews()` → `isReviewSummary` filter → `sortReviews`. Archived query: `fetchReviews(true)` →
rows with `archivedAt !== null`, `enabled: showArchived`. Socket `/api/ws/reviews`: array → `writeLive(sorted valid)`;
`{ removed }` → `updateLive(filter it out)`; summary → `updateLive(sort(upsertBy(prev ?? [], row, r => r.id)))`.
`loading` = no live data and no live error. `error` = live error message, else archived error message, null while
`isLeavingForAuth()`. `reviews` = live rows, merged and sorted with archived rows while `showArchived`.
Delete the hand-written effects, `wsDelivered` and `upsert`.

### Task 3: Evidence and review

- RED commit → push → draft PR → read the dashboard job: the two new tests fail, the rest pass.
- GREEN commit → push → read: green.
- Live check on the dev stack built from the branch: the Reviews page lists live rows; "Show archived" adds archived
  rows; an orchestrator restart (`docker restart spire-orchestrator-dev`) brings the list back with no error.
- Update `CLAUDE.md` (the "One way…" bullet: reviews moved). Mark ready, `review: requested`, stop for the operator.

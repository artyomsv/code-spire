# Live data step 3: attention and transcript — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move the last two hand-written socket clients — the attention bell (`hooks/useAttention.ts`, two feeds) and
the run transcript (`hooks/useRunTranscript.ts`) — onto `useLiveSocket` and React Query, so every socket in the
dashboard opens through one hook.

**Architecture:** Attention: one cache entry per feed (`['attention', 'orchestrator' | 'gateway']`), written only by
the socket (no REST, `queryFn: skipToken`). Transcript: one entry per run (`['run-transcript', runId]`); the REST page
and the socket frames both **merge** into it, because events are immutable and a late REST page holds older events
the socket snapshot may not (spec §3.4). Both hooks keep their return shapes, so `AttentionBell` and
`RunEventStream` do not change.

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` §3.2–§3.4, §5 step 3. The timeline socket
(`/api/ws/timeline`) has no client in the dashboard (`grep -rn "new WebSocket" spire-ui/src`), so there is nothing to move.

## Global Constraints

- No builds or tests on the host; CI only, RED commit then GREEN commit, both run ids reported.
- One PR, branch `feat/live-data-sockets`. No second PR.
- `TEST-` data only. 2-space indent, `interface` for shapes, comments say why.

## Review Focus

1. **A feed that has not answered yet** — claims nothing (no false BLOCKING row). Existing test 'shows no badge while
   the sockets are still connecting'.
2. **A dropped feed** — shows its UNAVAILABLE row and keeps the other feed's rows. Existing drop tests.
3. **A late REST transcript page after live events** — merged, not dropped and not replacing. Existing test 'merges a
   late REST page…'.
4. **1008 on the transcript** — terminal: shows the reason, ignores a late REST page, never reconnects. Existing test.
5. **A REST transcript failure after live events** — not shown over a live tail. New test.

---

### Task 1: `mergeLive` (liveCache)

`export function mergeLive<T>(client: QueryClient, key: QueryKey, update: (previous: T | undefined) => T): void` —
`setQueryData` **without** cancelling: for an append-only stream whose late REST page must be merged. Test
`'merges into an entry whose REST read is still out, and the read keeps it'`: a queryFn that, on resolve, merges
with `client.getQueryData(key)`; `mergeLive` during the read; resolve → data holds both.

### Task 2: attention on `useLiveSocket`

`useAttention(): { items: AttentionItem[] }` unchanged. Per feed: `useQuery({ queryKey, queryFn: skipToken })`;
`useLiveSocket({ path, onFrame: array → writeLive(rows), onClose: resetQueries(key) and 'reconnect' })`. Feed state:
socket `down` → UNAVAILABLE row; else rows when the cache holds a list; else nothing. Tests: `AttentionBell.test.tsx`
renders inside `queryWrapper()`; its fake socket closes with a browser-shaped event.

### Task 3: transcript on `useLiveSocket`

`useRunTranscript(runId)` unchanged in shape. Query `['run-transcript', runId]`, queryFn = REST page merged with the
cached entry. Socket `/api/ws/runs/transcript?runId=<encoded>`: array frame → `mergeLive(merge(prev, own events))`.
`onClose`: 1008 → cancel the REST read and `'stop'`; else `'reconnect'`. `error`: the refusal reason when refused;
else the REST error only while no events are held. Tests: wrapper, StrictMode via `reactStrictMode`, stub
`ensureServiceSessions`; new `'keeps a live tail when the REST page fails after it'` and
`'asks about a lapsed service session before reconnecting the transcript'` (RED: the old hook never asks).

### Task 4: Evidence, docs, review

RED → GREEN in CI; live check (run detail transcript loads; attention bell shows the gateway row while the gateway is
stopped and clears when it returns: `docker stop spire-gateway-dev`, then `docker start`); CLAUDE.md "One way…"
bullet: every socket now opens through `useLiveSocket`; fresh whole-branch review; mark ready.

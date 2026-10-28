# Live data: one way for the dashboard to get server data

Status: **draft for review** (2026-10-28). Nothing here is built yet.

## 1. Problem

The dashboard gets server data in three different ways, and each screen implements its way by hand.

| Way | Screens | Code |
|---|---|---|
| WebSocket push | Reviews list, runs list and run detail, run transcript, attention bell, review timeline | `useLiveReviews.ts`, `useLiveRuns.ts` + `hooks/useRunsSocket.ts`, `hooks/useRunTranscript.ts`, `hooks/useAttention.ts`, timeline in `api.ts` |
| Polling on a timer | Work-item list (5 s / 15 s), work-item detail (3 s / 15 s) | `WorkItems.tsx`, `WorkItemDetail.tsx`, `workPolling.ts` |
| One REST read on open | Every other screen (settings, repositories, policies, analytics…) | `useEffect` + `useState` per screen |

Measured on master `a88e83fd`:

- **The socket client is written four times.** `useLiveReviews`, `useRunsSocket`, `useRunTranscript` and
  `useAttention` each open the socket, parse frames, ask `fetchMe()` why it closed (an expired session closes
  it with no useful code), and reconnect after 1.5 s. The copies already differ in small ways.
- **The REST-versus-socket race is guarded by hand, twice.** `useLiveReviews` and `useLiveRuns` load a REST
  snapshot and open the socket at the same time, and a `wsDelivered` flag stops the slower REST answer from
  replacing a newer socket frame.
- **Work items have no socket.** The orchestrator has five socket endpoints (`ws/AttentionSocket`,
  `ReviewsSocket`, `RunsSocket`, `RunTranscriptSocket`, `TimelineSocket`) and none carries work items. So
  every open work-item page asks the server every 3 s, and the hand-written poll carries its own race guards
  (read counters, "one poll at a time", "drop a poll a refresh overtook"; review of PR #179).
- **Read-once screens repeat the same state code.** 38 component files call the API module directly, most from a
  `useEffect` with its own loading, error and "ignore a late answer" handling.

A dashboard maintained this way gets a new variant of the same bug on every screen. The operator's rule
(2026-10-28): **one transport, used the same way everywhere.**

## 2. Decision

- **WebSocket for push** on every screen whose data changes while it is open. No screen polls for server data.
- **React Query** (`@tanstack/react-query`) is the one place the dashboard keeps server data: loading and error
  state, caching, sharing one read between components, and dropping late answers. Socket frames write into it.
- **One shared socket hook** replaces the four hand-written clients.

React Query and the socket do different jobs: the socket says *that* something changed (or carries the new
row); React Query holds *what* the screen shows. Neither replaces the other.

Rejected: **sockets only, no library.** It keeps one dependency fewer, but every screen keeps its own cache,
loading state and race guards, which is the code that keeps breaking.

## 3. Client design

### 3.1 One query client

`spire-ui/src/queryClient.ts` creates the client; `main.tsx` wraps the app in `QueryClientProvider` above the
router. Defaults: `retry: false` (no screen retries today, and a live screen gets the next frame anyway),
`refetchOnWindowFocus: false` (a live screen is kept fresh by its socket, not by focus). Tests get a fresh
client per test through `src/test/queryWrapper.tsx`.

### 3.2 One socket hook: `useLiveSocket`

`spire-ui/src/hooks/useLiveSocket.ts`:

```ts
interface LiveSocketOptions {
  /** Same-origin path, e.g. '/api/ws/runs' or '/gw/ws/webhook-attention'. */
  path: string;
  /** Called once per parsed JSON frame. */
  onFrame: (frame: unknown) => void;
  /** Called on every successful open, the first one included. */
  onOpen?: () => void;
  /** False closes the socket and stops reconnecting (e.g. a screen that is hidden). */
  enabled?: boolean;
}
export function useLiveSocket(options: LiveSocketOptions): { status: 'connecting' | 'live' | 'down' };
```

It owns, once: building the `ws:`/`wss:` URL; JSON parsing (a bad frame is dropped); ignoring frames from a
socket it has already replaced; on close, the `fetchMe()` check → `goToFullLogin()` when the session is gone,
otherwise reconnect after 1.5 s; doing nothing while `isLeavingForAuth()`; closing on unmount. The `status`
lets a screen say "live updates paused" (the attention bell already needs this as its `down` state).

### 3.3 Three ways a frame updates the cache

Every live screen uses one of these three, through small helpers next to the hook:

| Kind | Frame | Cache action | Used by |
|---|---|---|---|
| **Snapshot + rows** | array = whole list, object = one row (or `{removed: id}`) | `setQueryData`: replace, or upsert/remove by id | reviews, runs, timeline |
| **Whole value** | the full current value each time | `setQueryData`: replace | attention |
| **Change notice** | `{changed: [ids]}` or `{changed: '*'}` | `invalidateQueries` for those ids, so React Query reads them again over REST | work items (new) |

**Rule for the race the hooks guard by hand today:** before a frame writes into a query, the helper calls
`cancelQueries` for that key. An in-flight REST read is then dropped, so an older REST answer can never replace a
newer frame. This replaces both `wsDelivered` flags, and one test pins it for the shared helper.

**Rule for missed changes:** a socket that was down missed frames. `onOpen` therefore invalidates every query
the socket feeds (for snapshot sockets the server's open snapshot does this already).

### 3.4 Run transcript

The transcript is a stream, not a list of rows: the open frame is the newest page, then events append. It moves
onto `useLiveSocket` for the transport; its frames append to the query's data with `setQueryData`. Paging back
stays a REST read.

### 3.5 Read-once screens

Screens with no live data use plain `useQuery` with their existing REST function, and actions call
`invalidateQueries` instead of bumping a `refresh` counter. They move screen by screen when touched; no big-bang
rewrite.

## 4. Server design: a work-item change feed

### 4.1 Why a notice, not a row

The work-item detail is assembled from many tables at read time (`WorkItemResource.get`: the event store, the
work source, `work_run_effect`, `work_land_effect`, `work_item_preparation_attempt`, operator names), and it
decrypts land detail for the reader. Pushing it would rebuild it for every change and every subscriber. A notice
carries only the item id; the browser reads the detail it is showing over the existing REST endpoint, with the
existing authorization. The list works the same way: a notice re-reads the page and filter the operator is on.

### 4.2 Detecting changes: PostgreSQL triggers + LISTEN/NOTIFY

The tables that feed a work-item view (`work_item`, `work_item_gate`, `work_run_effect`, `work_verify_effect`,
`work_delivery_effect`, `work_land_effect`, `work_item_preparation_attempt`) are written by **14 Java files in
about 60 statements** (counted on `a88e83fd`; `work_land_effect` alone has 17). An explicit `push(id)` after each
write would have to be added at every one of them, and the first writer someone adds later without it makes a page
silently stale.

Events need no trigger of their own: `WorkItemStore.append` upserts the `work_item` row in the same transaction as
every event it appends (`project(...)`), so the `work_item` trigger fires for each event.

So the database says what changed:

- A Flyway migration (`V104__work_item_change_notify.sql`) adds one trigger function and an `AFTER INSERT OR
  UPDATE OR DELETE … FOR EACH ROW` trigger on each table above. It runs `pg_notify('work_item_changed',
  work_item_id)`.
- Bookkeeping writes that change nothing on screen do not notify. `WorkActivityPoller` stamps
  `work_item.activity_polled_at` every 2 minutes per item, and `WorkItemStore` sets `slot_reserved`; the
  `work_item` trigger is declared `UPDATE OF` the columns the view shows, so those two do not wake every open page.
- PostgreSQL delivers a notification only when the writing transaction **commits**, and folds identical
  notifications inside one transaction into one. A rolled-back write sends nothing, which is the same "after
  commit" rule `FactoryRunProjection.push` follows by hand.
- `WorkItemChangeListener` (orchestrator, new) holds **one dedicated JDBC connection** outside the Agroal pool,
  runs `LISTEN work_item_changed`, and reads `PGConnection.getNotifications(timeout)` on a virtual thread. It
  collects ids for 250 ms and hands each batch to the broadcaster, so a burst of writes becomes one frame.
- If the listening connection drops, the listener reconnects with back-off and, once listening again, broadcasts
  `{changed: '*'}`: changes made while it was deaf are not lost, every open page reads again.

### 4.3 The socket

`ws/WorkItemsSocket` at `/api/ws/work-items`, with a declared `endpointId` (the `RunTranscriptSocket` lesson:
`findByEndpointId` is an exact match). It sends nothing on open; the client's `onOpen` invalidation reads the
current state. `WorkItemsBroadcaster` sends `{"changed":["<id>",…]}` to every subscriber, never letting one slow
browser delay the others (same send pattern as `RunsBroadcaster`).

Authorization: the socket sits under `/api/ws/*`, which the existing permission policy already requires a
signed-in operator for. A notice holds only ids, and every id is read back through `/api/work-items/{id}`, which
keeps its `@RolesAllowed`. No new data leaves the server through the socket.

### 4.4 Limits

- **One orchestrator replica.** `deploy/helm/spire/templates/orchestrator.yaml` sets `replicas: 1`, and every
  existing broadcaster is in-process too. LISTEN/NOTIFY would work across replicas, but the socket connections
  would not; that is a shared limit of all five sockets, not a new one.
- The listener needs a database connection of its own, so the orchestrator holds one more connection than its
  pool size.

## 5. Delivery plan

Each step is its own pull request, test-first with a RED and a GREEN CI run, a live check on the dev stack and the
reviewer, merged on the operator's word.

1. **Foundation + runs.** React Query, `queryClient.ts`, the test wrapper, `useLiveSocket`, the three cache helpers
   with the `cancelQueries` race rule. Runs list and run detail move onto them; `useRunsSocket` and `useLiveRuns`'
   hand-written race code are deleted. Runs go first because their socket is the simplest snapshot + rows feed.
2. **Reviews.** `useLiveReviews` onto the same pattern; the archived list becomes a separate `useQuery`.
3. **Attention, transcript, timeline.** The remaining socket hooks onto `useLiveSocket`.
4. **Work-item change feed (server).** Migration with the triggers, `WorkItemChangeListener`,
   `WorkItemsBroadcaster`, `WorkItemsSocket`.
5. **Work items (client).** List, detail and draft on `useQuery` + `useLiveSocket`; `workPolling.ts` and every
   poll timer are deleted. The tests written for draft PR #349 (one read at a time, a failed read shown beside the
   item, one draft read shared by both steps) carry over.
6. **Read-once screens**, as they are touched.

Draft PR #349 (React Query on the work-item page, with polling) is closed; its tests move to step 5.

## 6. Testing

- **`useLiveSocket`:** reconnects after a drop; goes to login, not a reconnect, when `fetchMe` says the session is
  gone; ignores frames from a replaced socket; calls `onOpen` on each open; closes on unmount.
- **Cache helpers:** a frame that arrives while the REST snapshot is still out wins, and the late REST answer is
  dropped; upsert, replace and remove; a change notice invalidates exactly the named ids, and `'*'` all of them.
- **Per screen:** the existing screen tests stay green, rendered inside the test wrapper.
- **Server (service tests, Testcontainers Postgres):** a committed write to each listed table produces one
  notice with its work-item id; a rolled-back write produces none; many writes in one transaction produce one;
  a dropped listening connection reconnects and broadcasts `'*'`; a stamp of `activity_polled_at` alone produces
  none. A test derives the tables the work-item read SQL selects from (`WorkItemResource` and the helpers it calls)
  and compares them with the tables that carry the trigger, so a table added to the view without one fails the
  build (the same "derive, do not trust the declaration" approach as `DockerTestsAreSerialisedTest`).
- **Mutation check:** remove one trigger and confirm exactly one test fails; remove the `cancelQueries` call and
  confirm the race test fails.
- **Live, on the dev stack:** a work item on `spire-test` moves from plan gate to build to land while its page is
  open, with no request to `/api/work-items/{id}` except those that follow a notice (checked in the browser's
  network panel and the orchestrator log).

## 7. Open questions for the reviewer

1. Notices for work items, rows for reviews and runs: acceptable as two of the three kinds in §3.3, or should
   reviews and runs also move to notices so the server side has one kind too?
2. Triggers in the database (§4.2) instead of a `push()` call at each Java writer: acceptable?

# Live data: one way for the dashboard to get server data

Status: **steps 1–5 delivered** (PRs #352–#356, 2026-10-29); step 6 moves read-once screens as they are touched,
its work-item batch is PR #357 and its repository batch PR #358.
The tables in §1 describe master `a88e83fd`, before this work.

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
  /**
   * Called on every close before any reconnect. Returning 'stop' makes the close terminal: no reconnect,
   * status 'refused', and the reason kept. Default: 'reconnect'.
   */
  onClose?: (close: { code: number; reason: string }) => 'reconnect' | 'stop';
  /** False closes the socket and stops reconnecting (e.g. a screen that is hidden). */
  enabled?: boolean;
}
interface LiveSocketState {
  status: 'connecting' | 'live' | 'down' | 'refused';
  /** The server's close reason once status is 'refused', e.g. "no such run". */
  closeReason: string | null;
}
export function useLiveSocket(options: LiveSocketOptions): LiveSocketState;
```

It owns, once: building the `ws:`/`wss:` URL; JSON parsing (a bad frame is dropped); ignoring frames from a
socket it has already replaced; doing nothing while `isLeavingForAuth()`; closing on unmount. The `status`
lets a screen say "live updates paused" (the attention bell already needs this as its `down` state).

**On close, in this order** (the union of what the four hooks do today, so no screen loses a behaviour):

1. `onClose` decides first. The run transcript returns `'stop'` for code 1008 (`RunTranscriptSocket` closes a
   tail for a missing run with 1008 "no such run"), so the tail shows the reason and never reopens; a late REST
   answer for that run is ignored, as `useRunTranscript` does today. Its existing 1008 test
   (`useRunTranscript.test.tsx`) moves with it unchanged.
2. `fetchMe()` → `goToFullLogin()` when there is no session at all.
3. `ensureServiceSessions()` → when it starts a sign-in for a lapsed service prefix, stop here. This is the case
   `useAttention` handles today: `/api` is signed in but the `/gw` session expired, so `fetchMe()` succeeds while
   every gateway handshake fails. Without this step the bell would report the gateway down for ever.
4. Otherwise status `down`, and reconnect after 1.5 s.

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

The work-item tables are written by **14 Java files in about 60 statements** (counted on `a88e83fd`;
`work_land_effect` alone has 17), and several tables the screens read change without any item event. An explicit
`push(id)` after each write would have to be added at every one of them, and the first writer someone adds later
without it makes a page silently stale. So the database says what changed.

**What the screens read.** Every GET the work-item screens call (`api.ts` and `components/work-items/*Api.ts`),
traced to its SQL on `a88e83fd`:

| Endpoint | Resource and helpers | Tables |
|---|---|---|
| `GET /api/work-items`, `/{id}` | `WorkItemResource`, `WorkPreparationSweep.health`, `LandSettings`, `WorkSourceRegistry`, `OperatorDirectory` | `work_item`, `event_log`, `work_run_effect`, `work_land_effect`, `work_item_preparation_attempt`, `repository_land_settings`, `repository`, `work_source`, `work_source_actor`, `scm_provider`, `operator_seen` |
| `GET /{id}/draft` | `WorkDraftResource`, `WorkItemStore`, `WorkItemArtifacts`, `WorkSourceEffects.status` | `event_log`, `work_item_artifact`, `work_tracker_outbox`, `work_item` |
| `GET /{id}/steps` | `WorkStepsResource`, `WorkStepSummaries`, `WorkItemArtifacts` | `work_step_summary`, `work_run_effect`, `factory_run.step_index`, `work_item_artifact` |
| `GET /api/approvals` | `WorkGateResource` | `work_item_gate` joined to `work_item` |

`/{id}/tracker` and the preparation reads ask the tracker or the forge, not the database; they are read with the
item and on each action, never on a notice (each read is a call to an external API).

**The dependency map.** Each table gets one of three treatments:

| Treatment | Tables | Notice |
|---|---|---|
| **Item trigger**: the row carries `work_item_id` | `work_item`, `work_item_gate`, `work_run_effect`, `work_land_effect`, `work_item_preparation_attempt`, `work_tracker_outbox`, `work_step_summary`, `work_item_artifact` | that item's id |
| **Shared trigger**: one row affects many items | `repository_land_settings` (merge method, ticket rule and fix allowance shown on every item of the repository), `repository` (name), `work_source` and `work_source_actor` (repository link, allowed people), `scm_provider`, `repository_account` (whether `GET /api/approvals` offers a PR review as an answer; added in step 4) | `'*'`: every open work-item screen reads again. These change only when an operator edits settings |
| **Covered by another trigger** | `event_log`: `WorkItemStore.append` upserts the `work_item` row in the same transaction as every work-item event (`project(...)`). `factory_run.step_index`: set once when a run is dispatched, never updated | none of its own |

Writes that change nothing on screen do not notify. Each UPDATE trigger has a `WHEN` clause that compares the row
without its bookkeeping columns (`to_jsonb(OLD) - <ignored> IS DISTINCT FROM to_jsonb(NEW) - <ignored>`), so a column
added later notifies by default and an UPDATE that changes no value notifies nothing. (The first draft said `UPDATE OF`
the shown columns; step 4 chose the deny-list for those two reasons.) The ignored columns:

- `work_item.activity_polled_at`, stamped by `WorkActivityPoller` (20 items every 30 s), and
  `work_item.slot_reserved`;
- `work_source.health`, `checked_at` and the scan cursor columns, written by `WorkSourceScanner` every 30 s;
- `work_land_effect.next_check_at` and `updated_at` (a re-check deferred with the same reason), and
  `work_tracker_outbox.checked_at` (a failed tracker write deferred);
- `scm_provider`'s health and scope check columns.

One periodic write still notifies: a land that waits for checks re-encrypts its detail every 2 minutes, so its item
gets one notice per pass.

**Accepted staleness, one case.** `operator_seen` is upserted on operator activity and holds only the display name
shown beside a past decision. It gets no trigger: a `'*'` on every sign-in would wake every open page for a name
that almost never changes. A renamed operator shows under the new name at the item's next notice.

So:

- A Flyway migration (`V104__work_item_change_notify.sql`) adds the trigger functions and an `AFTER INSERT OR
  UPDATE OR DELETE … FOR EACH ROW` trigger on each table above. Item triggers run `pg_notify('work_item_changed',
  work_item_id)`; shared triggers run `pg_notify('work_item_changed', '*')`.
- PostgreSQL delivers a notification only when the writing transaction **commits**, and folds identical
  notifications inside one transaction into one. A rolled-back write sends nothing, which is the same "after
  commit" rule `FactoryRunProjection.push` follows by hand.
- `WorkItemChangeListener` (orchestrator, new) holds **one dedicated JDBC connection** outside the Agroal pool,
  runs `LISTEN work_item_changed`, and reads `PGConnection.getNotifications(timeout)` on a virtual thread. It
  collects ids for 250 ms and hands each batch to the broadcaster, so a burst of writes becomes one frame.
- **Listen first, then refresh.** At startup and after every reconnect, the listener runs `LISTEN` on an
  autocommit connection, so the `LISTEN` is committed, and only then broadcasts `{changed: '*'}`. PostgreSQL
  delivers notifications only to a session whose `LISTEN` has committed
  ([LISTEN](https://www.postgresql.org/docs/current/sql-listen.html)), so a refresh sent before it would leave a
  gap in which a change is neither notified nor re-read.
- If the listening connection drops, the listener reconnects with back-off and repeats the step above, so changes
  made while it was deaf are re-read by every open page. A LISTEN session is idle, and a network device that drops an
  idle connection sends no error, so the session uses TCP keep-alive and runs `SELECT 1` after 30 quiet seconds; a
  dead peer fails the probe and the reconnect follows. Any other fault in the loop also reconnects, logged at ERROR.
- The feed is a **refresh signal, not an event history.** A notice never carries data, a missed notice costs one
  re-read, and the REST read is always the truth.

### 4.3 The socket

`ws/WorkItemsSocket` at `/api/ws/work-items`, with a declared `endpointId` (the `RunTranscriptSocket` lesson:
`findByEndpointId` is an exact match). It sends nothing on open; the client's `onOpen` invalidation reads the
current state. `WorkItemsBroadcaster` sends `{"changed":["<id>",…]}` to every subscriber, never letting one slow
browser delay the others (same send pattern as `RunsBroadcaster`).

**Authorization: the socket requires the same roles as the REST reads.** The `/api/*` permission policy in
`application.yml` only requires a signed-in identity (`policy: authenticated`); the roles are enforced per
resource, and `WorkItemResource` requires `spire-viewer` or `spire-admin`. Item ids and the moment an item changes
are data, so a signed-in identity with neither role must not receive them, and a later REST refusal cannot take back
a notice already sent. `WorkItemsSocket` therefore carries `@RolesAllowed({"spire-viewer", "spire-admin"})`, the
same annotation as `WorkItemResource`, so the handshake of an identity without either role is refused before it
receives anything.

### 4.4 Limits

- **Replicas.** `deploy/helm/spire/templates/orchestrator.yaml` sets `replicas: 1` today. This feed does not
  depend on it: every replica runs its own listener, PostgreSQL notifies every listening session, and each replica
  tells its own sockets. The five existing feeds do depend on it, because their writer pushes only to sockets on
  its own replica.
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
  gone; when `/api` is signed in but `/gw` needs a sign-in, starts that sign-in through `ensureServiceSessions` and
  does not report the feed down; an `onClose` that returns `'stop'` leaves the socket closed with status `refused`
  and its reason; ignores frames from a replaced socket; calls `onOpen` on each open; closes on unmount.
- **Run transcript:** the existing 1008 "no such run" test and its late-REST case pass unchanged after the move.
- **Cache helpers:** a frame that arrives while the REST snapshot is still out wins, and the late REST answer is
  dropped; upsert, replace and remove; a change notice invalidates exactly the named ids, and `'*'` all of them.
- **Per screen:** the existing screen tests stay green, rendered inside the test wrapper.
- **Server (service tests, Testcontainers Postgres):**
  - a committed write to each item-trigger table produces one notice with its work-item id, and to each
    shared-trigger table one `'*'`; a rolled-back write produces none; many writes in one transaction produce one;
  - the writes that happen **without an item event** each produce their notice on their own: a tracker comment
    marked sent (`WorkSourceEffects.mark`, `work_tracker_outbox` only), a land settings save
    (`LandSettings`, `repository_land_settings` only), a step summary written, a land effect moving state;
  - bookkeeping alone produces none: a stamp of `activity_polled_at`, a `slot_reserved` change, a scanner health
    or cursor write;
  - at startup and after a dropped listening connection, the `LISTEN` is committed before the `'*'` refresh, and
    a write made between the two is still notified.
- **Coverage check:** a test derives the tables that the SQL of every work-item read selects from
  (`WorkItemResource`, `WorkDraftResource`, `WorkStepsResource`, `WorkGateResource` and the helpers they call) and
  checks each one against the dependency map in §4.2: an item trigger, a shared trigger, or a listed reason (covered by
  another trigger, or accepted staleness). A table added to a read without a decision fails the build (the same
  "derive, do not trust the declaration" approach as `DockerTestsAreSerialisedTest`).
- **Socket authorization:** a signed-in identity with neither `spire-viewer` nor `spire-admin` is refused at the
  handshake and receives no frame; a viewer and an admin connect and receive notices.
- **Mutation check:** remove one trigger and confirm exactly one test fails; remove the `cancelQueries` call and
  confirm the race test fails.
- **Live, on the dev stack:** a work item on `spire-test` moves from plan gate to build to land while its page is
  open, with no request to `/api/work-items/{id}` except those that follow a notice (checked in the browser's
  network panel and the orchestrator log).

## 7. Questions answered in review

1. **Notices for work items, rows for reviews and runs?** Kept. The work-item view is assembled from many tables
   at read time, so a notice and a REST read fit it; reviews and runs keep their row feeds. Consistency comes from
   the one transport hook and the three explicit cache-update kinds in §3.3, not from one frame shape.
2. **Triggers instead of a `push()` at each writer?** Accepted, on two conditions now in §4.2: the dependency map
   covers every table the screens read, writes without an item event included, and the `LISTEN` is committed
   before the recovery refresh.

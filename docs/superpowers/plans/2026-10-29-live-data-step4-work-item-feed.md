# Live data step 4: the work-item change feed — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The orchestrator tells every open work-item screen, over `/api/ws/work-items`, which work items changed, so step 5 can delete the poll timers.

**Architecture:** PostgreSQL triggers on the tables the work-item screens read send `pg_notify('work_item_changed', <id> | '*')` on commit. One listener thread holds a dedicated JDBC connection, collects notices for 250 ms and hands each batch to a broadcaster, which sends `{"changed":[ids]}` or `{"changed":"*"}` to every socket subscriber. The socket carries no data; the browser re-reads over the existing REST endpoints.

**Tech Stack:** PostgreSQL LISTEN/NOTIFY, pgjdbc `PGConnection.getNotifications`, Flyway, Quarkus websockets-next, JUnit 5 `@QuarkusTest` on Dev Services Postgres.

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` §4 (server design), §6 (server tests, coverage check, socket authorization).

## Global Constraints

- Channel name `work_item_changed`; payload is the work-item id, or `'*'` for a table that affects many items.
- Migration file `V104__work_item_change_notify.sql` (orchestrator schema; V103 is the latest).
- Socket path `/api/ws/work-items`, declared `endpointId` (`findByEndpointId` is an exact match), `@RolesAllowed({"spire-viewer", "spire-admin"})`.
- Frames: `{"changed":["<id>",…]}` (ids sorted, no duplicates) or `{"changed":"*"}`. Nothing is sent on open.
- Batch window 250 ms. `LISTEN` runs on an autocommit connection and commits **before** the `'*'` refresh, at startup and after every reconnect.
- The listener connection is outside the Agroal pool. Reconnect with back-off.
- One slow or broken subscriber never delays or blocks the others (the `RunsBroadcaster.send` pattern).
- Test data uses `TEST-` values only; every test removes its own rows (`WorkFixture.cleanWork`).
- No local builds: every test result comes from CI (`service tests + packaging` job).

## Rulings in this plan (for review)

1. **A deny-list, not `UPDATE OF`.** Spec §4.2 says each trigger is `UPDATE OF` the visible columns. This plan
   uses an `UPDATE` trigger with a `WHEN` clause that compares the row without its bookkeeping columns:
   `to_jsonb(OLD) - <ignored> IS DISTINCT FROM to_jsonb(NEW) - <ignored>`. Why: a column added later notifies by
   default (the safe side: one extra re-read, never a stale page), and an `UPDATE` that rewrites a row with the same
   values notifies nothing. The ignored columns:

   | Table | Ignored on UPDATE | Writer |
   |---|---|---|
   | `work_item` | `activity_polled_at`, `slot_reserved` | `WorkActivityPoller` every 2 min, `WorkItemStore` |
   | `work_source` | `health`, `checked_at`, `scan_cursor`, `scan_requested`, `scan_batch`, `scan_next_cursor` | `WorkSourceScanner` every 30 s, `WorkSourceRegistry` |
   | `work_land_effect` | `next_check_at`, `updated_at` | `WorkLandEffects.backOff` (line 107) when reason and failing-since repeat |
   | `work_tracker_outbox` | `checked_at` | `WorkSourceEffects.touch` defers a failed row |
   | `scm_provider` | `last_check_at`, `last_check_ok`, `last_check_error`, `reported_scopes`, `scopes_checked_at` | `ProviderRegistry` health and scope checks |

   Accepted notices that are not visible changes: `WorkLandEffects.waitForChecks` rewrites `detail` with a fresh
   ciphertext on every 2-minute pass, so a land that waits for checks notifies its own item every 2 minutes (one
   re-read per open page); `work_run_effect.ready_processed`/`result_processed` flips; one `'*'` at startup from
   `ContextCredentialReconciler`. The last three table rows are additions to the spec's list, found by reading every `UPDATE` of these tables. Cost if
   wrong: a value shown on screen that lives only in an ignored column goes stale until the item's next notice.
2. **The RED commit carries empty classes.** `WorkItemChangeListener`, `WorkItemsBroadcaster` and `WorkItemsSocket`
   exist in the RED commit with their signatures and no behaviour, so CI fails on assertions, not on compilation
   (a compile error would also hide the trigger tests' result). Cost if wrong: none; the bodies come in GREEN.
3. **Writes without an item event are tested with the writer's own SQL** where the writer method is private
   (`WorkSourceEffects.mark`, `.touch`, `WorkActivityPoller`), quoted with its source line. `LandSettings.save` is
   public and is called directly.

4. **`repository_account` is a shared table.** `GET /api/approvals` reads it through
   `WorkGateChannels.approvalAvailable` → `RepositoryAccounts.resolve`; the spec map missed it. It gets a shared
   (`'*'`) trigger: only an operator's binding change writes it. Cost if wrong: one `'*'` per binding save.
5. **The coverage test derives its files from the `@Inject` graph**, not a fixed list, because the SQL of a read
   often sits one class away (`event_log` in `JdbcEventStore`, `repository_account` in `RepositoryAccounts`). Each
   graph over-approximates: it also walks the action (POST) helpers. Traced on 2026-10-29, the GETs read only the
   spec map plus `repository_account`; the 22 further tables the walk finds (`app_setting`, `dlq_entry`,
   `llm_charge`, `review_status` through the attention feed's writers, and `autonomy_profile`,
   `autonomy_profile_version`, `work_label_mapping`, `work_repository_policy`, `harness_catalogue`,
   `harness_credential`, `llm_model`, `llm_model_rate`, `llm_provider`, `context_provider`, `provider_author`,
   `repository_fix_actor`, `repository_build_defaults`, `work_activity_receipt`, `work_land_observation`,
   `work_phase_attempt`, `work_prepare_effect`, `work_verify_effect`) are EXEMPT with the reason "reached only by an
   action or a background job; no work-item GET reads it". Limit, written in the test: a GET that starts reading an
   already-exempt table is not caught; a table new to the graph is.

## Review Focus

1. **A burst from one transaction that touches many items** (a land settings save is `'*'`, a gate sweep touches
   several items): one batch, one frame. Pinned by `aBurstBecomesOneBatch` (Task 2).
2. **The listener connection dies while the app runs** (database restart, idle kill): it reconnects, re-listens,
   sends `'*'`, and later writes still arrive. Pinned by `reconnectsAfterTheConnectionIsKilled` (Task 2).
3. **A signed-in identity with no role** opens the socket: refused at the handshake, no frame. Pinned by
   `anIdentityWithoutAViewerRoleIsRefused` (Task 2).
4. **A table later added to a work-item read without a trigger decision**: the build fails. Pinned by
   `everyTableAWorkItemReadSelectsHasADecision` (Task 1).
5. **Shutdown while the listener blocks in `getNotifications`**: the thread ends and the connection closes, so a dev
   restart does not leak a session. Pinned by `stopClosesTheConnection` (Task 2).

---

### Task 0: RED — plan, tests and empty classes

**Files:** all test files of Tasks 1 and 2; the three main classes of Task 2 as empty shells (signatures from
Task 2's Interfaces, bodies `{}` or `return`). No migration.

- [ ] **Step 1:** Write every test named in Tasks 1 and 2.
- [ ] **Step 2:** Commit `Plan and test live-data step 4: the work-item change feed`, push, open the PR, set `review: requested`.
- [ ] **Step 3:** Read the CI run. Expected: `service tests + packaging` FAILS; the failures are the tests of Tasks 1
  and 2 on assertions (no notice arrived / no frame / handshake not refused), and no compilation error.
  `bookkeepingAloneNotifiesNothing` and `aRolledBackWriteNotifiesNothing` may pass in RED (nothing notifies yet);
  they are guards that bite only once triggers exist, which the mutation check in Task 1 proves.

### Task 1: Triggers (migration) and the coverage check

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V104__work_item_change_notify.sql`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/NoticeTap.java` (test helper)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkItemChangeTriggersTest.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkItemChangeCoverageTest.java`

**Interfaces:**
- Produces: channel `work_item_changed`; trigger names `work_item_changed_<table>` (INSERT/DELETE) and
  `work_item_changed_<table>_update` (UPDATE with `WHEN`). Functions `notify_work_item_changed()` (argument
  `TG_ARGV[0]` = the id column: `id` for `work_item`, `work_item_id` for the rest) and `notify_work_items_changed()`
  (sends `'*'`).
- `NoticeTap implements AutoCloseable`: `static NoticeTap listen()` opens its own `DriverManager` connection (not a pooled one, so no
  `LISTEN` is left on the pool) and runs `LISTEN work_item_changed`; open it after `@BeforeEach` seeding (the
  fixture's saves send `'*'`) and drain once; `List<String> drain(Duration quiet)` returns the payloads received until no notice
  for `quiet`.

- [ ] **Step 1: Write the trigger tests** (`@QuarkusTest`, `extends WorkFixture`; insert the item with the fixture's
  `source` and `repository`, as `WorkItemIntakeIT` does). Each test opens a `NoticeTap`, drains it once before the
  write, writes, then asserts on `drain(Duration.ofMillis(300))`:
  - `aCommittedWriteToEachItemTableNotifiesItsId` — `@ParameterizedTest` over the 8 item tables
    (`work_item`, `work_item_gate`, `work_run_effect`, `work_land_effect`, `work_item_preparation_attempt`,
    `work_tracker_outbox`, `work_step_summary`, `work_item_artifact`): an INSERT, an UPDATE of a non-ignored column
    and a DELETE each give `List.of(itemId)`.
  - `aWriteToEachSharedTableNotifiesEveryItem` — over `repository_land_settings`, `repository`, `work_source`,
    `work_source_actor`, `scm_provider`, `repository_account`: an UPDATE of a non-ignored column gives a list that contains `"*"`.
  - `aRolledBackWriteNotifiesNothing` — UPDATE `work_item.reason` with autocommit off, rollback → `[]`.
  - `manyWritesInOneTransactionNotifyOnce` — three UPDATEs of `work_item` plus an INSERT into `work_item_artifact`
    for the same item in one transaction → `List.of(itemId)`.
  - `writesWithoutAnItemEventNotifyOnTheirOwn` — each alone gives its notice: the `WorkSourceEffects.mark` SQL
    (`UPDATE work_tracker_outbox SET state=?,remote_id=?,reason=?,checked_at=now() WHERE effect_id=? AND state=?`)
    → `[itemId]`; `LandSettings.save(repository, …)` → contains `"*"`; an INSERT into `work_step_summary` →
    `[itemId]`; `UPDATE work_land_effect SET state=…` → `[itemId]`.
  - `bookkeepingAloneNotifiesNothing` — each alone gives `[]`: `UPDATE work_item SET activity_polled_at=now() WHERE id=?`
    (`WorkActivityPoller`); `UPDATE work_item SET slot_reserved=? WHERE id=?`; the `WorkSourceScanner` cursor SQL
    (`UPDATE work_source SET scan_cursor=?,scan_requested=?,scan_batch=NULL,scan_next_cursor=NULL,health='healthy',checked_at=now() WHERE id=?`);
    `UPDATE work_source SET health=?,checked_at=now() WHERE id=?`; `UPDATE work_tracker_outbox SET checked_at=now() WHERE effect_id=?`;
    the `WorkLandEffects.backOff` SQL with the row's current reason and failing-since
    (`UPDATE work_land_effect SET reason=?,failing_since=?,next_check_at=?,updated_at=now() WHERE effect_id=? AND state=?`);
    `UPDATE scm_provider SET last_check_at = now(), last_check_ok = ?, last_check_error = ? WHERE id = ?`;
    and an UPDATE that sets `work_item.reason` to its current value.
- [ ] **Step 2: Write `WorkItemChangeCoverageTest.everyTableAWorkItemReadSelectsHasADecision`** (`@QuarkusTest`).
  - **Files, derived:** start from `WorkItemResource`, `WorkDraftResource`, `WorkStepsResource`, `WorkGateResource`.
    Read each source with `Files.readString`, take the type of every `@Inject` field, and add every source file under
    `src/main/java/dev/codespire/orchestrator` that declares `class <Type>` or `implements <Type>`; repeat until no
    new file. Assert each resource reaches at least one table (the empty-list trap).
  - **Tables:** extract `(?i)(from|join)s+([a-z_]+)` from those files and keep names that exist in
    `information_schema.tables` for schema `orchestrator`.
  - **Map:** each table must be ITEM (the 8 item tables), SHARED (the 6 shared tables) — each with a `pg_trigger` row
    named `work_item_changed_%` on it — or EXEMPT with a reason string: `event_log` → "covered: WorkItemStore.append
    upserts work_item in the same transaction"; `factory_run` → "step_index is set once at dispatch"; `operator_seen`
    → "accepted staleness: display name only"; plus the decisions Ruling 5 adds.
  - **Reverse:** every table with a `work_item_changed_` trigger is ITEM or SHARED.
  - The failure message names the table and the three choices.
- [ ] **Step 3 (GREEN): Write `V104__work_item_change_notify.sql`.** Two functions, both `RETURNS trigger LANGUAGE plpgsql`
  and `RETURN NULL` (AFTER triggers). The item function reads the id with
  `IF TG_OP = 'DELETE' THEN id := to_jsonb(OLD) ->> TG_ARGV[0]; ELSE id := to_jsonb(NEW) ->> TG_ARGV[0]; END IF;`
  and skips a NULL id. Per table:
  `AFTER INSERT OR DELETE … FOR EACH ROW` and `AFTER UPDATE … FOR EACH ROW WHEN (to_jsonb(OLD) - '{…}'::text[] IS DISTINCT FROM to_jsonb(NEW) - '{…}'::text[])`
  with the ignored list from Ruling 1 (`'{}'` for tables with none). A header comment points to spec §4.2 and names
  the coverage test as the place to record a decision for a new table.
- [ ] **Step 4: Mutation check (in CI).** Push one commit that removes the `work_step_summary` triggers, read the
  CI run, then push its revert. Expected: exactly these fail — `everyTableAWorkItemReadSelectsHasADecision`, the
  `work_step_summary` parameter of `aCommittedWriteToEachItemTableNotifiesItsId`, and the step-summary case of
  `writesWithoutAnItemEventNotifyOnTheirOwn`. Record the run id in the ledger.
- [ ] **Step 5: Commit** `Notify work-item changes from database triggers`.

### Task 2: Listener, broadcaster and socket

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkItemChangeSink.java`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkItemChangeListener.java`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkItemsBroadcaster.java`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/ws/WorkItemsSocket.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkItemChangeListenerTest.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkItemsBroadcasterTest.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkItemsSocketTest.java`
- Modify: `CLAUDE.md` (the "One way for the dashboard to get server data" bullet: the feed exists; the screens still
  poll until step 5).

**Interfaces:**
- Consumes: channel `work_item_changed` (Task 1).
- Produces:
  - `interface WorkItemChangeSink { void changed(Set<String> ids); void refreshAll(); }`
  - `WorkItemsBroadcaster implements WorkItemChangeSink` (`@ApplicationScoped`, `public static final String ENDPOINT_ID = "work-items"`).
    `changed`: a set containing `"*"` sends `{"changed":"*"}`, otherwise `{"changed":[sorted ids]}`; an empty set
    sends nothing; no subscribers → nothing serialised. `refreshAll()` = `changed(Set.of("*"))`.
  - `WorkItemChangeListener` (`@ApplicationScoped`): `void onStart(@Observes StartupEvent)` starts one virtual
    thread; `@PreDestroy void stop()` ends it and closes the connection. Package-private seams for the test:
    `start(WorkItemChangeSink sink, Connector connector)` (`interface Connector { Connection open() throws SQLException; }`) and `stop()`.
    Connection from `DriverManager.getConnection` with `quarkus.datasource.jdbc.url`, `quarkus.datasource.username`,
    `quarkus.datasource.password` and `ApplicationName=spire-work-item-listener`. Constants `BATCH_MS = 250`,
    back-off 1 s doubling to 30 s.
  - `WorkItemsSocket`: `@WebSocket(path = "/api/ws/work-items", endpointId = WorkItemsSocket.ENDPOINT_ID)`,
    `@RolesAllowed({"spire-viewer", "spire-admin"})`, `ENDPOINT_ID = WorkItemsBroadcaster.ENDPOINT_ID`, no `@OnOpen` frame.

- [ ] **Step 1: Write `WorkItemChangeListenerTest`** (`@QuarkusTest`; builds its own listener with a recording sink,
  so the app's listener is not disturbed):
  - `listensBeforeItRefreshes` — the recording sink's `refreshAll()` performs a committed write to a `TEST-` item on
    **every** call; that id arrives in a later `changed` batch.
  - `aBurstBecomesOneBatch` — five committed writes to five items in one loop → all five ids arrive in at most two
    `changed` calls, and the first call holds more than one id.
  - `reconnectsAfterTheConnectionIsKilled` — `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'spire-work-item-listener-test'`
    (the test passes its own ApplicationName) → a second `refreshAll()` within 5 s, its write arrives (the gap after a
    reconnect), and a write after it arrives.
  - `stopClosesTheConnection` — after `stop()`, no `pg_stat_activity` row with that ApplicationName within 2 s.
- [ ] **Step 2: Write `WorkItemsBroadcasterTest`** (plain JUnit, mirrors `RunsBroadcasterTest`):
  `withoutSubscribersNothingIsSerialised`, `idsAreSortedAndDeduplicated`, `aStarInTheBatchSendsOneStarFrame`,
  `aFailedSendDoesNotStopTheOtherSubscribers`.
- [ ] **Step 3: Write `WorkItemsSocketTest`** (`@QuarkusTest`, real upgrade, the `Feed` helper pattern of `factory/RunsSocketTest`):
  - `aViewerReceivesNoticesAndNothingOnOpen` — `@TestSecurity(roles = "spire-viewer")`: no frame within 300 ms of open;
    after a committed `work_item` UPDATE, the frame `{"changed":["<itemId>"]}` arrives (end to end through the
    app's listener).
  - `anAdminReceivesNotices` — `@TestSecurity(roles = "spire-admin")`: `broadcaster.refreshAll()` → `{"changed":"*"}`.
  - `anIdentityWithoutAViewerRoleIsRefused` — `@TestSecurity(user = "TEST-norole", roles = "TEST-other")`: the
    handshake fails (the `buildAsync` future completes exceptionally) and no frame is received.
- [ ] **Step 4 (GREEN): Implement the four classes** to the Interfaces above. Listener loop: connect →
  `setAutoCommit(true)` → `LISTEN work_item_changed` → `sink.refreshAll()` → loop `getNotifications(BATCH_MS)`; on
  the first notice of a batch, keep reading until 250 ms have passed since it, then `sink.changed(ids)`. An
  `SQLException` closes the connection, waits the back-off and starts over; `stop()` sets the flag, closes the
  connection and interrupts the thread, so a back-off sleep ends at once. A comment says the session is on the
  default schema and is for `LISTEN` only; a sink exception is logged at WARN and
  the loop continues. Log reconnects at WARN (one line per drop, with the back-off), the first `LISTEN` at INFO.
- [ ] **Step 5:** Update the CLAUDE.md bullet. Commit `Push work-item change notices over /api/ws/work-items`.
- [ ] **Step 6:** Push. Expected CI: all three jobs green; the Task 1 and Task 2 tests pass by name in the
  `service tests + packaging` log.

### Task 3: Live check and review

- [ ] **Step 1:** Rebuild the dev orchestrator (`docker compose -p spire-dev … up -d --build --no-deps orchestrator`).
  Check the log has the INFO `LISTEN` line once.
- [ ] **Step 2:** In the signed-in dashboard tab, open a `WebSocket` to `/api/ws/work-items` from the console. Change
  a work item (for example a settings save for its repository, or a real transition). Expected: one
  `{"changed":[…]}` or `{"changed":"*"}` frame, and no frame on open.
- [ ] **Step 3:** Restart `spire-postgres`. Expected: one WARN reconnect line in the orchestrator log, then one
  `{"changed":"*"}` frame on the still-open socket once the listener is back.
- [ ] **Step 4:** Fresh whole-branch reviewer, then the bot reviewer. Merge on the operator's word.

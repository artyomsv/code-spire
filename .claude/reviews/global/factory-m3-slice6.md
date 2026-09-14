# Factory M3 slice 6 — source parity and recovery

Round 9 accepted the route pollution fix on d426501c. Criteria 3, 5, 6 and 7 remain independently
verified; this slice adds parity and recovery evidence, not another headline criterion.

## What changed

GitLab and Jira work-source modules reuse the context clients and pinned transport, with distinct
write facades. GitLab authenticates Issue Hook deltas through the real gateway/Kafka path. Jira
uses polling, explicit project-to-repository mapping and actual Cloud accountIds. Unknown authors
and incomplete history remain unattributed. Data Center explicitly omits unsupported attribution
and recoverable transition capabilities. Each arm has its own measured/live-gap entry in UNVERIFIED.

The source screen registers a tracker using explicit repository and account selections, resolves
people through that source, requires ambiguous Jira selection, saves revisions, requests rescans
and displays capabilities. Configured enablement remains visible when an account is unavailable;
an existing source can still be disabled in that state. Pending responses cannot cross source
selection or teardown boundaries. The route's `.content` and admin controls remain discriminating.

V65 stages only candidate coordinates. Each item reconciliation and checkpoint deletion shares
one transaction. Ten coordinates per sweep and bounded remote observations permit progress on
large pages. V66 stores encrypted tracker intents separately from Kafka domain notifications.
A pending effect rechecks current policy and local revisions, commits uncertainty before HTTP,
then sends once. Recovery only reads; missing evidence leaves the effect uncertain.

## Evidence that distinguishes the guards

- Both `GitLabWorkItemIntakeIT` and `JiraWorkItemIntakeIT` run all five methods for each headline
  mutation. Removing membership fails only `unlistedLabellerSelectsNoProfile`; removing attribution
  fails only `unattributedCurrentLabelSelectsNoProfile`. The latter retains allowed hint **900123**,
  persists `UNATTRIBUTED`/`label_unattributed`, and asserts no run, gate or tracker-write effect.
- `WorkSourceProcessRecoveryIT` starts the current packaged orchestrator on isolated test DB/Kafka
  with a WireMock GitHub source,
  forcibly kills actual JVMs between pages and inside a staged page, then resumes. It observes
  two items, one history each and no re-fetch of the completed coordinates. Child configurations
  and processes are isolated from the worktree's dev environment.
- `WorkSourceCheckpointTest` fails checkpoint deletion after admission. It checks zero event,
  item, delivery and both outbox rows, plus the still-pending coordinate, then retries successfully.
  Concurrent scans must produce one successful checkpoint and one clean refusal.
- `WorkSourceEffectsTest.retryFindsThePreviouslyWrittenComment` changes the remote WireMock state
  when POST arrives and delays its response beyond the HTTP timeout. A fresh service object reads
  the durable claim and marker; the journal contains one POST. Another test reads the committed
  uncertain state through a separate connection while the first response remains pending.
- The Jira offset mutation initially survived because an inconsistent `isLast` also refused the
  fixture. The corrected fixture makes every other completion fact agree, isolating `startAt`.
  Nontext Jira accountIds are not coerced into attribution. Transition recovery binds both the
  effect and transition IDs; unknown IDs, unmet required fields and uncertain acknowledgements refuse.
- Scanning changed Java tests explicitly exposed a ProcessBuilder finding hidden by the default
  Java test-path exclusions. The process test now uses a literal `java` executable, with the Gradle
  test task putting its selected toolchain first on PATH. No scanner suppression was added. The
  changed-file scan includes these test sources and reports zero findings and zero parser errors.
- The forced architecture suite caught copied module names in the two LICENSE files and
  provider-specific capability wording in the administration service. The names are corrected;
  capability explanations now come from the work-source adapters through the SPI. The resource
  proof requires that explanation to reach the response, with a production mutation of the call.

## Verification status

Forced `testFast` and `testServices`, then `assemble`, passed sequentially with JDK 25: **3545 Java tests across 407 suites and 30 modules**, zero failures and 1 existing Windows symlink privilege skip. The final service run includes both actual packaged-JVM kill/restart tests. The UI passed **675 tests across 81 files** and its production build; all 37 route cases passed in normal order and three shuffled orders (42, 99, 5191448392).

**142 mutation checks cover 140 distinct production changes** (121 Java-main checks and 21 UI checks). Every check has exactly one selected assertion failure, byte-identical scratch restoration and a passing restored run. No fixture is a mutation target.

Pinned Semgrep 1.172.0 (CI digest `65dcd4408adda7c183a6b4550cb1e9b19f7f627a6fbb7e0559bd466bedc44d7b`, p/default and p/secrets) reported zero findings over 1179 source files. That full scan had 24 parser warnings in existing templates/scripts and CRLF Dockerfiles. An explicit LF-normalized scan of all 56 changed source/test/build/migration files reported **zero findings and zero parser errors**, including Java test paths normally excluded by rules. Final source hashes match that scan. No suppressions were added.

Exact production lines, failed methods and restored results: `factory-m3-slice6-mutations.json`.

## Exact fixture cleanup

The executable DELETE statements and their exact bindings are in
[`WorkFixture.cleanWork`](../../../spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkFixture.java),
[`WorkSourceProcessRecoveryIT.stopChildrenAndCleanOtherItems`](../../../spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkSourceProcessRecoveryIT.java)
and [`GitLabWorkWebhookTest.clean`](../../../spire-gateway/src/test/java/dev/codespire/gateway/GitLabWorkWebhookTest.java).

No dev work source, live tracker write or live run was created. `WorkFixture` deletes only its
generated TEST IDs, in this order: tracker outbox, gates, Kafka outbox, delivery IDs, work item,
event log, source actors, source, label mappings/policy, profile versions/profiles, repository
bindings/repository and account. All predicates use the fixture's item/source/repository/account
IDs. `work_scan_candidate.source_id` cascades on deletion of that exact source. The process test
also deletes its second item's tracker outbox/gate/outbox/delivery/item/event rows by that exact item ID.
The checkpoint failure test drops its exact `TEST_fail_work_checkpoint` trigger/function in
`finally`. `GitLabWorkWebhookTest` deletes `webhook_repo WHERE id=?`, then the corresponding
`repository_snapshot_outbox WHERE registration_id=?` tombstone. WireMock and child JVMs stop in
test cleanup; Testcontainers owns only the isolated test stack.

Full policy/approval journeys remain slice 7; artifact/build handoff remains slice 8. The user
will start the run worker for slice 8b's standalone `/fix` live proof.

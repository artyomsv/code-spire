# Factory M3 slice 5 — first durable tracker ticket

Round 7 independently verified criterion 5. Criteria 5, 6 and 7 are accepted; criterion 3 awaits
independent review of this slice. Source parity, full policy gates and build handoff remain later work.

## What changed

GitHub signed issue delivery and explicit scans share source-bound evidence reconciliation and
admit one durable work item. Sources explicitly select a repository/account and own their stable
actor allowlist. The Apache-2.0 SPI remains JDK-only; context reads and work writes share the
existing pinned transport through distinct facades. Unknown or incomplete current-label audit
cannot grant policy, including a verified hint whose actor is allowed.

Immutable profile versions, every eligible label and the repository ceiling determine effective
phase modes. The full combined admission vector remains a bound after another label disappears.
Applied/ignored evidence and local authority revisions are durable; title/body/tracker status are
fetched separately. Missing specification/approval execution is visible, with no synthetic success.

One JTA transaction appends the encrypted work event, updates bookkeeping, deduplicates delivery
and creates the encrypted work outbox row. Stable event IDs are published on a dedicated topic
only marked complete after broker acknowledgement. Review history receives no work event.

The round 7 carry replaces locks across /fix permission reads with short revision snapshots on
both sides of the bounded remote call. Account rotation, repository rebinding and override edits
discard the result. The explicit-override early return is documented beside the final null argument.

## Criterion 3: distinct guards, real intake

Every negative has a mapped otherwise eligible profile, a real selected source/account and ceiling,
the persisted ignored reason and zero run effects. These names remain separate in `WorkItemIntakeIT`:

| Test | Fixture and proof |
|---|---|
| `unlistedLabellerSelectsNoProfile` | Attributed actor 900999; reason `actor_not_allowed`; no profile or run effect. |
| `unattributedCurrentLabelSelectsNoProfile` | Allowed actor hint **900123**, origin **UNATTRIBUTED**; reason `label_unattributed`; no profile or run effect. |
| `allowedAttributedLabellerCanSelect` | Allowed attributed actor 900123 selects the mapped profile and reaches real `awaiting_input`. |
| `aGenuinelyMissingActorSelectsNoProfile` | No actor ID; reason `actor_id_missing`; no profile or run. |

Both headline mutations run **all seven methods**. Deleting membership fails only the unlisted
method. Deleting attribution fails only the unattributed method. Each class returns to 7/7 green
after a byte-identical scratch restore. Neither proof counts a decoding or setup exception as success.

## Other discriminating evidence

- Carry: operator saves finish within one second during a three-second delayed permission read;
  the split result refuses as `PERMISSION_UNAVAILABLE`. Deleting the re-read makes the rotation
  test fail. Seven carry mutations have restored passing tests.
- Atomicity: a TEST-only PostgreSQL trigger fails projection after append. The test asserts zero
  event, projection, gate, dedupe and outbox rows, then retries after removing the trigger.
  Moving append into `requiringNew` leaves an orphan event and fails the assertion.
- Broker routing: actual Kafka intake requires the duplicate delivery's committed consumer offset
  before asserting one history and cleaning up. Actual outbox publication is observed on
  `cs.work-events`; rerouting it to the review channel fails that broker assertion.
- Admission: removing a restrictive label cannot widen the persisted combined vector. Deleting
  the pure bound and disconnecting the store's admission pin each fail their selected tests.
- Scanner races: empty pages isolate source, cursor and policy revision checks from per-item
  store checks. Changing each during the remote read must refuse the page commit.
- UI: stale success/error responses cannot overwrite newer list, workflow or tracker state.
  Unknown workflow status is refused/unknown; tracker outages leave workflow visible.
- Scope validation accepts dot-prefixed GitHub repositories such as `.github` while rejecting
  `.` and `..` path segments. Restoring the old regex fails separate read and signed-ingress
  positive cases; deleting the dot-segment exclusion fails its signed-ingress negative case.
  The valid repository shape is documented by
  [GitHub](https://docs.github.com/en/organizations/collaborating-with-groups-in-organizations/customizing-your-organizations-profile).

## Validation

Forced `testFast`, then `testServices`, then `assemble` passed sequentially with JDK 25,
`--rerun-tasks --no-parallel`. Reports contain **3371 Java tests across 393 suites
and 28 modules**, zero failures/errors and 1 existing Windows symlink privilege skip.
The worktree had no dev run worker before Docker-driving tests.

The full UI suite passed **650/650 tests in 79 files** with `--maxWorkers=4`; the final Work items
changes passed their 12 tests and TypeScript/Vite production build. An earlier full run failed
in the unchanged archive test; that suite passed 8/8 in isolation and the complete rerun passed.
The existing bundle-size warning remains.

Pinned Semgrep 1.172.0 (`p/default` plus `p/secrets`) ran 442 rules on 1,159 files with zero
findings and the same 24 pre-existing diagnostic paths; no Java parser errors. Later scans of
the added scanner test, expanded authorization test and four GitHub scope files also passed
with zero findings/parser errors. All 89 changed code/configuration files match a scanned copy.
No suppression was added.

**154 mutation checks / 153 distinct production mutations**: 143 checks target Java main
sources, 2 target migration constraints, and 9 target UI production source. None mutate a fixture.
The admission-bound deletion is deliberately exercised by two independent tests and counted
once in the distinct total.

Exact production lines, replacements, test selectors, scratch SHA-256 values and evidence prefixes
are committed in [the mutation inventory](factory-m3-slice5-mutations.json). The runners and raw
mutant/restored reports are under `.handoff/s5-*`. Each Java/schema check requires one selected
assertion failure and a restored pass; the two headline checks additionally require exactly one
failure across the complete seven-method intake class. WireMock's `VerificationException` is an
assertion failure; compilation failures, setup errors and surviving mutants are not accepted.

Run a selected JVM proof with JDK 25 and
`./gradlew :<module>:test --rerun --tests <fully-qualified-method> --no-parallel`.
Never run another Gradle invocation or a dev run worker concurrently. Mutation restoration uses
scratch bytes, never git. Full reports are archived in `.handoff/s5-full-junit`.

## Cleanup and limits

Every new database row belongs to a `TEST-` fixture on isolated Dev Services PostgreSQL.
`WorkFixture.cleanWork` contains the exact parameterized DELETE statements: item-owned gate,
outbox, dedupe, projection and event rows; source actors/sources by fixture repository; mappings,
policy and versions by fixture IDs; repository bindings/repository and the selected account.
`GitHubWorkWebhookTest` deletes its own gateway registration and snapshot-outbox rows by UUID.
The injected rollback trigger/function is removed in `finally`. No shared dev database, webhook,
credential, backup or run worker was changed.

Fresh-store-instance recovery is not a live process restart. WireMock plus real PostgreSQL/Kafka
does not establish live GitHub token behavior. Large slow scan pages, source parity, uncertain
remote writes, full approvals and build execution remain named in `docs/UNVERIFIED.md` and the
later slice plan. Work items has no tracker-content mirror and no admission run effect.

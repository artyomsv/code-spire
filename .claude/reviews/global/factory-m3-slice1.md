# Factory M3 slice 1 — repository registry and migration bridge

PR #153, issue #114. This slice expands the schema and makes explicit repository configuration
reviewable. Review/run callers still use their existing legacy resolvers until slice 2.

## Review disposition

- Round 2's remaining backup concern: the plan now uses worktree-relative `.handoff/`, already
  ignored by git. No session GUID remains in the plan. The existing
  `spire-dev-pre-m3-2026-09-13.dump` satisfies the prerequisite: 182 objects, 492,480 bytes,
  SHA256 `7660D8EAE4110141887BD565747D45D1AA2621025A157D36741F70B6E65A1051`.
  No second dump was taken in response to that review.
- Keep `scm_provider.workspace`, its values and old constraints in slice 1. Slice 2 removes the
  key/check and runtime reads; slice 10 removes the retained workspace column.
- Reconcile the M2 live-proof claims now: `artyomsv/spire-test#31`, runs `3987682681:1` and
  `3987682176:1`, resolved threads and persisted verdicts. Keep the automated GitLab network gap
  separate. Slice 8b's standalone live `/fix` proof remains required; it was not attempted here.

## Implemented behavior

- V60 adds repositories, role bindings, immutable legacy mapping evidence, snapshot revisions and
  nullable history references. V3 adds the gateway outbox and optional registration origin.
- Registry API and settings view expose workspace, canonical origin and explicit role accounts,
  including missing, disabled and conflicting identities. Stale edits fail with a reload message.
  Referenced account deletion names repositories; kind/origin changes require reassignment.
- Disabled repositories/accounts, wrong roles/kinds/origins and identity collisions after rotation
  cannot resolve through the new registry. Binding validation preserves separate known identities.
- Gateway metadata has a typed wire discriminator and a stable registration key/revision. It
  contains no webhook secret/routing key. Broker acknowledgement precedes marking the outbox sent;
  failed processing has a registry-specific DLQ replay route.
- Replays preserve operator-selected accounts. Missing/conflicting legacy origins become pending
  mappings and attention rows with explicit repair. Real historical coordinates, including nested
  namespaces observed through legacy org coverage, receive nullable repository references.
  Automatic binding requires explicit registration origin or persisted PR URL evidence matching
  the legacy account origin. Workspace equality alone is insufficient; history without that
  evidence remains pending. Both review and run history are linked after a mapping is established.

## Verification scope

All new database fixtures run in disposable Dev Services databases/private schemas and use
`TEST-` names. `RepositoryFixture.removeFixture` deletes owned history, bridge rows, bindings,
repositories, legacy evidence and accounts in FK order; gateway fixtures remove their registration
and outbox rows. No synthetic rows were inserted into the running dev database.

The read-only credential probe captured and compared 9 real credential/reference entries. An
encrypted TEST-only baseline with an extra reference was rejected; bypassing the comparison
caused exactly one CLI assertion failure, and restoration made it pass again. The real baseline
then matched again. This is pre-upgrade evidence: the dev stack has not been rebuilt for slice 1.

Two existing tests needed deterministic clocks: `RunAgentStartedTest` now compares a database
timestamp against the database clock; `ReviewRetryScheduleIT` uses an explicit future clock so
the background scheduler cannot claim its fixture. No production timing behavior changed.

The user stopped four pre-existing `quarkusDev` run workers during verification. None was started
for this slice. Final Docker-driving verification passed with those workers stopped; there was
no missing-container failure. A read-only thread snapshot located the long runtime-test pause in
the existing publisher-drain wait, whose limit is five minutes. No runtime code was changed.

## Final verification — 2026-09-13

| Gate | Measured result |
|---|---|
| `testFast --rerun-tasks` | 1053 tests / 125 suites; zero failures; one skip; 1m 29s |
| `testServices --rerun-tasks` | 1952 tests / 223 suites; zero failures or skips; 20m 10s |
| Java total | 3005 tests / 348 suites; zero failures; one skip |
| Full UI suite | 620 tests / 76 files passed |
| `npm run build` | TypeScript and Vite passed |
| Working diff | `git diff --check` passed |

Every Java report counted above was freshly written by the final gates; nightly `spire-e2e`
reports were excluded. The existing skipped test is
`PublishRepoTest.theBundleIsOpenedWithoutFollowingASymlink`: this Windows session lacks the
privilege to create the symlink. The service gates ran sequentially after all production mutations
were restored. No live deployment or post-upgrade credential proof is claimed.
## Mutation verification

Each Java row below changed a compiling production line, selected exactly one test, observed
exactly one failure and no skips, restored the working-file scratch snapshot, and observed one
passing test. No mutation was restored from git. Repeated checks after origin changes are counted
once. The two UI mutations and one CLI mutation used the same fail/restore/pass discipline.

| Mutation | Targeted test |
|---|---|
| `aad_preservation` | `RepositorySchemaMigrationTest.preservesAccountIdsCredentialsAndContextReferences` |
| `account_enabled` | `RepositoryAccountsTest.disabledAccountCannotServeAnExistingBinding` |
| `account_kind` | `RepositoryAccountsTest.corruptBindingCannotUseAnotherKind` |
| `account_origin` | `RepositoryAccountsTest.rejectsAnAccountFromAnotherOrigin` |
| `account_role` | `RepositoryAccountsTest.corruptBindingCannotUseAnotherRole` |
| `admin_registration` | `RepositoryResourceTest.viewerCannotRegisterRepository` |
| `ambiguous_origins` | `RepositoryMigrationBridgeTest.leavesConflictingOriginsPending` |
| `binding_identity` | `RepositoryAccountsTest.rejectsSameResolvedIdentity` |
| `binding_kind` | `RepositoryAccountsTest.rejectsWrongKindBinding` |
| `binding_missing` | `RepositoryAccountsTest.missingAccountIsAnActionableConflict` |
| `binding_origin` | `RepositoryAccountsTest.rejectsCrossOriginBindingWithoutLeavingRepository` |
| `binding_role` | `RepositoryAccountsTest.rejectsWrongRoleBinding` |
| `broker_ack` | `RepositorySnapshotPublisherTest.failedBrokerAcknowledgementKeepsTheOutboxForRetry` |
| `dlq_destination` | `DlqTopicsTest.registrationReplaysOntoItsRegistryTopic` |
| `gateway_bootstrap` | `RepositorySnapshotMigrationTest.upgradeQueuesExistingRegistrationWithoutChangingKeyOrSecret` |
| `gateway_origin_retention` | `RepositorySnapshotPublisherTest.preservesConfiguredOriginWhenALegacyClientEditsRegistration` |
| `history_run_link` | `RepositoryMigrationBridgeTest.orgHistoryCreatesOnlyTheRepositoryActuallyObserved` |
| `mapping_attention` | `RepositoryMigrationBridgeTest.leavesConflictingOriginsPending` |
| `mapping_coordinates` | `RepositoryMigrationBridgeTest.mappingRefusesAnotherRepositoryPath` |
| `mapping_preservation` | `RepositoryMigrationBridgeTest.missingLegacyAccountStaysVisibleAndCanBeLinked` |
| `mapping_revision` | `RepositoryMigrationBridgeTest.mappingRefusesStaleRegistrationRevision` |
| `origin_url` | `ForgeOriginTest.rejectsAmbiguousOrSecretBearingUrls` |
| `public_web_origin_one` | `RepositoryForgeOriginTest.mapsPublicWebOriginsWithoutRewritingSelfHostedOrigins` |
| `public_web_origin_two` | `RepositoryForgeOriginTest.mapsPublicWebOriginsWithoutRewritingSelfHostedOrigins` |
| `referenced_delete` | `RepositoryAccountsTest.referencedDeleteNamesTheRepository` |
| `referenced_origin` | `RepositoryAccountsTest.referencedAccountCannotBeRepurposed` |
| `registration_origin` | `RepositoryMigrationBridgeTest.registrationFromAnotherHostCannotInheritWorkspaceCredentials` |
| `repository_enabled` | `RepositoryAccountsTest.disabledRepositoryCannotResolve` |
| `repository_revision` | `RepositoryResourceTest.staleUpdateCannotOverwriteBindings` |
| `repository_unique` | `RepositoryResourceTest.duplicateCoordinatesCannotCreateSecondRepository` |
| `role_selection` | `RepositoryAccountsTest.reviewerNeverReceivesTheFactoryCredential` |
| `rotated_identity` | `RepositoryAccountsTest.identityCollisionAfterRotationCannotServeEitherRole` |
| `slug_path` | `RepositoryCoordinatesTest.refusesNamespaceInsideSlug` |
| `snapshot_positive_revision` | `RepositoryRegistrationTest.rejectsInvalidSnapshotBeforeStorage` |
| `snapshot_revision` | `RepositoryMigrationBridgeTest.staleSnapshotCannotResurrectDeletedRegistration` |
| `unknown_origin` | `RepositoryMigrationBridgeTest.unknownRegistrationOriginCannotInheritWorkspaceCredentials` |
| `workspace_path` | `RepositoryCoordinatesTest.refusesTraversalAndEmptySegments` |
| `ui_host`, `ui_role` (separate mutations) | `RepositoryRegistryPage.test.tsx`: offers only same-origin accounts of the chosen role and saves explicit ids |
| `credential_comparison` | Read-only CLI Compare rejects an encrypted TEST baseline with one extra reference; bypassing equality fails that assertion; restoration rejects it and matches the real 9-entry baseline |

Completed: 37 distinct Java mutations, 2 UI mutations and 1 CLI mutation (40 total).

## Round 3 — blank-origin review correction

The record now rejects empty/whitespace origins while allowing null. Gateway `webhook_repo`
and orchestrator `repository` enforce the requested non-empty CHECK constraints. The bridge
uses one blank-safe missing-origin predicate both when decoding legacy payloads and when
reconciling mappings. Legacy blanks are converted to null before strict record construction;
the resulting unknown-origin mapping is durable and repairable.

`RepositorySnapshotConsumerTest.blankLegacyOriginIsPendingAndAcknowledged` sends raw empty-origin
JSON through the actual broker and consumer. It waits for the consumer group's committed offset
to pass that exact message, then asserts the stored revision, `registration_origin_unknown`,
null origin and no repository binding. DLQ delivery alone cannot pass the mapping assertion.
Removing `origin.isBlank()` from the shared bridge predicate produced exactly one
`AssertionFailedError` at the durable-row assertion, after the consumer offset had committed.
No exception escaped into the test. Restoration made that same real-broker test pass.
All four review-fix mutations compiled, failed exactly one targeted test, restored from a working
scratch snapshot and passed that same test. They bring the slice's distinct mutation total to 44.

| Production guard removed | Targeted test | Scratch snapshot SHA256 |
|---|---|---|
| Record `forgeOrigin != null && forgeOrigin.isBlank()` | `RepositoryRegistrationTest.rejectsBlankOriginButAllowsUnknownOrigin` | `6E3CC31C742B93B112E1E527834BC67D8EBBB02A71932AEF806F0A8FA57E053B` |
| Bridge `origin.isBlank()` | `RepositorySnapshotConsumerTest.blankLegacyOriginIsPendingAndAcknowledged` | `6A0CA6B8573A8977DD42742D67E1FEBF85AF03E6C1670772475D1DA49DB38B7C` |
| Gateway origin CHECK | `RepositorySnapshotPublisherTest.databaseRejectsBlankOriginButAllowsUnknownOrigin` | `1E278A1DF48DC2B3D3A49E800E7952E2C1DD52FC7134A5AE54248DC552BB6C8D` |
| Repository origin CHECK | `RepositorySchemaMigrationTest.databaseRejectsBlankRepositoryOrigin` | `18EFA507137BF5BD8AFC425AD49E3D366E90DE8AF239EA78B6C9B41ABE0BBB1A` |

The first forced service run exposed the same pre-existing retry-fixture race in
`ArchivedReviewAttentionTest`: its live-row precondition failed before the archive assertion,
because the background scheduler could claim its wall-clock-due fixture first. It now schedules
against an explicit future test clock, as `ReviewRetryScheduleIT` already does. No production
timing changed. The final service gate is rerun after this test-only correction.
The first retry also encountered PostgreSQL Dev Services startup timeouts in gateway and
review-worker, before their Quarkus suites could run. Container logs showed database initialization,
not a missing-container error. The next forced service run disables Gradle project parallelism
for this invocation only; no runtime timeout or repository build configuration is changed.

Final verification passed on 2026-09-13:

| Gate | Result |
|---|---|
| `testFast --rerun-tasks --console=plain` | 1054 tests / 125 suites; no failures/errors; one existing Windows symlink privilege skip |
| `testServices --rerun-tasks --no-parallel --max-workers=2 --console=plain` | 1955 tests / 223 suites; no failures/errors/skips; 22m 2s |
| Combined Java reports | 3009 tests / 348 suites; zero failures/errors; one skip |
| Four new isolated mutations | Each compiled, failed exactly one targeted test and passed after scratch restoration |
| UI | Unchanged by this correction; preceding slice 1 proof remains 620 tests / 76 files and successful build |

The final service invocation passed gateway (84), orchestrator (1295), review-worker (225),
run-worker (266), plus agent-image/runtime-docker (85 combined). All report files came from the
forced tiers, excluding the separate nightly E2E tier. No live run worker was started and no
missing-container failure occurred. A read-only thread capture confirmed the long Docker test
was waiting in its existing publisher drain; no runtime code or timing was changed for this fix.

## Round 4 — literal history-link SQL

The reviewed dynamic table-name concatenation had only two private literal callers and bound all
values, but it failed Semgrep. `LINK_REVIEW_STATUS` and `LINK_FACTORY_RUN` now contain complete
literal SQL statements; the helper receives either constant and binds the same four values.
No suppression was added. The forced `RepositoryMigrationBridgeTest` run passed all 11 tests
after this change, including review and run history linking.

The failing workflow's full scan reported exactly one finding across 1081 files. Its PR gate
reported the same rule, `java.lang.security.audit.formatted-sql-string.formatted-sql-string`.
The separate `Semgrep OSS` check contained exactly one annotation on the same history-bridge line;
there was no second finding hidden behind the gate failure. New-head CI checks are required before
the account resolver cutover proceeds.

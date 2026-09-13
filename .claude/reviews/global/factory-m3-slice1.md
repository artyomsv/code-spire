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

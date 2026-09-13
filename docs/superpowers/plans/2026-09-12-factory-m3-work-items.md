# Factory M3 — work items, labels and gates — implementation plan

**Date:** 2026-09-12

**Status:** Planning accepted; slice 1 implemented and verified for review. Slices 2–10 remain planned.

**Goal:** Start factory work from a tracker ticket with explicit, bounded autonomy; make repository
ownership, command authority and approval state visible and durable.

**Issue:** [#114](https://github.com/artyomsv/code-spire/issues/114), fetched after its
`2026-09-12T21:47:45Z` update.

**Design:** [Factory M3 design](../specs/2026-09-12-factory-m3-work-items-design.md).

**Architecture:** An event-sourced work-item lifecycle owns workflow decisions. Existing run
records and the charge ledger retain execution truth; no run aggregate or transcript replay is
introduced. Explicit repository-role bindings replace account-workspace lookup. The gateway owns
keyed, authenticated webhook registrations. Work-source adapters reuse context transport with a
separate write facade. Current policy is checked at each boundary before durable effects leave
the orchestrator. The design's §2 explains the aggregate decision and its ADR-034 amendment.

**Stack:** Java 25 / Quarkus / JDBC and Flyway; PostgreSQL + Kafka; React + TypeScript, vitest and
Testing Library; existing Gradle split test tiers. Keep the versions already pinned by the repo.

**Branch:** `feat/factory-m3-work-items`, already checked out in
`E:\Projects\Stukans\code-spire-worktrees\feat-software-factory`, based on `origin/master` at
`27fe17b`. Do not create or switch a branch. The analyst reviews this same worktree.

## Global constraints

- Round 1 changes exactly the design and this plan. **Slice 0 opens the draft PR**, before any
  production slice. Opening a draft is explicitly authorized by the brief; no extra permission
  round is needed. The draft stays draft for analyst review.
- Commit each independently reviewable slice with imperative first line, maximum 72 characters,
  and a body explaining nontrivial changes. No authoring attribution, coauthor trailers, model
  names, vendor names or generated-by notices in commit/PR text. Requested PR title takes
  precedence over generic commit-style templates.
- The running `spire-dev` stack is shared; do not run compose down. On 2026-09-13 the analyst
  stopped all four competing `quarkusDev` run workers. Keep them stopped: the analyst will start
  a worker for slice 8b's live proof. Service tests use their own Testcontainers resources. If a
  Docker-driving test loses a container unexpectedly, report possible external deletion to the
  analyst before investigating a production defect; the Gradle lock does not cover dev workers.
- **Never run concurrent Gradle test invocations in this worktree.** Run `./gradlew testFast`
  then `./gradlew testServices`, sequentially, using `--rerun-tasks` for measured evidence.
  On PowerShell use `.\gradlew.bat`. Targeted runs use `--tests` plus `--rerun-tasks`.
- Mutation checks use a scratch snapshot of the exact pre-mutation file, never a git restore.
  A mutation must change a production line, compile, and kill exactly one designated test in
  the selected run. Zero or more than one failure is not the required proof. Details below.
- Pure domain code in `spire-contract` and `spire-diff` stays framework-free. Extend module
  purity/architecture checks for the new SPI; provider dispatch belongs only in ADR-020
  composition roots. No forge-specific branches in policy, saga or resource code.
- Preserve existing line endings. Java uses four-space indentation, TS two. Split new React
  screens/components rather than growing the already-large settings components. Match existing
  validation, icons, auth, encryption and CSS contract conventions.
- No plausible synthetic rows. Isolated test fixtures use `TEST-`/`CANARY-` names. A live canary
  requires announcing its actual ids and exact cleanup `DELETE` before insertion; track remote
  issue/branch cleanup too. This round creates no data. Do not include generic destructive SQL
  against user-owned rows in a runbook and call it cleanup.
- Temporary files belong in the active session's scratchpad. Do not reuse a previous session's
  path. Persistent database backups and encrypted continuity evidence belong in the worktree's
  git-ignored `.handoff/` directory.
- Unknown capabilities, missing external evidence and test skips are visible outcomes. No stub
  phase reports success in production. Proposed tests below are not evidence until executed.

## Slice order and runnable exits

Each slice includes its own API/read surface, tests and applicable documentation. A migration-only
or interface-only commit can exist inside a slice, but is not its review exit.

| Slice | Depends on | Runnable exit | Review unit |
|---|---|---|---|
| 0 — Design and plan | None | These documents render, link to the updated issue, and are in an open draft PR. | **Opens the PR. Round 1 ends here.** |
| 1 — Repository registration and migration bridge | 0 reviewed | Register/read a repository with explicit accounts; existing reviews and runs still resolve identically during bridge. | Schema, bootstrap exchange, repository API and first detail view. |
| 2 — Repository cutover and per-kind webhooks | 1 | Repository screen is the entry point; workspace is absent from account form and runtime lookup; existing hook keys still verify. | All active resolvers, gateway kind routing and UI together. |
| 3 — Resolve people and edit bidirectional overrides | 2 | Enter a resolvable handle, reload its id-backed display, reject unresolved input; edit allow/deny on repository. | Directory adapters, registry/API and account/repository person controls. |
| 4 — Authorize `/fix` by effective push permission | 3 | A real inbound command reaches dispatch on measured write access with empty overrides; all four override cases work. | Permission adapters and both saga authorization layers. |
| 5 — First ticket, durable intake and suggest policy | 2, 3 | A signed issue label or rescan admits one durable item, visible on Work items; unknown/unlisted actors select nothing. | GitHub source, minimal profile registry, lifecycle/store/outbox and UI. |
| 6 — GitLab/Jira sources and recovery | 5 | Each source can admit a real fetched ticket through webhook or polling with the same actor rule. | Adapter parity, safe tracker writes and restart-safe scanning. |
| 7 — Full policy and dashboard approvals | 5 | Label changes and ceiling edits affect the next phase; a dashboard gate survives restart, resolves or expires. | Complete profile vector, transition checks, gates, Approvals and attention. |
| 8a — Prepared task to policy-controlled build | 4, 6, 7 | Three labelled prepared tasks stop, await approval or build; missing later capabilities stay visibly waiting. | Artifact handoff and dispatch/result join. |
| 8b — Publication hold and draft delivery | 8a | Item-linked runs await a current delivery permit; standalone `/fix` still pushes automatically, re-proved live. | Worker/publisher/watchdog hold, native draft delivery and standalone regression proof. |
| 9 — External answers and human takeover | 8b | Tracker/PR answers resolve the same gate; human activity suspends automation and holds publication through restart. | Authenticated ingress, gate channel adapters, run control/publisher hold and resume. |
| 10 — Integrated evidence and release documentation | 1–9 | Acceptance proofs and mutation evidence are recorded; supported journeys demonstrated and remaining limitations named. | Final integration tests, runbook and measured status updates. |

The §11.1 review dependency is discharged. Slices 9 and 10 retain their numbers; 8b sits between 8a and 9.

Slices are sequential review boundaries, not a request to launch parallel test runs or delegated
work. Some dependencies are independent for scheduling, but this plan requires no additional pane.

## File map and contracts

Abbreviations used only in the task file lists:

- `C` = `spire-contract/src/main/java/dev/codespire/contract/`.
- `O` = `spire-orchestrator/src/main/java/dev/codespire/orchestrator/`.
- `G` = `spire-gateway/src/main/java/dev/codespire/gateway/`.
- `U` = `spire-ui/src/`.
- Java tests use the corresponding `src/test/java` package. Every new test class in this plan
  lives there unless the `spire-e2e` module is named explicitly.

| Surface | Create / modify |
|---|---|
| Repository | Create `O/repository/RepositoryRegistry.java`, `RepositoryAccounts.java`, `RepositoryResource.java`, `RepositoryMigrationBridge.java`; migrations in orchestrator `src/main/resources/db/migration/`. Modify `O/provider/ProviderRegistry.java`, `ProviderResource.java`, `ProviderInput.java`, `ProviderView.java`, `ScmProvider.java`, `ProviderClients.java`. |
| Active account consumers | Modify `O/provider/ReviewProviderResolver.java`; `O/pipeline/IntegrationSaga.java`, `ReviewRerunService.java`; `O/ingress/ManualRegisterResource.java`; `O/prompt/PromptSampleRenderer.java`; `O/factory/MachineAccounts.java`, `RunResource.java`, `FixRunDispatcher.java`, `FactoryPullRequests.java`, relevant credential assemblers and non-secret serving views. Confirm exact call sites by search before edits. |
| Gateway | Modify `G/RegistryWebhookEdge.java`, `WebhookProviders.java`, `WebhookCommands.java`, `registry/WebhookRepo*.java`; create registry snapshot outbox/publisher and work integration publisher; gateway migrations/configuration; all three SCM resource routes. |
| Identity/permission | Create `C/port/ActorDirectory.java`, `RepositoryPermissionSource.java`; `C/scm/ResolvedActor.java`, `RepositoryPermission.java`; `O/provider/ActorResolutionResource.java`; `O/factory/FixAuthorization.java`, `FixPermissionService.java`; implementations in existing `spire-scm-{github,gitlab,bitbucket}` packages. |
| Work-source SPI | New `spire-worksource/src/main/java/dev/codespire/worksource/WorkSource.java`, `WorkItemRef.java`, `LabelEvent.java`, capabilities/paging/actor records. Three `spire-worksource-{github,gitlab,jira}` adapter modules; reuse/refactor existing issue clients and `spire-http/PinnedJsonClient` transport. |
| Work-item lifecycle | Create `C/lifecycle/WorkItemLifecycle.java`, work-item state/command/value types; extend `C/event/DomainEvent.java`; create work integration/command wire hierarchies and `WorkItemIds`. Update envelope decoding, `O/pipeline/DomainEventSink.java`, `O/eventstore/JdbcEventStore.java`. |
| Orchestration | Create `O/workitem/WorkItemStore.java`, `WorkItemSaga.java`, `WorkItemTransitions.java`, `WorkItemProjection.java`, `WorkItemResource.java`, `WorkItemOutbox.java`, `WorkItemDispatcher.java`; `O/worksource/WorkSourceRegistry.java`, `WorkSourceClients.java`, `WorkSourceScanner.java`, resource and reconciliation classes. |
| Policy/approval | Create `O/autonomy/AutonomyRegistry.java`, `AutonomyResource.java`; pure policy values/resolver in contract lifecycle package; `O/workitem/GateExpiry.java`, `GateResource.java`, `GateAnswerRouter.java`, `HumanTakeover.java`; expand attention queries. |
| Run bridge | Modify `O/factory/RunResultSaga.java`, `FactoryRunProjection.java`, `FactoryPullRequests.java`, run dispatch assembly; contract run records/control; `spire-run-worker/.../RunControlListener.java`, `RunLauncher.java`, `OrphanWatchdog.java`; publication-hold handling in runtime/publisher. |
| UI | Create focused `U/components/repositories/`, `workItems/`, `approvals/`, `autonomy/` components and API modules. Modify `App.tsx`, `api.ts`, `ProviderFormModal.tsx`, `AccountCredentialFields.tsx`, `ReviewerFieldsSection.tsx`, `AccountsCells.tsx`, `SettingsWebhookRepos.tsx`, serving hooks and `AttentionBell.tsx`. |
| Build/docs | `settings.gradle.kts`, module builds, root test tiers, `spire-arch` tests, contract snapshots, Kafka provisioning, service `application.yml`, both packaged Compose variants and Helm/kustomize resources where channels/config require them; `docs/{DECISIONS,CONTRACT,DATA-MODEL,SCM-MAPPING,SECURITY,SMOKE-TEST,HISTORY,UNVERIFIED}.md`, factory docs and `CLAUDE.md`. |

New filenames and method names are proposed contracts. Check repository state at the start of each
slice; do not copy obsolete line numbers from historical plans. If a composition root moves, update
the plan and its architecture allowlist together.

## Slice 0 — publish this planning round

**Files:** only this document and its linked design.

- [x] Confirm branch and clean initial worktree, read the complete brief, re-fetch issue #114 and
  inspect ADR-041, factory requirements and the actual run/account/webhook implementation.
- [x] Write the aggregate decision and why; repository migration; policy/identity/gate rules;
  ordered runnable slices; exact proof and mutation obligations for criteria 1–7.
- [x] Record under-specification in design §11 rather than choosing silent fallback behavior.
- [x] Check Markdown links, `git diff --check`, exact two-file scope and absence of secrets/data.
  No Gradle/UI suites are warranted for this documentation-only change.
- [x] Commit `Plan Factory M3 work items, labels and gates`, with a body explaining the new
  registry and workflow decisions and the acceptance-proof plan. Push with
  `git push -u origin feat/factory-m3-work-items`.
- [x] Write the PR body as a real UTF-8 scratchpad file and use `gh pr create --draft --base master
  --head feat/factory-m3-work-items --title "Factory M3 — work items, labels and gates"
  --body-file <scratchpad-body>`. Link issue #114 without claiming to close implementation work.
- [x] Verify the remote head equals the commit, PR is draft against master, and only these two
  documents are in its diff. Report the PR number and the design questions. **Stop Round 1.**

## Slice 1 — register a repository while preserving existing resolution

**Files:** repository registry/bridge/resource and migration files in the map; gateway snapshot
outbox; first repository UI; ADR-042 draft in `docs/DECISIONS.md`.

**Produces:** `RepositoryAccounts.resolve(repositoryId, role)` and a non-secret serving view;
`POST/GET /api/repositories`; versioned metadata-only registration snapshots.

- [x] **Before any new migration reaches dev:** take a full `pg_dump`, validate the archive and
  record its path/hash. Use the exact PowerShell commands below; the binary dump never passes
  through PowerShell text redirection. The running stack holds real accounts, encrypted source
  credentials and review/run history. Preserve its existing matching keyset securely outside git.

The existing `.handoff/spire-dev-pre-m3-2026-09-13.dump` satisfies this prerequisite (182 objects,
492,480 bytes, verified 2026-09-13). **Do not take another dump for slice 1.** For a future upgrade,
run this repeatable command from the worktree root; `.handoff/` is git-ignored and survives sessions.

```powershell
$m3Handoff = Join-Path (Get-Location).Path '.handoff'
[void](New-Item -ItemType Directory -Force -Path $m3Handoff)
$m3Dump = Join-Path $m3Handoff ('m3-before-slice1-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.dump')
$m3Process = [Diagnostics.Process]::new()
$m3Process.StartInfo = [Diagnostics.ProcessStartInfo]::new('docker')
$m3Process.StartInfo.UseShellExecute = $false
$m3Process.StartInfo.RedirectStandardOutput = $true
@('exec','spire-postgres','sh','-c','exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc') | ForEach-Object { $m3Process.StartInfo.ArgumentList.Add($_) }
$m3File = [IO.File]::Create($m3Dump)
try { [void]$m3Process.Start(); $m3Process.StandardOutput.BaseStream.CopyTo($m3File); $m3Process.WaitForExit(); if ($m3Process.ExitCode -ne 0) { throw 'pg_dump failed' } } finally { $m3File.Dispose(); $m3Process.Dispose() }
Get-FileHash -LiteralPath $m3Dump -Algorithm SHA256
```

Validate archive listing with `pg_restore --list` using a one-shot container with only this
handoff directory mounted read-only, without changing the running stack:

```powershell
docker run --rm --mount "type=bind,source=$m3Handoff,target=/backup,readonly" postgres:18.4-alpine pg_restore --list "/backup/$([IO.Path]::GetFileName($m3Dump))"
if ($LASTEXITCODE -ne 0) { throw 'Backup archive validation failed' }
```

- [x] Add `scripts/verify-dev-credential-continuity.ps1` as the read-only local operational probe.
  It uses the actual dev keyset and `EncryptionService`, with `provider:<id>` for account secrets
  and `context-provider:<id>` for remaining legacy context secrets. Capture encrypted baseline
  evidence (including source→account references) into .handoff/; Compare re-decrypts actual rows
  and checks equality in memory. Never write/log plaintext, keysets or unkeyed secret hashes.
  Execute Capture before migration; slice 2 must execute Compare on the real dev rows:

```powershell
$m3Handoff = Join-Path (Get-Location).Path '.handoff'
.\scripts\verify-dev-credential-continuity.ps1 -Mode Capture -Snapshot (Join-Path $m3Handoff 'm3-real-credentials.bin')
.\scripts\verify-dev-credential-continuity.ps1 -Mode Compare -Snapshot (Join-Path $m3Handoff 'm3-real-credentials.bin')
```

Capture has already established the encrypted baseline for 9 real credential/reference entries.
Reuse it for Compare; Capture deliberately refuses to overwrite existing evidence. The measured
comparison in slice 1 is pre-upgrade only; slice 2's comparison after cutover remains required.

- [x] Reconcile `CLAUDE.md` and `docs/UNVERIFIED.md` **in this slice**: the live M2 chain on
  `artyomsv/spire-test#31`, runs `3987682681:1` and `3987682176:1`, resolved threads and persisted
  verdicts was measured on 2026-09-12. Keep the automated GitLab gap as its own open entry:
  `RunUnitSpec` has no network field, so run units cannot reach that test stack's GitLab.

- [x] Apply the accepted bridge-only org enrollment decision. Write failing migration/service tests:
  `RepositorySchemaMigrationTest.preservesAccountIdsCredentialsAndContextReferences`,
  `RepositoryMigrationBridgeTest.replaysGatewaySnapshotWithoutDuplicateBindings`,
  `RepositoryMigrationBridgeTest.leavesConflictingOriginsPending`, and
  `RepositoryResourceTest.registersARepositoryWithExplicitRoleBindings`.
- [x] Add repository and binding tables, revision checks, referenced-delete protection and
  migration snapshot storage. Preserve UUID/AAD and V59 source recovery. Do not relax the old
  account key before the bridge can preserve assignments.
- [x] Publish/consume real gateway metadata with stable snapshot revision and outbox retries.
  Provision `cs.registry-integration`, keyed by registration id. Do not read gateway SQL from the
  orchestrator or send its webhook secret across this channel.
- [x] Add repository registration/detail UI backed by the API, including empty/disabled/pending
  roles. Show workspace on the repository. During this slice the old account field is explicitly
  labelled legacy; it is removed at slice 2's cutover.
- [x] Prove old review/run resolution equals new bindings for migrated fixtures, across restart,
  multiple roles, hosts and nested namespaces. Duplicate source snapshots must not recreate
  an account an operator has already rebound.
  Migration requires origin evidence from explicit registration metadata or persisted PR URLs;
  a workspace match alone cannot choose credentials. Unknown or conflicting origins remain
  pending for explicit repair, including factory-only history without PR origin evidence.
- [x] Mutation: omit factory-role filtering in the binding resolver; run only
  `RepositoryAccountsTest.reviewerNeverReceivesTheFactoryCredential`. Expect one assertion failure
  with distinct `TEST-` credentials, restore snapshot, rerun green. Also kill the origin-match
  guard with `rejectsAnAccountFromAnotherOrigin` and migration AAD/id preservation with the
  migration test above, each as a separate mutation.
- [x] Run relevant service/UI tests sequentially, demonstrate registration through the real API
  in the isolated test stack, update ADR-042/upgrade notes, commit the slice for review.

Measured 2026-09-13: forced `testFast` then `testServices`, 3005 Java tests / 348 suites with zero
failures and one existing Windows symlink skip; 620 UI tests and the UI build passed. Forty
distinct production mutations failed exactly one targeted test and passed after restoration.
Details: `.claude/reviews/global/factory-m3-slice1.md`. The dev stack was not rebuilt; slice 2's
post-cutover comparison against the real encrypted baseline remains required.

## Slice 2 — cut over to repository ownership and per-kind hooks

**Files:** all active account consumers, gateway registry/edge/resources, account DTOs/forms,
repository UI and migrations; ADR-042 final decision text.

**Produces:** runtime resolution solely by explicit repository binding; no account workspace in
new API/form; gateway key plus scope plus event-kind validation.

- [ ] Write failing `RepositoryResolverCutoverTest.allDispatchPathsUseTheSelectedRepository`,
  `RepositoryResolverCutoverTest.unmappedLegacyReviewCannotDispatch`,
  `RepositoryWebhookKindsTest.preservesLegacyKeyAndRejectsWrongKind`,
  `RepositoryWebhookKindsTest.refusesASecondWebhookForTheSameKind`, and criterion 7 tests below.
- [ ] Change every resolver caller; search `resolveByWorkspace`, `registration`,
  `providers.resolve`, `MachineAccounts.resolve` and serving API usages. Include manual/rerun,
  prompt, fix and result-time PR proposal paths, not only the HTTP run endpoint.
- [ ] Drop the old UNIQUE and workspace-by-role CHECK; finish migration mappings, remove active
  account workspace reads. Keep the populated column as rollback evidence until slice 10. Retain scalar
  role checks. Refuse origin/type edits on referenced accounts. Do not add global role uniqueness.
- [ ] Upgrade all three keyed SCM edges to product event-kind filtering. Preserve keys, secrets,
  scope and rejection history during migration. Wire FACTORY activity separately from REVIEWER
  commands; reserve ISSUE scope for source registration. Unknown kinds fail closed. End org
  auto-enrollment. Add `UnregisteredRepositoryAttentionTest.namesRepositoryOriginAndRegistration`:
  a verified event for an unregistered repo raises attention and a Register action pre-filled
  with repo, origin and incoming registration. Mutate its attention write, isolate the test,
  expect exactly one failure, restore; a silent drop does not satisfy cutover.
- [ ] Replace the webhook-row screen with repository detail and one hook control per kind. Support
  registration without hooks, retries after partial save and legacy org/deep-link navigation.
  Remove workspace from `ProviderInput`/`View` and `AccountCredentialFields`, not merely hide CSS.
- [ ] Mutation checks for criterion 7 are specified in the matrix. Additionally kill the scope
  comparison with `RepositoryWebhookKindsTest.validSignatureCannotCrossRepositoryScope`; use
  the same provider and a valid signature so a different guard cannot mask the mutation.
- [ ] Run migration, gateway, orchestrator and UI verification in sequence. Search for remaining
  active workspace-only lookups; historical docs/bridge mappings are the only permitted matches.
  Add `spire-arch/AccountWorkspaceIsUnusedTest.noProductionCodeReadsLegacyAccountWorkspace`:
  inspect production source/SQL including SELECT-star mappings so retained workspace cannot silently
  re-enter resolution. Mutation: restore a workspace read in the repository resolver; exactly that
  targeted test fails. Restore from scratch. Confirm real dev-row credential continuity with the
  Compare command below after cutover; compare all baseline account ids and context references,
  report added/removed rows separately, and never accept fixture-only evidence.
  Update serving API/upgrade contracts and commit the runnable cutover.

## Slice 3 — resolve a person with the selected account

**Files:** directory SPI/adapters, actor-resolution resource, account policy storage, repository
override registry, person controls; ADR-044 identity half.

**Produces:** exact identity resolution with typed errors; stable-id persisted allowlists and
`ALLOW|DENY` repository fix overrides with readable display metadata.

- [ ] Apply accepted capability errors and disambiguated selection for Bitbucket/Jira person lookup.
  Write criterion 6 tests and `ActorResolutionResourceTest.usesOnlyTheSelectedAccountsCredential`,
  `ActorResolutionResourceTest.refusesAnAmbiguousMatch`,
  `ActorResolutionResourceTest.rechecksSubmittedIdentityOnSave`,
  `ActorDisplayTest.renamedHandleKeepsTheStoredId`.
- [ ] Implement directory adapters via configured account origin/auth. Exact match and stable id
  are required; no username-to-id string coercion and no first search result. Implement by-id
  refresh with stale display metadata on failure; never send a secret in a response.
- [ ] Add the repository override table and UI controls. One actor has one override; a contradictory
  edit is a version conflict, not “last array entry wins.” Migrate verified legacy stable-id grants
  to their real repository mappings. Flag unresolved legacy handles without granting authority.
- [ ] Add account and source person pickers with unresolved/error states. Show handle plus policy
  effect; a count may accompany people but cannot replace their identities.
- [ ] Kill criterion 6 mutations, then separately kill account credential selection and returned-id
  verification with the corresponding targeted tests. Restore and rerun each case green.
- [ ] Run adapter unit tests, resource persistence test and UI form round-trip; update SCM-MAPPING
  identity capability notes and per-forge UNVERIFIED entries (what was measured, which forge, and remaining proof), then commit. At this exit overrides are editable; slice 4 activates
  their new permission fallback without changing unrelated review policy.

## Slice 4 — measure repository push access for `/fix`

**Files:** permission SPI/adapters, `FixAuthorization`, `FixPermissionService`, both guards in
`IntegrationSaga`; authorization tests and ADR-044.

**Produces:** explicit deny → grant → fresh effective push permission, still subject to existing
target, identity, observe-mode, spending and fix-chain guards.

- [ ] Write the six distinct criterion 5 cases, plus inherited-rights/unknown-response adapter
  tests. Read the official endpoint contracts linked by design §4.3 before writing fixtures.
  Test Bitbucket's effective endpoint and pagination, not its explicit-grant endpoint.
- [ ] Implement adapters returning `CAN_PUSH|CANNOT_PUSH|UNKNOWN`, binding repository and stable
  user. Enforce timeout/rate limits and origin-pinned requests; do not fall back to another token
  when the assigned reviewer cannot inspect permission. No stale positive permission cache.
- [ ] Route `/fix` around the old common author-list guard into `FixAuthorization`. Keep self-loop,
  observe-only and registration/target checks. Do not change `/review` or `/finding` semantics.
- [ ] Test through `IntegrationSaga.on` using the actual normalized command so a private
  `FixAuthorization` unit test cannot conceal the outer guard. Assert dispatch count and the
  refusal reason, with legitimate thread/finding/account prerequisites in every permission case.
- [ ] Prove override grant still cannot push a fork/trunk or exceed either FR-F32 cap. Retain
  corresponding M2 regression suites; live-author permissions never substitute for push target
  validation. Known stale PR-state/shared-branch debt stays documented unless explicitly fixed.
- [ ] Kill each criterion 5 mutation in its isolated method, then run the class green. Exercise all
  three adapter contracts and inherited-access cases; record live-token limitations without
  changing the operator's account privileges. Add each per-forge permission behavior as its own UNVERIFIED entry with measurement and forge. Commit the authorization slice.

## Slice 5 — admit the first ticket and display durable bookkeeping

**Files:** `spire-worksource` and GitHub arm, source registry/scanner, minimum profile registry,
work-item lifecycle/store/saga/outbox/resource, wire/config changes, Work items screen; ADR-043.

**Produces:** authenticated label intake and explicit rescan; attributable label reconciliation;
one durable suggest item without a run or mirrored issue content.

- [ ] Write `WorkItemIntakeIT.signedLabelCreatesOneVisibleItemAcrossRedelivery`,
  `WorkItemStoreTest.restartRehydratesOnlyWorkflowMilestones`,
  `WorkItemStoreTest.rollbackLeavesNoGateEventOrOutboxEffect`,
  `WorkItemProjectionTest.containsNoTrackerContentColumns`, and criterion 3 tests.
- [ ] Add the SPI/module dependencies and build purity/licensing checks. Reuse the issue client's
  HTTP/auth implementation through a read facade and a separate work writer; no write methods on
  a context-provider interface. GitHub candidates/fetch/label audit use bounded pagination.
- [ ] Add source registration with explicit account and repository, typed actor allowlist and
  health/cursor state. Add minimum versioned profile/mapping/ceiling registry necessary for a
  real suggest admission; do not embed profile behavior in a forge adapter.
- [ ] Implement work-item ids/generations, lifecycle decide/fold, typed event decoding and dedicated
  work topics. Make JDBC event append, projection, dedupe and outbox one real transaction.
  Route work events away from review history; test `WorkItemEventRoutingTest.neverWritesAReviewRow`.
- [ ] Extend the keyed gateway edge for a bound issue scope; signed delivery and scanner events
  enter the same reconciliation path. Store control facts only. Start polling with conservative
  unknown attribution when full history cannot be proven.
- [ ] Render a paginated Work items screen/detail from persisted workflow fields, ignored-label
  reasons and a live tracker link; show tracker fetch errors separately from workflow state.
- [ ] Kill criterion 3 mutations, event-route isolation and rollback guards individually. For
  rollback mutate the shared-transaction use and inject a failure after event append but before
  projection/outbox completion; a compile failure is not a valid transaction test.
- [ ] Run SPI/adapter tests and service intake/restart/UI proofs; commit the first ticket slice.

## Slice 6 — source parity, safe writes and downtime recovery

**Files:** GitLab/Jira arms, shared provider transport, source clients/scanner, tracker ingress,
source settings UI, source capability docs.

**Produces:** same source contract for three trackers, resumable polling and idempotent comment/
transition effects, with unsupported audit/approval channels visible.

- [ ] Write `GitLabWorkSourceTest.reconstructsCurrentLabelApplierAcrossPages`,
  `JiraWorkSourceTest.attributesOnlyTheActualAddedLabel`,
  `WorkSourceRecoveryIT.backfillWithoutAuditRemainsUnattributed`,
  `WorkSourceRecoveryIT.removeThenReaddCannotReuseAnOldAllowedActor`,
  `WorkSourceRecoveryIT.restartResumesAfterCommittedCursor`.
- [ ] Implement candidate/read/comment/transition/label-event operations and supported capability
  reports for each adapter. Validate real Jira transition ids rather than treating arbitrary
  status names as commands. Respect origin/auth compatibility and source account disable/rotation.
- [ ] Add authenticated tracker webhook normalization with source-bound project checks. If the
  deployed Jira hook cannot be authenticated using a supported mechanism, support polling and
  report the webhook limitation. Never accept an unverified hook just because its URL has a key.
- [ ] Reconcile current label set with additions/removals and stable event ordering; exhaust required
  history pages or return unknown. No actor fallback to issue reporter/editor. Commit scan cursors
  with reconciliation and cap each sweep; retries cannot duplicate items or lose pages.
- [ ] Implement source comments/transitions through outbox effects with deterministic markers and
  uncertain-write handling. `WorkSourceEffectsTest.retryFindsThePreviouslyWrittenComment` must
  observe a successful remote write followed by a client timeout before retry.
- [ ] Kill audit-completeness, remove/re-add and source-scope guards in targeted tests. Test credential
  errors as health failures, not issue deletions. Show the supported operations on source settings.
- [ ] Run all three arm suites plus service recovery tests sequentially; record which token families/
  webhook variants have only documentation/WireMock evidence, and commit the parity slice.

## Slice 7 — re-resolve policy and persist dashboard approvals

**Files:** full policy resolver/registry/UI, transitions, gate storage/resource/expiry, attention,
Approvals screen; ADR-045 policy decision.

**Produces:** checked profile vectors, visible clamps, current-policy phase decisions, durable gates,
expiry and operator answers.

- [ ] Apply the accepted profile precedence and meet rule. Write criterion 2 and 4 tests plus
  `AutonomyProfileTest.requiresDistinctProfilePrecedence`,
  `AutonomyProfileTest.incomparableVectorsMeetWithoutWideningEither`,
  `AutonomyProfileTest.omittedPhaseIsOff`,
  `WorkItemPolicyIT.lowestEligibleLabelWins`,
  `WorkItemPolicyIT.profileEditCannotWidenAnAdmittedVersion`,
  `WorkItemPolicyIT.removedLabelStopsTheNextTransition`.
- [ ] Implement versioned vector/precedence validation, current allowed label selection, pinned
  admission version and component-wise restriction across EVERY eligible label, not only the lowest-precedence label. Record selection/clamp/reason with policy revision.
  Include source disabled/allowlist removed, stricter caps and protected-path floor cases.
- [ ] Call the transition service from every entry point named in design §6.2. Re-read evidence
  outside the transaction and compare registry revision inside it; stale external data must not
  become authority after a newer local edit. Include outbox retries and operator resume.
- [ ] Implement gate open/resolve/expiry atomically with event/outbox and reservations. Write
  `GateResourceTest.concurrentAnswersProduceOneResolution`,
  `GateExpiryTest.exactDeadlineRefusesALateApproval`,
  `GateExpiryTest.restartExpiresOpenGateAndReleasesReservation`,
  `GateResourceTest.viewerCannotResolveAGate` and `GateResourceTest.replayedAnswerIsIdempotent`.
- [ ] Render Approvals and integrate attention using current OPEN/expired/clamped conditions. A
  resolved gate disappears from open views. UI submits expected version and displays 409/503
  honestly; status union, renderer and filters change together.
- [ ] Kill criterion 2/4 mutations and each concurrency/expiry/auth guard with isolated tests; use
  an injected clock and real PostgreSQL interleavings, not sleeps against the live scheduler.
- [ ] Run contract, orchestrator, UI tests sequentially; demonstrate restart and ceiling downgrade
  through real APIs in the test stack; update ADR-045 and commit.

## Slice 8a — prepared task to policy-controlled build

**Files:** artifact reference handoff, dispatcher, run-result bridge, item/run FK metadata,
`FactoryPullRequests`, `PullRequestSink` and all three arms, work-item UI.

**Produces:** criterion 1's accepted M3 journeys; actual one-task build and policy-aware delivery
boundaries. No production verifier is invented to reach the delivery test cases.

- [ ] Apply the accepted manual-artifact/plan-build proof boundary. Missing capabilities remain
  waiting. Correct the eight-phase diagram in `docs/factory/AUTONOMY.md` and affected PRD/
  architecture diagrams in this slice: `intake → spec → plan → build → verify → deliver → review
  → land`. Record the order in ADR-045; its acceptance dependency is discharged.
- [ ] Write `WorkItemJourneyIT.threeProfilesProduceDifferentVisibleJourneys` and UI journey test
  from the acceptance matrix. Use real persisted policy/source/item data; scripted execution is
  permitted only in tests and identified as such. Also write
  `WorkItemRunBridgeTest.itemRunCannotUseStandaloneAutomaticProposal`,
  `WorkItemRunBridgeTest.duplicateResultAdvancesTheItemOnlyOnce`,
  `WorkItemRunBridgeTest.ceilingChangesBeforeDeliveryPreventTheProposal`.
- [ ] Fetch human-supplied tracker artifact references/digests and bind gates to them. Missing or
  changed artifacts require input/new approval. Dispatch through the existing run assembly/caps
  using the repository's selected FACTORY identity and stable item/attempt linkage.
- [ ] Persist an effect claim before dispatch, recheck current policy before publishing and make
  run/result association recoverable after crash. Do not reset attempts on re-admission or charge
  the same run result twice. Existing standalone runs remain outside work-item gates.
- [ ] Commit the artifact handoff and dispatch/result join for review. Before 8b provides a
  trustworthy publication hold, item-linked real execution stays capability-unavailable; the
  runnable 8a policy proof uses the explicit test execution boundary, never an auto-pushing M2
  run advertised as held. Slice 8b closes the real-container execution proof.

## Slice 8b — publication hold and draft delivery

**Depends on:** 8a. Slices 9 and 10 keep their numbers.

**Two-part exit:** item-linked runs hold publication until a current delivery permit, through
restart and orphan recovery; **standalone `/fix` still pushes automatically**, re-proved live on
`artyomsv/spire-test`. Unit/fixture tests do not discharge the second half.

**Files:** work-ready/control/results, worker durable state, publisher/runtime finalization,
orphan salvage, item delivery orchestration, sink draft support and UI states.

- [ ] Implement the accepted work-ready/delivery-permit handshake from design §6.3. An item-linked
  run starts with publication held, checkpoints without pushing, persists awaiting-delivery and
  releases active compute. Resume only the trusted publisher on a current delivery permit; keep
  workspace and hold through restart. Add contract/result/control fields, runtime lifecycle and
  UI states together; never send a permit through a repository-writable file. Deduplicate charge
  reporting across work-ready and terminal results. Add
  `WorkItemDeliveryIT.deliverOffNeverPushesTheBuiltBranch`,
  `WorkItemDeliveryIT.deliveryPermitPublishesWithoutRebuilding`, and
  `WorkItemDeliveryIT.workReadyAndFinishedDoNotDoubleCharge` in the worker service tier.
- [ ] Prevent item-linked BUILD results from falling through `FactoryPullRequests.propose` before
  their deliver transition. Implement policy-controlled PR opening, observed reviewer result and
  land readiness according to the accepted order. Never mark missing review/verify as passing.
- [ ] Extend the sink with explicit draft capability/request semantics and update constructors,
  withers, snapshots and each adapter. Unsupported `draft_pr` visibly blocks delivery. Do not
  send a regular PR and label it a draft. Preserve find-by-head idempotency and existing FIX
  source-branch semantics.
- [ ] Prove actual run execution separately in `WorkItemRunJourneyIT.preparedItemBuildsAndWaitsForVerification`
  (`spire-run-worker` service tier): real containers and local test origin plus provider fixture,
  distinct from a live-forge proof. This suite must share the existing Docker serialization lock.
  Delivery tests supply valid prior phase results through an explicitly test-only phase driver;
  the production handler for an unavailable verify capability continues to block honestly.
- [ ] Kill criterion 1 mutations and the standalone-proposal bypass separately. Run relevant run,
  orchestrator, sink adapter and UI suites sequentially. Also remove the initial publication hold
  and isolate `WorkItemDeliveryIT.deliverOffNeverPushesTheBuiltBranch`: exactly one test must fail
  on the real remote's changed head. Restore and rerun green.
- [ ] Re-prove a standalone `/fix` live on `artyomsv/spire-test`, using an actual open finding and
  the same command → worker → publisher → next review → resolved thread/persisted verdict chain
  proved by runs `3987682681:1` and `3987682176:1`. Record actual new run/PR ids, source head before/
  after and verdict observations. A synthetic fixture or unit test is not this proof. Announce
  any TEST-/CANARY-prefixed setup and its exact cleanup first. Do not close 8b without this result.
- [ ] Commit 8b independently after both exit obligations pass.

## Slice 9 — answer outside the dashboard and take over safely

**Files:** gate channel routing, normalized tracker/PR activity, takeover/resume, durable run control
publication hold, publisher/runtime/orphan finalization, UI suspended state and attention.

**Produces:** one gate resolution path across three channels; takeover persists and suppresses new
effects, including salvage publication after a restart.

- [ ] Write `GateChannelsIT.dashboardTrackerAndPrReviewResolveTheSameGate`,
  `GateChannelsIT.prReviewCannotApproveAPlanGate`,
  `GateChannelsIT.staleHeadAndDismissedReviewCannotApprove`,
  `HumanTakeoverIT.humanCommentSuspendsUntilOperatorResume`,
  `HumanTakeoverIT.knownMachinePushDoesNotTakeOver`,
  `HumanTakeoverIT.gateReplyIsNotReprocessedAsTakeover`.
- [ ] Normalize source delivery identity/channel, actor and artifact/head. Re-read current
  permission/review evidence before approval. Dashboard OIDC authority and tracker actor ids
  must never be compared in the same namespace. Unknown approval capabilities are disabled.
- [ ] Implement human activity classification from real linked branch/PR observations. Persist
  takeover, supersede gates and stop unstarted effects transactionally. Record resume actor/note;
  re-resolve policy and head before allowing a fresh action. Transfers retire rather than resume.
- [ ] Design the publication hold through the existing `RunCommand` control vocabulary and worker
  durable state; inspect actual runtime/finalization interfaces before editing. Hold must survive
  queued delivery, restart and orphan recovery. Carry the hold to a trusted publisher control
  channel rather than trusting a flag in a repository-writable file. Preserve local work and
  standalone cancel semantics. Record the unavoidable already-in-progress push race honestly.
- [ ] Add `PublicationHoldIT.takeoverPreservesWorkWithoutPushingOnCancel` and
  `PublicationHoldIT.orphanRecoveryKeepsTheDurablePublicationHold` in `spire-run-worker`, using a
  real remote whose head is measured before/after and a deterministic pause before publication.
  These are not satisfied by asserting that `CancelRun` was emitted.
- [ ] Add `WorkItemRetirementIT.transferRetiresOldIdentityAndInvalidatesOpenGate` and
  `WorkItemRetirementIT.sourceOutageDoesNotPretendTheIssueWasDeleted`.
- [ ] Kill gate scope/head, human-vs-machine identity, expiry-on-answer, retired-state and
  publication-hold guards independently. A mutation killed by an earlier unrelated refusal is
  invalid; prove the fixture reached its intended production line.
- [ ] Run gateway, orchestrator and Docker worker suites one at a time, then UI tests. Document
  native approval capabilities and takeover race limits. Commit the cross-channel slice.

## Acceptance proof matrix

All methods/classes in this matrix are **tests to add**, not claimed existing coverage. `O-test`
means `spire-orchestrator/src/test/java/dev/codespire/orchestrator/`. Tests exercise the public
resource/consumer path plus persisted outcomes; helpers may stub external HTTP at adapter edges.
Every integration proof has a visible UI assertion or a matching component test where required.

| # | Ticket exit criterion and exact proof | Production mutation and isolated expected failure |
|---|---|---|
| 1 | **Three visibly different journeys.** `O-test/workitem/WorkItemJourneyIT.java#threeProfilesProduceDifferentVisibleJourneys`: label three otherwise identical `TEST-` prepared tasks under suggest/assisted/autonomous with ceiling autonomous, inspect persisted timeline/API: suggest stops before build; assisted waits at plan approval with zero runs; autonomous starts one build without approval. Then approve assisted and assert its recorded human decision and single dispatch. Assert exact phase/gate/effect fields; missing verify remains waiting. `U/components/workItems/WorkItemJourney.test.tsx` → `renders distinct suggest assisted and autonomous journeys` proves visible differences. Draft/regular PRs are separate delivery tests with a test-only prior-phase driver. Scope accepted in Round 2; runtime publication proof belongs to 8b. | First mutant: in the real transition policy branch change `approve` to proceed without opening its gate; run only the Java method, expect exactly one failed test. Restore. Second mutant: render all journey status labels as the same label; run only the named vitest case, expect one failure. Restore. A test that only compares three profile names is insufficient. |
| 2 | **Above-ceiling label clamped and says so.** `O-test/workitem/WorkItemPolicyIT.java#aboveCeilingLabelRecordsAndDisplaysClamp`: request autonomous at assisted ceiling; assert effective vector, durable clamp event after reload, attention API row and detail reason. `U/components/workItems/WorkItemPolicy.test.tsx` → `shows requested and effective profiles with the clamp reason`. | Mutate the effective-profile meet to retain requested authority; isolate Java method, one failure. Restore. Separately omit the clamp event/attention projection write; same isolated method must fail once. Restore. Mutate the UI clamp message to empty and run the named UI case for one failure. Each proves a different half of “and says so.” |
| 3 | **Unlisted and unattributable appliers ignored.** `O-test/workitem/WorkItemIntakeIT.java#unlistedLabellerSelectsNoProfile` and `#unattributedCurrentLabelSelectsNoProfile`, each using a mapped label that would otherwise dispatch, valid source/account/ceiling and assertions of ignored reason plus no run effect. Add `#allowedAttributedLabellerCanSelect` as positive control. | Delete the actor-membership check; run only `unlistedLabellerSelectsNoProfile`, one failure. Restore. Delete the attribution check; run only `unattributedCurrentLabelSelectsNoProfile`, one failure. Fixture for the latter has an actor hint that would pass membership but origin UNATTRIBUTED, so the origin guard alone distinguishes it. Use additional no-id test for real missing actor. Never combine both negative cases into one count. |
| 4 | **Lowering ceiling stops an in-flight item at next phase.** `O-test/workitem/WorkItemPolicyIT.java#loweredCeilingStopsAnInFlightItemAtTheNextPhase`: admit and start under higher policy, commit a lower ceiling with next phase off, then send the prior phase's result. Assert no next effect/PR and persisted stop/reason on detail. `#ceilingChangeBeforeGateAnswerRequiresANewDecision` covers waiting items. | Replace the transition's current ceiling lookup with admission-time ceiling, preserving all other checks; run only the first method, one failure. Restore. Separately bypass policy recheck in gate resolution and isolate the second method, one failure. The mutation must not be masked by also removing a label or disabling an account in the fixture. |
| 5 | **Push access without a list, refusal without access, overrides both ways.** `O-test/pipeline/FixPermissionSagaTest.java` methods `writerWithEmptyOverridesDispatches`, `writerOutsideTheLegacyAuthorListDispatches`, `readerWithoutOverrideIsRefused`, `explicitGrantLetsAReaderDispatch`, `explicitDenyStopsAWriter`, `unknownPermissionCannotDispatch`. Invoke the real saga entry; assert one/zero dispatch and exact decision reason. Each forge also adds `*RepositoryPermissionSourceTest#inheritedWriteAccessIsRecognized` and `#unknownResponseCannotGrant`. | Six separate compiling mutants: default unlisted to deny; restore the legacy outer author check for `/fix`; grant the reader result; skip explicit ALLOW; skip explicit DENY; map UNKNOWN to allow. Select the corresponding single method for each mutant; exactly one failure each, snapshot restoration and baseline pass between them. Test fixture is same-repository/open valid finding with available caps, so unrelated guards do not kill the proof. |
| 6 | **Type handle, store id, render handle; unresolved refused.** `O-test/provider/ActorResolutionResourceTest.java#handleEntryStoresStableIdAndReturnsHandle` sends a `TEST-` handle via resolve/save and asserts actual DB id (not its textual handle), then reads after restart. `#unresolvedHandleIsRejectedWithoutWriting` asserts 422 and unchanged rows. `U/components/accounts/ActorPicker.test.tsx` → `saves a resolved handle and renders it after reload`, plus `refuses unresolved input`. Resolved provider fixture data is explicitly synthetic and never inserted into the dev stack. | Replace the repository/account actor-id binding with input handle and isolate the first Java method, one failure. Restore. Remove refusal on not-found and attempt a raw text write; isolate the second, one failure. Restore. Render stored id/count in place of handle and isolate the named successful UI round-trip, one failure. Refuse a mutant that only changes the mocked response instead of production code. |
| 7 | **Repository shows workspace/accounts/one hook per kind; accounts have no workspace.** `O-test/repository/RepositoryResourceTest.java#repositoryOwnsWorkspaceAndRoleBindings`; gateway `dev.codespire.gateway.registry.RepositoryWebhookKindsTest#refusesASecondWebhookForTheSameKind`; `U/components/repositories/RepositoryDetail.test.tsx` → `shows workspace selected accounts and one webhook per event kind`; `U/components/SettingsProviders.form.test.tsx` → `does not offer workspace on an account`. UI uses distinct configured reviewer/factory fixtures, missing/disabled states and all enabled hook kinds. | Drop repository-account binding filter and isolate the Java test, one failure. Restore. Drop only the per-kind uniqueness constraint in an isolated migrated PostgreSQL test schema; allow duplicate insertion through the real registry API, isolate gateway test, one failure. Restore migration snapshot and recreate isolated schema. Hide the workspace/account/hook section, each as a separate UI mutant of the same named case, one failure each. Reintroduce workspace input on account form; run the named account-form case, one failure. |

Criterion 7's database mutant must be a change to production migration/constraint code applied to a
fresh test schema, not a manual ALTER of the shared dev database. If an application guard masks the
constraint mutation, target the repository method directly within the same test while keeping
valid input; prove exactly the constraint being claimed. Record that selection explicitly.

## Mutation protocol and additional guard obligations

Each slice is responsible for every guard it adds, not only the seven headline criteria. Maintain
an evidence table with production path/line, snapshot hash, changed line, exact test selector,
baseline result, mutated failure name/count, restored hash and restored pass. The evidence belongs
in the slice's review notes, with a concise PR checklist pointer; no invented pass counts.

1. Ensure no other Gradle test invocation is running. Copy the current source/migration to a unique
   file under the session scratchpad and record its hash. The snapshot includes uncommitted work.
2. Run the selected test green with tasks forced. For example:
   `.\gradlew.bat :spire-orchestrator:test --tests
   'dev.codespire.orchestrator.pipeline.FixPermissionSagaTest.writerWithEmptyOverridesDispatches'
   --rerun-tasks`. Pass the actual argument as one shell token; line wrapping here is prose.
3. Edit exactly one production guard. Verify the intended text changed (match `\r?\n` if needed).
   Re-run the exact method. Inspect JUnit XML for exactly one failure, the designated assertion,
   no compilation errors, no setup/container failure, and no skipped target. Targeting one method
   is intentional; this does not claim the full suite contains only one affected assertion.
4. Restore by copying the scratch snapshot back. Check its hash and run the same method green.
   Use a `finally` restoration in scripted checks. Never use `git checkout`, `git restore`, reset
   or a cached Gradle result as restoration/evidence.
5. For UI mutations use `npx vitest run <file> -t '<exact test name>'` from `spire-ui`; inspect the
   executed case and failure count. Restored case must pass. Run the whole affected class/file
   after its individual mutations to catch fixture leakage.
6. If a second guard masks the target, improve the discriminating fixture; do not delete several
   guards together. A surviving/non-compiling mutant is not “killed.” Record failures honestly.

Additional required isolated guards and their exact proposed witnesses:

| Guard | Witness | Mutation |
|---|---|---|
| Same-origin account binding | `RepositoryAccountsTest.rejectsAnAccountFromAnotherOrigin` | Remove origin equality, retain matching kind/role. |
| Credential rotation/reference safety | `RepositoryAccountsTest.disabledAccountCannotServeAnExistingBinding` | Remove enabled filter after a valid binding was created. |
| Host-qualified item identity | `WorkItemIdsTest.samePathsOnDifferentOriginsHaveDifferentIds` | Omit origin from derived identity. |
| Per-source actor namespace | `WorkItemPolicyIT.sameIdOnAnotherSourceDoesNotAuthorize` | Resolve actor membership from the other source. |
| Lowest eligible label | `WorkItemPolicyIT.lowestEligibleLabelWins` | Select highest eligible label instead. |
| Pinned immutable profile | `WorkItemPolicyIT.profileEditCannotWidenAnAdmittedVersion` | Use latest version rather than pinned at transition. |
| Current label removal | `WorkItemPolicyIT.removedLabelStopsTheNextTransition` | Reuse intake labels for continuation. |
| Omitted phase refusal | `AutonomyProfileTest.omittedPhaseIsOff` | Default an absent phase to auto. |
| Gate deadline | `GateExpiryTest.exactDeadlineRefusesALateApproval` | Change `now >= expiresAt` to `now > expiresAt`. |
| Concurrent answer | `GateResourceTest.concurrentAnswersProduceOneResolution` | Remove expected-version/OPEN compare at the transaction write. |
| Dashboard authority | `GateResourceTest.viewerCannotResolveAGate` | Permit viewer on mutation resource. |
| Gate PR scope | `GateChannelsIT.prReviewCannotApproveAPlanGate` | Ignore required land phase when translating approval. |
| Artifact/head freshness | `GateChannelsIT.staleHeadAndDismissedReviewCannotApprove` | Accept approval without current-head verification; test stale-head branch alone. Use a second isolated method for dismissal mutation. |
| Retirement | `WorkItemRetirementIT.transferRetiresOldIdentityAndInvalidatesOpenGate` | Continue old item after confirmed identity transfer. |
| Machine identity in takeover | `HumanTakeoverIT.knownMachinePushDoesNotTakeOver` | Treat matching recorded factory actor as human. |
| Publication hold after restart | `PublicationHoldIT.orphanRecoveryKeepsTheDurablePublicationHold` | Omit durable hold read before orphan finalization. |
| Event/projection/outbox atomicity | `WorkItemStoreTest.rollbackLeavesNoGateEventOrOutboxEffect` | Append on a separate autocommit connection before the forced transaction failure. |
| Idempotent result continuation | `WorkItemRunBridgeTest.duplicateResultAdvancesTheItemOnlyOnce` | Remove consumed-result dedupe; preserve valid active state for both deliveries. |
| No early item PR | `WorkItemRunBridgeTest.itemRunCannotUseStandaloneAutomaticProposal` | Remove the item-association exclusion in automatic M2 proposal. |

Tests with multiple scenarios should be split into individually selectable methods before their
mutations if one scenario would mask another. Every guard discovered during review is added to
this table with its discriminating witness before that slice is called complete.

## Slice 10 — integrated proof and handoff

- [ ] Only now drop `scm_provider.workspace` in an explicit Flyway migration. Confirm its
  populated evidence survived slices 2–9 and the no-production-read guard remained green.
  Retain the validated pre-migration dump/credential proof as rollback evidence outside git.

- [ ] Run `testFast --rerun-tasks` and, after it exits, `testServices --rerun-tasks`. Verify every new
  module is included in its proper tier. No parallel second Gradle invocation.
- [ ] From `spire-ui`, run `npm test` and `npx tsc --noEmit`. Check CSS contracts, routes/deep links,
  keyboard form behavior and new unknown/refused/suspended status rendering.
- [ ] Run the whole criterion test classes after targeted mutation baselines; inspect JUnit/vitest
  counts. No compilation/setup failure, skipped suite or empty fixture is an acceptance pass.
- [ ] Run the warm `spire-e2e` stack only if already available or separately provisioned without
  touching `spire-dev`. Add `dev.codespire.e2e.WorkItemGateJourneyTest` for signed tracker event →
  durable admission → gate → policy-controlled continuation against real GitLab. If the run unit
  cannot reach its local GitLab, record the known network limitation; do not rebind it publicly
  or claim this test proves run execution. Pair it with the real-container/local-origin worker
  proof and an explicitly recorded live external-forge run when authorized and feasible.
- [ ] Demonstrate all seven acceptance criteria through actual APIs/UI with supported provider
  configuration. Any live canary uses announced ids and exact local cleanup before insertion,
  with explicit remote cleanup. Store only observed ids, dates and results in the evidence notes.
  A live credential/permission probe is evidence about that account/token family, not all tokens.
- [ ] Record all mutation selectors and measured results, missing capabilities, unavailable live
  credentials and exact remaining proof gaps. Do not mark acceptance complete if a required
  criterion lacks evidence; bring that gap to the analyst.
- [ ] Update `docs/DECISIONS.md` with accepted ADRs; reconcile factory architecture/topic/data
  catalogues; M2 live-proof reconciliation already landed in slice 1; update smoke-test instructions, registry upgrade steps,
  security/SCM mapping, factory roadmap and `docs/HISTORY.md`. Rewrite `CLAUDE.md` status/measured
  counts from actual results, preserving unrelated UNVERIFIED entries.
- [ ] Submit each slice for analyst review and resolve findings with their tests/docs. The PR
  remains draft until the analyst's review process says to mark it ready; this plan authorizes
  no merge, deployment or privilege changes.

## Round 1 report content

Report the draft PR number/link, the two document paths, the commit and push verification, and
that this round ran documentation checks only. Explicitly call out the analyst decisions in design
§11: M3/M4 journey and phase-order boundary, ordering incomparable profiles, permission/handle
portability, legacy org/source cardinality, native drafts and takeover precedence. Those are
review inputs, not reasons to withhold the requested draft PR.

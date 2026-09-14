# M3 slice 10 — final migration and handoff

Measured on **2026-09-14**. Slice 9 was independently accepted without findings in round 16.
All seven criteria are verified; the [consolidated acceptance record](../../../docs/factory/M3-ACCEPTANCE.md)
maps each to its proving slice, exact Java/UI witnesses and mutation ledger. PR #153 remains draft
for final operator review. No merge or promotion to ready is claimed.

## Explicit workspace drop

AccountWorkspaceIsUnusedTest ran successfully with --rerun-tasks before V72 was added.
It checks production reads and writes, including INSERT and UPDATE. V72 is one explicit
ALTER TABLE scm_provider DROP COLUMN workspace; it changes no historical migration.
The populated V71→V72 test compares every remaining account field, ciphertext, repository
bindings, context references and immutable legacy mapping rows; decryption keeps provider UUID AAD.
Its TEST-only schema is dropped in finally. Existing latest-schema fixtures now use exact created
account IDs and explicit TEST legacy snapshots instead of querying the removed account column.

Four distinct production mutations were independently killed, with one selected assertion failure
per run, exact scratch-byte restoration and a passing restored case. The three architecture mutants
also compiled the production orchestrator. No fixture mutation or compilation/setup failure was
counted. The initial pre-migration test target-72 setup failure was followed by a valid populated
migration baseline and is not mutation evidence.

| Mutation | Selected test | Result |
|---|---|---|
| workspace_drop | `dev.codespire.orchestrator.provider.AccountWorkspaceDropMigrationTest.dropsOnlyLegacyWorkspaceAfterPreservingEveryOtherAccountFieldAndReference` | One selected assertion failure; restored pass |
| workspace_read | `dev.codespire.arch.AccountWorkspaceIsUnusedTest.noProductionCodeReadsLegacyAccountWorkspace` | One selected assertion failure; restored pass |
| workspace_insert | `dev.codespire.arch.AccountWorkspaceIsUnusedTest.noProductionCodeReadsLegacyAccountWorkspace` | One selected assertion failure; restored pass |
| workspace_update | `dev.codespire.arch.AccountWorkspaceIsUnusedTest.noProductionCodeReadsLegacyAccountWorkspace` | One selected assertion failure; restored pass |

The [machine-readable inventory](factory-m3-slice10-mutations.json) includes the exact production
edits and final source hashes. workspace_drop replaces the production DROP with SELECT 1, so the
absence assertion must fail despite otherwise preserved rows. The read/write edits are separate.
Final scratch hashes and all five Semgrep source hashes were audited before commit.

## Backup before dev migration

- Fresh archive: .handoff/m3-before-slice10-20260914-161030.dump.
- Dump started: 2026-09-14T14:10:30.8773282+00:00; archive verified: **2026-09-14T14:10:32.6500742+00:00**.
- Size: **581093 bytes**; pg_restore --list: **312 entries**.
- SHA-256: 039ae2b0ee55f3f5899bb48a8d5ce41d186b4e47dab84178ca82323a5b3b76cd.
- V72 installed on dev: **2026-09-14T14:54:48.835744+00:00**, after archive validation; Flyway success true.

The repeatable binary-safe command is in the [upgrade runbook](../../../docs/SMOKE-TEST.md#m3-final-upgrade-slice-10).
The archive and original encrypted baselines remain git-ignored in .handoff; none entered an image.
The orchestrator was at V70 immediately before upgrade, so accepted slice 9's V71 applied before V72.
The rebuilt gateway, orchestrator, review worker and UI use the tested source; all readiness checks
pass. Postgres, Redpanda and unrelated services were retained. No development run worker was started.

One scheduler invocation raced messaging initialization at 14:54:50 UTC: WorkItemOutbox could not
inject the work-events-out emitter (SRMSG00019). The application became ready immediately after
startup; work-events-out reports OK and no further ERROR appeared through 14:56 UTC. This is a
transient startup observation, not a live work-event publication proof. The empty dev outbox was
not populated with a canary to manufacture one.

## Row and credential continuity

Before observation: 2026-09-14T14:15:26.970341+00:00. After observation: 2026-09-14T14:55:27.321308+00:00.
The account workspace column existed before and is absent after; latest successful migration is 72.

| Table | Full before | Full after | Original baseline excluding accepted PR #32 audit |
|---|---:|---:|---:|
| scm_provider | 6 | 6 | 6 |
| review_status | 38 | 38 | 37 |
| review_finding | 93 | 93 | 85 |
| factory_run | 15 | 15 | 14 |
| gateway.webhook_repo | 3 | 3 | 3 |

The older baseline was 6/37/85/14/3. The independently accepted live standalone /fix proof retained
one review (review::artyomsv/spire-test#32), eight findings (IDs 152–159) and one run
(run::github:artyomsv/spire-test:4003204361:1). These explain the full 6/38/93/15/3 inventory.
Excluding exactly that accepted audit restores all five original counts; no row was deleted to
force a historical total. Both encrypted Compare probes passed before and after migration against
the original snapshots: **9 credential/reference entries and 12 webhook entries identical after
decryption**. No baseline was recaptured, no secret printed and no synthetic live row inserted.

## Final validation

Forced testFast, testServices and assemble passed sequentially on Java 25 with --rerun-tasks
--no-parallel. XML totals: **4076 Java tests, 452 suites, 30 modules**,
zero failures/errors and 1 existing Windows symlink privilege skip. The fast tier
contains 1555 tests; services contain 2521. All seven criterion classes and
the real-JVM hold/takeover recovery tests ran in the full tiers.

Full UI: **730 tests across 92 files**, zero failures; TypeScript and production build passed.
Pinned Semgrep 1.172.0: **zero findings and zero parser errors across five changed code/migration
files**, 167 rules. No suppression was added. The accepted earlier slice ledgers remain linked;
this handoff does not inflate a globally deduplicated mutation total.

CLAUDE.md Status was replaced as a snapshot; HISTORY gained one milestone entry. Architecture,
topics, data, ADR status, SCM mapping, security and the upgrade runbook now describe the delivered
M3 boundaries. No warm GitLab e2e stack was available, so that conditional tier was not run and no
new WorkItemGateJourneyTest or live canary result is claimed. Existing accepted API/UI and local
execution proofs remain the evidence for their corresponding criteria.

## Limits that remain

- **Production VERIFY and LAND remain unavailable.** M4 owns the verifier; M3 does not ship one.
- **No live item-build proof.** TEST PR #32 proves standalone /fix; an item-linked build has not
  been run against a real forge.
- **The automated GitLab run-unit gap is still open.** RunUnitSpec has no network field, so a
  run unit cannot reach the e2e stack's GitLab. A live GitHub run does not close it.
- **The two factory images are still not on GHCR.**
- Per-forge identity and permission limits remain their own unchanged UNVERIFIED entries.
  External gate answers/operator resume lack live proof. GitLab/Bitbucket native PR approvals and
  Jira Data Center comment polling remain unavailable.
- Publication already in progress cannot be recalled. No atomic ordering with a remote human
  push is claimed. Confirmed deletion/transfer still needs live provider evidence.

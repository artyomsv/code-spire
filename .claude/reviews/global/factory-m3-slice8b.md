# Factory M3 slice 8b — held publication and observed delivery

Local validation and the live standalone proof pass. Slice 8a is pushed on d9861bc3 with all CI checks green. Round 12 independently
verified criterion 1: all seven acceptance criteria are now proved. This slice's local proofs and live
standalone `/fix` obligation are recorded separately below.

## Execution and publication

`ExecuteWorkRun` is a distinct wire command containing the original M2 execution and the immutable
item/generation/build-attempt/preparation binding. The worker commits this encrypted command and
the shared M2 execution claim before acknowledging it. An older worker cannot ignore an optional
hold flag and execute an item run automatically. Ordinary `ExecuteRun`, including standalone FIX
source-branch execution, retains automatic publication.

The initial publisher has a separate held entry point. It gates and checkpoints the actual built
head without pushing. Trusted daemon labels mark every run resource before creation, including
partial init failures. Runtime destruction and orphan recovery respect that hold. Active compute
stops while the workspace, original topology and encrypted result outbox remain durable.

`RunWorkReady` reports the actual full head, changed paths, measured active wall time and measured
or unknown usage. It completes BUILD and releases the item's active reservation. Production has
no M4 verifier: the next phase records `verify_capability_unavailable`. No successful verification,
review, CI run or merge is fabricated.

A short-lived `PublishWorkRun` control command binds the retained work, completed verification,
delivery attempt and exact head. The delivery claim checks current source/actor/profile/ceiling,
artifact versions, the original phase admission and the selected FACTORY identity. The encrypted
permit commits before the broker write. Uncertain acknowledgement never resends execution or an
unclaimed publisher; definite serialization misses may re-arm the same delivery effect.

The worker resumes only the trusted publisher, using fresh SCM credentials, original topology and
both original and current protected paths. The original harness credential is deliberately invalid
in the credential-boundary test. The publisher checks its exact head and lease window before the
write. Cancellation is checked before creation, before start and after start; an already observed
push remains reported even if cancellation races it. Unobserved publication stays recoverable.

## Delivery and review

After actual publication, delivery commits a proposal claim before its external write. Recovery
of an ambiguous POST only reads by both branches. A compare-and-set prevents a stale publication
reader from changing `proposing` back to `pushed` and posting twice. Completed delivery recovery
also checks the exact completed attempt, item, generation and phase.

The sink contract carries requested and observed native draft state. GitHub and Bitbucket send
the documented boolean; GitLab uses its documented native `Draft:` title. Missing draft state
remains unknown. A regular or unknown existing request cannot masquerade as the requested draft.
Unsupported native draft capability blocks delivery visibly. Official contracts checked 2026-09-14:
[GitHub](https://docs.github.com/en/rest/pulls/pulls#create-a-pull-request),
[Bitbucket](https://developer.atlassian.com/cloud/bitbucket/rest/api-group-pullrequests/),
[GitLab draft semantics](https://docs.gitlab.com/user/project/merge_requests/drafts/) and
[GitLab API](https://docs.gitlab.com/api/merge_requests/).

Delivery tests identify their TEST-only prior verification driver. They prove native draft versus
regular control-plane behavior, not a shipped verifier. REVIEW observes the existing reviewer for
this repository and pull request, the exact reviewed and posted head, completed non-degraded
results, an open unarchived request, readable findings/reconciliation and no open blockers. Missing
or unreadable evidence stays waiting. Successful observation reaches unavailable LAND capability;
there is no synthetic CI or merge success.

## Recovery and paid usage

Worker readiness and terminal publication have independent encrypted result slots and independent
acknowledgements. The orchestrator validates the immutable work binding before projecting or
charging readiness. It retains readiness metadata even when a terminal publication arrived first,
without reopening the terminal run. Readiness completes BUILD once; publication cannot complete
BUILD or add its usage again. Both results use the existing M2 call identity.

A stopped or superseded build still bought usage. Late recovery records it once without completing
the current attempt, changing its phase or erasing its reservation and execution evidence. Unknown
usage remains unknown. Tests replay the inbox after aggregate commit but before acknowledgement.

`WorkItemRunJourneyIT.preparedItemBuildsAndWaitsForVerification` uses real worker containers and a
real local smart-HTTP origin, plus an isolated orchestrator TEST JVM and provider HTTP fixture.
The actual result saga consumes readiness, records one build charge and exposes the checkpoint.
VERIFY remains unavailable, no PR exists and the remote branch is absent. The only execution
substitutions are the local origin/image/SCM and a bounded TEST wall clock.

`WorkRunProcessRecoveryIT` uses three real worker JVMs and the same isolated database/runtime.
The parent kills the first after durable readiness, then observes a new owner preserving the held
workspace without rebuilding. It kills the second in each of two separate windows: after durable
publication claim but before publisher IO, and after the real remote push but before terminal
commit. A third owner recovers the original permit and publisher. Both cases assert the remote's
exact head, `TEST-build-count` of one, no new harness execution, acknowledged results and cleanup.
Only the exact dead TEST lease is backdated to avoid waiting a production minute.

The separate charge test sends actual worker readiness and terminal results into another JVM with
the production charge ledger and full production Flyway schema. Skipping readiness loses its
measured tokens; changing the final call identity produces two ledger rows. Each mutation is
isolated and restored. A fixture flag is not used as evidence of process death or remote writing.

## Discriminating fixtures and defects found

- The initial-hold mutation changes the actual remote head and fails the named delivery-off test.
- A runtime wrong-run mutation initially reached an unrelated mount guard. Its assertion now names
  the wrong-run error, so that second guard cannot count as evidence.
- The unobserved-agent fixture initially had no pushed branch. It now supplies an actual observed
  push with an unobserved agent, distinguishing that guard from the empty-result branch.
- The reviewed-head mutation survived twice: first both reviewed and posted heads were wrong, then
  a corrective `recordPosted` call correctly refused a mismatched commit. The final fixture changes
  only the reviewed head with exact TEST SQL and asserts both fields before exercising the guard.
- Terminal-before-ready delivery exposed missing checkpoint metadata; its metadata update now
  preserves both the terminal status and independently bound build evidence.
- Late readiness on a stopped or readmitted item exposed endless inbox recovery and lost spend.
  A separately deduplicated accounting decision preserves the newer attempt and acknowledges it.
- A stale publication reader could rewind a committed proposal claim. A two-thread test now pauses
  that reader while another caller commits a POST with an ambiguous response; resumption performs
  neither another POST nor another forge read.
- Initial child-JVM failures were TEST bootstrap/profile and JSON discriminator issues. They were
  corrected before claiming the real process proofs. No unexplained container deletion occurred.
- Pinned Semgrep found four test process launchers with dynamic executable expressions. They now
  name literal `git` or `java` executables; the worker test task pins PATH to its selected JDK, and
  arguments remain separate process arguments. No shell or scanner suppression was introduced.
  The final changed-file scan, including the later taxonomy fix, reports zero findings across
  98 files. Its only partial-parser warning has the same four pre-existing `api.ts` spans.
- Publisher failure redaction originally used only build credentials even when publication used
  a rotated SCM identity. It now redacts the current identity before the existing original-secret
  scrub. An unreadable current credential leaves the publisher recoverable without emitting its
  error text. Real Tink tests cover raw and Basic forms; worker tests distinguish old and new secrets.
- The missing-checkpoint mutation originally caused `NoSuchElementException` in its fixture.
  That was rejected as evidence. The case now asserts the exact optional checkpoint value; the
  same production mutation produces one assertion failure and the scratch-restored case passes.
- Delivery selection and policy revision have separate fixtures: changed labels preserve the
  revision, while an unused mapping changes revision with identical selection. Both retain an
  enabled source and allowed actor, so neither half can hide behind the other policy guard.
- The first forced fast suite found two publisher causes missing from the shared vocabulary:
  expired publication permits and unavailable permitted heads became `UNCLASSIFIED`. Both now
  map to the existing non-retryable `GATE_REFUSED` category, which preserves paid-build accounting.
  The producer-derived inventory caught this; its assertion was retained. A focused test checks
  the category, retry answer and spend classification for both causes.

## Validation and live obligation

Forced `testFast` and `testServices` passed sequentially in 3m13s and 25m43s. Fresh archived JUnit
reports contain 3970 tests across 444 suites and 30 modules, zero failures/errors and one existing
Windows symlink privilege skip. This includes both process-death windows, late accounting and the
stale proposal race. The complete UI passes 714 tests across
88 files; all 40 route cases pass shuffled seeds 814, 2718 and 5192, and the production UI builds.
Thirteen UI production mutations, including the required identical-journey-label replay, pass
their isolated fail/restore checks. The final evidence audit accepts 248 checks covering 245
distinct production mutations: 235 Java checks and 13 UI checks. Each has exactly one selected
assertion failure, byte-identical scratch restoration and a passing restored case; the audit
also verifies its final production anchor. All 48 final inbox/delivery mutations passed without
a survivor or unexpected failure. The expanded inbox baseline has 36 cases and final delivery
baseline has 24, all passing. Pinned Semgrep reports zero findings across 98 files, with all final
file hashes matching the scan capture. Packaging passed in 47 seconds.

The user started the live worker only after the final Docker service tier exited. The live
orchestrator ran the tested source (matching `RunResultSaga` and `RunFailureCause` hashes).
The real standalone proof is [TEST PR #32](https://github.com/artyomsv/spire-test/pull/32):

- Initial head: `5c9eac3195308ec61b180ec67c2a1356e5606240`, the unchanged invoice example from
  the existing test branch. Its actual initial review posted finding `4003204361` about missing
  `customerId` validation, with zero prior fix runs.
- [Command `4003211987`](https://github.com/artyomsv/spire-test/pull/32#discussion_r4003211987)
  dispatched `run::github:artyomsv/spire-test:4003204361:1` at 08:00:13 UTC on 2026-09-14.
- The real worker/publisher completed successfully at 08:01:00 UTC and automatically pushed
  `refs/heads/TEST-s8b-standalone-fix-20260914`, commit
  `264ff858b538a3c779cf94161d3bc601bcfcf69a`. The change adds the null check before invoice lookup.
  The persisted run has kind `FIX`; its work-run effect count is zero. No work-item permit was used.
- The next review completed on that exact new head. PostgreSQL `review_finding` row 157 records
  `RESOLVED` for thread `4003204361`; the API reconciliation records `resolvedThread=true` and
  GitHub thread `PRRT_kwDOTQMWo86iB31I` is resolved. Other invoice findings remain outside this task.
- Cleanup closed PR #32 without merging and issued exactly
  `DELETE /repos/artyomsv/spire-test/git/refs/heads/TEST-s8b-standalone-fix-20260914`.
  Review/run history is retained as the live evidence. The user was notified to stop the worker.

Live preflight exposed legacy configuration/data limits without bypassing them. The GitHub hook
`26c8a671-6dcb-4cc7-a71a-a1184fcf0162` lacked its forge origin; its revision-7 gateway API repair
selects the existing repository `4bc898a4-7dbc-4167-885a-fd354ddbb8bb` and `https://api.github.com`,
preserving its secret and roles. The original GitHub event was redelivered. PR #26 then correctly
refused because its old finding row has no thread reference; a normal fresh review confirmed the
bug but retained that legacy row. PR #27 correctly refused unknown historical fork metadata.
Neither attempt dispatched a run or consumed a fix slot. The announced TEST PR avoided both old
data gaps; no cap, refusal guard or historical finding row was changed. PR #31's cap was preserved.

All automated fixtures use isolated Testcontainers and TEST-prefixed identities. Cleanup binds
owned IDs; worker cleanup deletes only its claimed TEST execution/lease/work rows and held units.
The charge proof creates and drops only its generated TEST schema. Orchestrator cleanup deletes
the exact delivery/run effects before their phase/item rows and then the fixture's profiles,
accounts and repository bindings. No second backup or direct dev-database fixture insert/delete
was performed; the live TEST review and run were created through the actual application path.

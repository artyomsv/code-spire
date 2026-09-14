# Factory M3 slice 7 — visible policy bounds and durable approvals

Round 10 accepted slice 6 on 721f4084. Criteria 3, 5, 6 and 7 remain independently verified.
This slice supplies the named proofs for criteria 2 and 4; independent verification belongs to
review. The final full-suite validation is green.

## Policy decisions and visible clamps

Profiles have immutable versions, distinct operator-declared precedence, all eight phase modes,
six numeric bounds and cumulative protected paths. Omitted phases grant nothing; old profiles
without numeric limits authorize no execution. Every eligible label, admission bounds and the
current ceiling restrict the vector. Numeric maxima take the minimum, protected paths the union,
including the actual publisher CI floor. Invalid globs and overflowing gate lifetimes are rejected
before admission. The settings screen creates versions and pins repository ceilings and label mappings.

The central transition service handles intake selection, operator resume, prior-phase results,
dashboard answers and policy checks before pending tracker writes. Remote evidence is collected
before locks. Source/account/repository and policy revisions are checked inside the decision
transaction, followed by the aggregate lock. A result is bound to its durable attempt; repeated
results cannot start another phase. Completion is recorded on the prior phase before deciding the
next. Re-admission advances generation while retaining usage. Production reports unavailable
executors; tests explicitly provide a test capability for control-plane attempts.

V67 records the current clamp projection only when the durable clamp milestone is written. The
same transaction writes encrypted events, query rows and Kafka outbox entries. The detail shows
the selected profile, current ceiling, actual modes, caps, protected paths and explanatory reason.
A removed condition disappears from attention while its historical milestone remains.

## Durable dashboard decisions

V68 adds versioned gate bindings, deadlines, encrypted notes, reservations and durable phase
attempts. Gate answers bind expected version, item revision, generation, phase, policy and serving
authority. The server derives the resolver from the verified session. Identical winning key,
resolver, choice and note are idempotent; conflicting answers return 409. Current tracker labels
are observed again. Unavailable authority returns 503 without claiming an answer. Expiry wins at
the exact deadline, releases the reservation and invalidates pending tracker writes.

Approvals exposes open decisions and history, with admin-only answer controls, stable retry keys,
notes and honest 409/503 feedback. Current open/expired/clamped/source-health conditions feed the
real attention API. Workflow status types, labels and server-backed filters change together.
Pending UI reads cannot replace a newer selection or cross teardown. The route `.content`
assertion and its existing `Verified by mutation` comment remain intact.

## Discriminating evidence

- `WorkItemPolicyIT.aboveCeilingLabelRecordsAndDisplaysClamp` checks the bounded PLAN and DELIVER
  modes, a fresh encrypted-history read containing the clamp milestone, the real attention API and
  HTTP detail reason. Keeping requested modes fails this isolated case. Separately omitting the
  clamp event/projection write fails it again. Emptying the UI explanation fails exactly
  `WorkItemPolicy.test.tsx` / `shows requested and effective profiles with the clamp reason`.
- `loweredCeilingStopsAnInFlightItemAtTheNextPhase` starts SPEC, commits PLAN=off, then submits
  that SPEC attempt's successful result. The source and actor remain eligible. Only the prior
  attempt exists; there is no factory run or PR request, and HTTP detail persists `plan_off`.
  Substituting the prior policy snapshot's ceiling for the current ceiling fails this method.
- `ceilingChangeBeforeGateAnswerRequiresANewDecision` changes the ceiling identity/revision while
  keeping every mode and numeric limit identical. The actor and source remain eligible. Bypassing
  only the policy-revision recheck turns the expected 409 into approval and fails this method.
  The final service mutation repeats this proof on the strengthened fixture.
- The full suite found the older admission-pin test counting two total events. It now requires
  the exact `POLICY_CLAMPED`, `POLICY_OBSERVED`, `POLICY_OBSERVED` sequence. Every original mode,
  admission and actor assertion remains. Removing the admission-mode argument at its new central
  transition boundary still fails this isolated test before the history assertion.
- `GateResourceTest.concurrentAnswersProduceOneResolution` uses real PostgreSQL interleavings
  and requires one 200, one 409 and one resolution. The separate expiry/answer fixture holds the
  PostgreSQL aggregate lock with literal fixture SQL, independently of the production helper.
  Removing the production lock must let a future complete while the fixture still holds it.
  The baseline exposed a real expiry/answer deadlock: expiry held the item while its projection
  needed parent rows locked by the answer. Expiry now takes registry, policy and item locks in
  the same order. The fixture stages expiry at the held item lock before starting the answer;
  removing expiry's registry lock reproduces the deadlock and fails the no-exception assertion.
- `GateProcessRecoveryIT.restartExpiresOpenGateAndReleasesReservation` starts the current
  packaged orchestrator in a child JVM. That child itself scans the TEST ticket and opens the
  gate. The test reads OPEN through HTTP and kills the child while its deadline is still live.
  A second packaged JVM expires the persisted gate, releases its reservation and leaves a durable
  `gate_expired` reason. The parent's expiry scheduler is disabled. The shared harness also reruns
  both existing scanner process-kill tests. No live run worker participates.
  A full-suite failure exposed child JVMs joining the parent's Kafka groups and retaining their
  partitions until the broker detected process death. The harness now derives incoming channels
  from configuration and assigns separate TEST topics and groups. While each gate child is alive,
  Kafka Admin confirms its actual isolated assignment and the parent's single-member work group.
  All three process cases and four broker/outbox cases pass together. The original 20-second
  duplicate-acknowledgement assertion is unchanged; no broker timeout was extended to hide the leak.
- `WorkSourceParityCoverageTest` discovers adapter modules from disk and requires an executable
  Quarkus subclass of the shared cases for every arm. GitHub now inherits those same cases.
  Removing Jira's inheritance fails with the derived set still containing JIRA. This is the one
  explicitly requested test-wiring mutation; it is identified separately from production mutants.

ADR-045 states: **The effective vector is never above any applied label in any component.**
It also names the FR-F22/FR-F25 conflict and classifies authorized explicit commands and gate
answers before ordinary comment takeover. Artifact/head binding and prepared task/build wiring
remain slice 8. Generated specification/planning/verification execution remains M4.

## Validation

Forced `testFast` and `testServices`, then `assemble`, passed sequentially with JDK 25: **3649 Java tests across 418 suites and 30 modules**, zero failures and 1 existing Windows symlink privilege skip. The final service run includes both scanner process-kill tests and the new gate process-kill test.

**123 mutation checks cover 120 distinct production changes**: 97 Java-main checks, 25 UI checks, plus the explicitly requested Jira test-inheritance check. Each has exactly one selected assertion failure, byte-identical scratch restoration and a passing restored run. Two transition checks were repeated on the final strengthened fixture. Exact lines and failed methods are in `factory-m3-slice7-mutations.json`.

Pinned Semgrep 1.172.0 (CI digest `65dcd4408adda7c183a6b4550cb1e9b19f7f627a6fbb7e0559bd466bedc44d7b`, p/default and p/secrets) reports **zero findings** across all 55 changed source/test/build/migration files, including Java test paths normally excluded by rules. Its 1 parser warning is the existing optional-field parse in `spire-ui/src/api.ts`; a separate scan of that file from accepted 721f4084 reproduces it with zero findings. Final LF-normalized source hashes match the scan. No suppression was added.
The full UI baseline passed 701 tests across 86 files. All 40 route tests also passed with shuffle
seeds 42, 99 and 5192071894. The final presentation adjustment passed 100 targeted tests, including
the style contract and route file, followed by the production build.

## Exact fixture cleanup and boundaries

Executable DELETEs and bindings are in
[`WorkFixture.cleanWork`](../../../spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkFixture.java):
tracker outbox, gates, phase attempts, Kafka outbox, deliveries, item and event log, then the fixture's source, policy, profile,
repository and account dependencies. Every predicate binds that fixture's generated TEST item,
source, repository, profile or account ID. `extraProfiles` includes every added policy identity.
The process harness kills each child in cleanup; Testcontainers owns the isolated service stack.
The gate process proof uses the same item cleanup and creates no additional work item.

No dev database, live tracker row/write, backup or live run worker was used. Live OIDC and tracker
journeys are not inferred from TestSecurity or WireMock. The user will start the worker for slice
8b's live proof. Per-arm live limits remain in `docs/UNVERIFIED.md`.

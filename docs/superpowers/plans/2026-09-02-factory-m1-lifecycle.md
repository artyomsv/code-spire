# Factory M1 — The Lifecycle Survives Reality

**Goal (ROADMAP M1):** a run that meets a hostile world behaves correctly.

**Exit criteria, quoted from the roadmap so they can be checked rather than paraphrased:**

1. Killing the control plane mid-run loses no completed work and creates no duplicate charge.
2. Killing the sandbox mid-run yields a classified failure, not a stall.
3. Exhausting one credential rotates to the next without re-charging the call.

**FRs:** FR-F5, F6, F7, F8, F9, F10, F12, F14, and the M1 half of F13.

M0 delivered the happy path against a real forge. M1 is the unhappy paths, and the ordering below
is by dependency: the failure taxonomy first because everything else classifies into it, the lease
before the watchdog that reads it, the control topic before the cancel that rides it.

---

## What M0 left behind, and where it lands here

Every one of these is an existing `techdebt/` entry written during the M0 review rounds. M1 closes
them as part of the task that owns the same concern, rather than as a separate cleanup pass. The
debt file is deleted in the same commit that closes it.

| Debt entry | Closed by |
|---|---|
| `spire-run-worker/2-3-a-failed-salvage-discards-every-push…` | Task 3 |
| `spire-run-worker/2-4-a-worker-death-between-claim-and-result-strands-the-run` | Tasks 5 and 6 |
| `spire-run-worker/3-3-a-gate-refusal-does-not-stop-the-agent` | Task 7 |
| `spire-run-worker/3-3-cancelrun-is-a-no-op` | Task 7 |
| `spire-run-worker/4-1-runstarted-carries-the-run-id-as-its-provider-run-id` | Task 5 |
| `spire-run-worker/4-2-every-publisher-failure-is-retryable` | Task 1 |
| `spire-run-worker/4-2-the-workers-own-failure-details-are-not-scrubbed` | Task 1 |
| `spire-publisher/3-2-a-non-fast-forward-push-has-no-handling` | Task 1 |
| `spire-orchestrator/3-2-a-run-that-delivered-nothing-is-recorded-as-succeeded` | Task 1 |
| `spire-orchestrator/3-3-run-token-usage-is-dropped-and-a-run-writes-no-charge` | Task 4 |
| `spire-orchestrator/3-3-a-run-defaults-to-the-reviewers-own-model-key` | Task 10 |
| `spire-orchestrator/4-1-a-runs-failure-detail-is-readable-by-a-viewer` | Task 1 |
| `global/3-3-run-event-accumulation-is-unbounded` | Task 2 |

Deliberately **not** closed here, with the reason: `spire-runtime-docker/4-3-…default-bridge…` and
`spire-run-worker/4-2-the-m0-test-origin-runs-as-root…` are network-isolation items that belong with
M2's deployment work; `…dockerrunruntime-is-past-the-class-size-guideline` is readability only and a
refactor mid-milestone would collide with Tasks 3, 6 and 7, all of which edit that class;
`global/3-2-testservices-races-two-docker-driving-modules` is a build-infrastructure fix that M1 will
feel on every task, so it is Task 0 below rather than deferred.

---

## Global Constraints

Every task's requirements implicitly include this section. These repeat M0's constraints because the
build enforces them and a forgotten one fails the build rather than a review.

**Adding a module is a four-file ritual:** `settings.gradle.kts`, the root `build.gradle.kts` tier
list (`fastTestModules` or `serviceTestModules` — `TestTierCoverageTest` fails a module in neither),
the root `Dockerfile`'s alphabetical `COPY` block (`ImageBuildSeesEveryModuleTest` fails otherwise,
and **every production image build breaks**), and a `<module>/LICENSE` plus a `LICENSING.md` row.

**Licence boundary (ADR-021):** no Apache-2.0 module may depend on a service module. `spire-harness`,
`spire-runtime`, `spire-runtime-docker`, `spire-workspace` are Apache-2.0; `spire-run-worker` and
`spire-publisher` are FSL.

**Framework-free modules:** `spire-contract`, `spire-diff`, `spire-harness`, `spire-runtime` carry no
framework imports beyond the JDK, their own module, and `jackson-annotations` where the type *is* the
wire contract. `PureModulesAreFrameworkFreeTest` enforces it.

**Provider neutrality (ADR-020):** core modules name no harness, runtime or SCM. `CoreIsProviderNeutralTest`
scans source text, and it covers `spire-run-worker`. The M0 round-3 defect is the cautionary tale: a
core module read a constant off `DockerRunRuntime` and the branch was red for hours because a
conflicting pull request gets no CI run. **A new capability that needs a number from an arm asks the
SPI for it.**

**Migration numbering:** orchestrator continues at **V46**; the run worker's own schema continues at
**V2** (`V1__run_claim.sql` exists). Check the directory before writing, never assume — master and a
feature branch have already collided on this once, and Flyway refuses duplicate versions while git
merges them cleanly.

**Never log a credential.** The machine-account token and the harness credential are injected per run
and must not appear in a run event, a log line, an exception message, or a git remote URL. Task 1
adds the scrub the worker's own failure path currently lacks.

**Money:** millicents, integers. A charge is priced at the rate in force when the call happened
(ADR-023) and snapshotted onto the row.

**Commit style:** imperative, at most 72 characters on the first line, body for anything non-trivial.
No mention of AI authorship, tooling, model names or review rounds as authorship.

**Verification loop:** `./gradlew testFast` for the pure modules, `./gradlew testServices` for the
deployables. Run them one at a time — see Task 0.

**Definition of done, per task:** the test scenarios below are written first and observed to fail,
the implementation makes them pass, every new guard is mutation-verified (break the production line,
confirm exactly the intended test fails), the commit is pushed, and the pull request checklist is
ticked.

---

## Task 0: Serialise the two Docker-driving test modules

Closes `techdebt/global/3-2-testservices-races-two-docker-driving-modules.md`, which was filed
against this branch's own predecessor. It goes first because every later task runs `testServices`,
and an intermittent red that looks exactly like a container-lifecycle bug will cost more than it
costs to fix now.

**Files:** root `build.gradle.kts`; new `buildSrc` or a shared `BuildService`; delete the debt entry.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `theDockerDaemonIsUsedByOneTestTaskAtATime` | the two Docker-driving `Test` tasks declare the same shared build service with `maxParallelUsages = 1` |
| `theOtherServiceModulesStillRunInParallel` | the three non-Docker service modules carry no such constraint |

The second scenario is the one that matters: the cheap fix is to disable parallelism globally, which
would pass the first assertion and slow every build. Asserting the negative keeps it honest.

---

## Task 1: The failure taxonomy is a closed set, recorded as data

**FR-F9.** "Read the logs" is not a failure cause. Today `factory_run.failure_cause` is an
unconstrained `VARCHAR(32)` that any writer can typo, and the worker classifies every publisher
failure as retryable.

**Files:**
- New: `spire-contract/…/event/RunFailureCause.java` (enum, closed set)
- New: `spire-orchestrator/src/main/resources/db/migration/V46__run_failure_cause.sql`
- Modify: `RunLauncher`, `PublisherOutcome`, `FactoryRunProjection`, `RunResource`
- Modify: `spire-workspace/…/PublishRepo.java` (non-fast-forward detection)
- Delete: four debt entries listed in the table above

**The closed set**, each value earning its place by being actionable to a different person:

`BAD_COMMAND`, `IMAGE_UNAVAILABLE`, `CLONE_FAILED`, `AGENT_FAILED`, `AGENT_TIMEOUT`,
`GATE_REFUSED`, `PUSH_REJECTED`, `NON_FAST_FORWARD`, `CREDENTIAL_REJECTED`,
`ALL_CREDENTIALS_EXHAUSTED`, `SALVAGE_FAILED`, `RUNTIME_UNAVAILABLE`, `CANCELLED`,
`DISPATCH_FAILED`, `DISPATCH_UNCERTAIN`, `NOTHING_PRODUCED`.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `everyCauseTheWorkerCanEmitIsInTheClosedSet` | reflection over the worker's emit sites finds no literal outside the enum |
| `theDatabaseRefusesACauseOutsideTheSet` | inserting `failure_cause = 'whatever'` violates the V46 check |
| `aNonFastForwardPushIsItsOwnCauseAndIsNotRetryable` | a rejected non-fast-forward push classifies `NON_FAST_FORWARD`, `retryable=false` |
| `aRejectedCredentialIsNotRetryable` | `CREDENTIAL_REJECTED` never retries; retrying spends nothing and cannot succeed |
| `anImagePullFailureIsRetryable` | `IMAGE_UNAVAILABLE` retries; a registry blip is transient |
| `aRunThatPushedNothingIsNotSucceeded` | a run whose agent produced no bundle records `NOTHING_PRODUCED`, not `succeeded` with a null ref |
| `theWorkersOwnFailureDetailIsScrubbed` | a detail carrying a token, a percent-encoded token, or a Basic-auth URI is redacted before it is stored |
| `aFailureDetailIsAdminOnly` | `GET /api/runs/{id}` as a viewer omits `failureDetail`; as an admin includes it |

Mutation targets: delete the check constraint (the database scenario must fail); make
`NON_FAST_FORWARD` retryable (its scenario alone must fail); remove the scrub (the credential
scenario alone must fail).

---

## Task 2: The run event stream on `cs.run-events`

**FR-F5, ADR-034, NFR-F6.** A normalized stream, tailable live, retained with a TTL, and never in
the aggregate's durable log.

**Files:**
- New: `spire-contract/…/event/RunEventRecord.java` (the wire envelope; the *vocabulary* stays in
  `spire-harness` per ADR-034, and this carries it without promoting it)
- New: `spire-orchestrator/…/factory/RunEventProjection.java`, `RunEventSweep.java`
- New: `V47__run_event.sql` — bounded, TTL'd, payload encrypted (ADR-011: a tool result may quote source)
- New: `spire-orchestrator/…/factory/RunEventSocket.java` (`/api/ws/runs/{runId}`)
- Modify: `RunLauncher` to publish, `application.yml` channels, `DlqTopics`
- Delete: `techdebt/global/3-3-run-event-accumulation-is-unbounded.md`

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `everyHarnessEventReachesTheTopicNormalized` | a harness line becomes exactly one `RunEventRecord` with its type preserved |
| `theStreamIsBoundedPerRun` | past the per-run cap, the oldest rows go and the newest are kept — a truncated tail beats an unbounded table |
| `theSweepDeletesPastTheTtlAndNothingElse` | a row inside the window survives the same sweep that deletes one outside it |
| `aToolResultIsEncryptedAtRest` | the stored payload is not readable as plaintext, and decrypts with the run's AAD |
| `nothingFromTheRunStreamEntersTheEventStore` | after a run with events, `event_log` holds only milestones |
| `theLiveTailDeliversToASubscriber` | a subscriber to the socket receives an event published after it connected |
| `aViewerMayTailButNotAnUnlistedRun` | authorization matches the run detail page's rule |

The bounded scenario is the load-bearing one: the debt entry it closes exists because the M0
accumulation had no cap at all.

---

## Task 3: `finalize` before `destroy`, and a failed salvage blocks teardown

**FR-F7.** Destroying on a failed push is exactly the loss this step exists to prevent.

**Files:** `RunLauncher`, `DockerRunRuntime`, `RunAttentionRows`, `FactoryRunProjection`; delete
`techdebt/spire-run-worker/2-3-…`.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `aFailedSalvagePreservesTheUnit` | no `destroy` call is made, and the containers remain findable by label |
| `aFailedSalvageKeepsEveryPushThePublisherReported` | the pushed ref survives into the result rather than being discarded with the failure |
| `aFailedSalvageRaisesAnAttentionRow` | an operator sees the preserved workspace and what to do, and the row clears when it is reclaimed |
| `aSuccessfulSalvageStillDestroys` | the preservation path is not a leak for the common case |
| `teardownNeverPrecedesSalvage` | ordering asserted directly, since both are calls on the same object and a reordering compiles |
| `anOperatorCanReclaimAPreservedWorkspace` | the reclaim endpoint destroys it and clears the row |
| `aPreservedWorkspaceExpires` | expiry reclaims it too; "never by the failure path itself" is the rule |

---

## Task 4: A run writes to the charge ledger

Closes `techdebt/spire-orchestrator/3-3-run-token-usage-is-dropped-and-a-run-writes-no-charge.md`.
M0 delivered `V42`'s neutral charge subject precisely so this could land, and then dropped the usage
on the floor.

**Files:** `RunResultSaga`, `LlmCharges`, `CallRefs`; the run's `ModelUsage` mapping.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `aFinishedRunWritesOneChargeLinePerTokenType` | the ledger rows match the usage the harness reported |
| `theChargeNamesTheRunAsItsSubject` | subject is the run, not a review — the V42 column used as designed |
| `aRedeliveredResultDoesNotDoubleCharge` | the `UNIQUE (call_ref, token_type)` guard covers the run path too |
| `aRetriedRunChargesUnderItsOwnAttempt` | attempt 2 does not collide with attempt 1's key, and does not overwrite it |
| `unknownUsageIsRecordedAsUnknownNotZero` | the ADR-023 rule holds here: a missing count is a category, never a zero |
| `aRunOnAnUnpricedModelIsRefusedBeforeItSpends` | the pre-spend priceability check covers runs |

---

## Task 5: `workspace_lease` — owner and heartbeat

Prerequisite for Task 6, and half of `techdebt/spire-run-worker/2-4-…`. Also closes
`techdebt/spire-run-worker/4-1-runstarted-carries-the-run-id-as-its-provider-run-id.md`, because the
lease is where the unit's real identity finally has somewhere to live.

**Files:** new `spire-run-worker/src/main/resources/db/migration/V2__workspace_lease.sql`, new
`WorkspaceLeases.java`, `RunLauncher`, `RunStarted`.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `aLeaseIsWrittenBeforeTheUnitIsCreated` | ordering: a crash between the two leaves a lease, never an unleased sandbox |
| `theHeartbeatAdvancesWhileTheRunIsAlive` | `heartbeat_at` moves without the run finishing |
| `theLeaseIsReleasedOnEveryTerminalPath` | success, failure, refusal and cancel all release; a preserved workspace keeps its lease on purpose |
| `theOwnerIdIsThisReplica` | two replicas write different owners |
| `runStartedCarriesTheUnitIdNotTheRunId` | `providerRunId` names the sandbox, so an operator can find it |

---

## Task 6: The orphan watchdog

**FR-F8.** The architecture is explicit that a naive watchdog is worse than the leak: with two
replicas on one daemon, enumerating every sandbox reaps a sibling's live hour-long run.

> An **orphan** is a sandbox whose `workspace_lease` row is absent, or whose lease heartbeat is
> older than N missed intervals. Reaping an orphan always runs `finalize` before `destroy`.

**Files:** new `OrphanWatchdog.java`, `RunRuntime.discoverOrphans` already exists on the SPI.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `aSiblingsLiveRunIsNeverReaped` | a fresh heartbeat owned by another replica survives the sweep |
| `aSandboxWithNoLeaseIsAnOrphan` | the control plane lost it; reap it |
| `aStaleHeartbeatPastNIntervalsIsAnOrphan` | exactly at N it is not yet, past N it is — the boundary asserted on both sides |
| `reapingSalvagesBeforeDestroying` | the Task 3 ordering holds on this path too |
| `aFailedSalvageDuringReapPreservesTheSandbox` | the watchdog does not become the delete path that Task 3 forbids |
| `theWatchdogRecordsWhatItReaped` | a reaped run gets a classified failure, not a silent disappearance |

---

## Task 7: `cs.run-control`, and cancel that actually cancels

**FR-F6, first half.** Closes `techdebt/spire-run-worker/3-3-cancelrun-is-a-no-op.md` and
`techdebt/spire-run-worker/3-3-a-gate-refusal-does-not-stop-the-agent.md`.

The topic is the point: a cancel must not queue behind the run it cancels, and the M0 dispatcher is
deliberately ordered and blocking. So control rides its own topic into a **non-blocking listener
beside** the executor.

**Files:** new `RunControlListener.java`, `RunRegistry.java` (live handles), `application.yml`
channels, `DlqTopics`, `RunResource` cancel endpoint.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `aCancelReachesARunningRunWithoutQueueing` | the control listener is not the ordered blocking channel — asserted by cancelling while a run occupies the executor |
| `aCancelStopsTheContainersAndReleasesTheLease` | not merely a status write |
| `aCancelledRunSalvagesFirst` | work already pushed is kept; cancel is not a delete |
| `aCancelForAnUnknownRunIsHarmless` | late or duplicate cancels do not fail the channel |
| `aGateRefusalStopsTheAgentToo` | the agent does not keep burning tokens after the publisher refused |
| `aCancelIsIdempotent` | two cancels record one cancellation |

---

## Task 8: Steer, where the harness declares it

**FR-F6, second half.** Capability-gated: the SPI already carries declared capabilities, and a
harness that cannot steer must refuse rather than silently drop the instruction.

**Files:** `RunCommand.SteerRun`, `HarnessAdapter` capability, `CodexAdapter`, `RunControlListener`.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `aHarnessDeclaringSteerReceivesTheInstruction` | it reaches the running agent |
| `aHarnessNotDeclaringSteerRefusesVisibly` | an explicit refusal with a reason, never a silent drop |
| `steeringAFinishedRunIsRefused` | and says so |
| `aSteerInstructionIsBounded` | the same length cap the prompt has, for the same reason |
| `aSteerIsRecordedInTheRunStream` | an operator can see that a human intervened |

---

## Task 9: Idempotent dispatch, and ambiguity that fails closed

**FR-F10.** Acking on receipt moves the redelivery guarantee from Kafka to the claim row, so a lost
dispatch response cannot be recovered by redelivery. Intent is journalled **before** the request and
an ambiguous outcome fails closed into `dispatch_uncertain`, which an operator resolves.

**Files:** `V48__dispatch_intent.sql`, `RunResource`, `FactoryRunProjection`, `RunAttentionRows`.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `intentIsJournalledBeforeTheBrokerIsCalled` | a crash between the two leaves a journalled intent, never a silent send |
| `aLostAckBecomesDispatchUncertainNotQueued` | the ambiguous case is explicit, not optimistic |
| `anUncertainDispatchIsNeverAutomaticallyRetried` | retrying a maybe-sent paid run is the failure this exists to prevent |
| `anOperatorCanResolveAnUncertainDispatch` | both ways: it ran, or it did not |
| `anUncertainDispatchRaisesAnAttentionRow` | and the row clears on resolution |
| `aDuplicateDispatchOfTheSameIntentIsRefused` | the claim row remains the sole idempotency mechanism |

---

## Task 10: The credential pool with rotation

**FR-F12.** Closes `techdebt/spire-orchestrator/3-3-a-run-defaults-to-the-reviewers-own-model-key.md`.

**Files:** `V49__harness_credential.sql` (orchestrator, encrypted secrets like every registry),
new `HarnessCredentialPool.java`, `HarnessCredentialResource.java`, `RunAttentionRows`, settings UI.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `theLeastRecentlyExhaustedMemberIsChosen` | rotation order asserted directly, not "some other member" |
| `rateLimitedAndRejectedAreDifferentStates` | one returns at a stated time, the other never does without an operator |
| `aRateLimitedMemberReturnsWhenItsWindowPasses` | the pool heals itself |
| `aRejectedMemberIsNotRetriedUntilAnOperatorActs` | retrying a dead key spends a request per run to learn nothing |
| `exhaustingThePoolIsAFirstClassRefusal` | `ALL_CREDENTIALS_EXHAUSTED`, naming when capacity returns |
| `poolExhaustionRaisesAnAttentionRow` | and it clears when a member recovers |
| `rotationDoesNotRechargeTheCall` | exit criterion 3, asserted on the ledger |
| `aRunNeverFallsBackToTheReviewersKey` | the FACTORY/REVIEWER split holds under exhaustion |

---

## Task 11: The enterprise image environment

**FR-F14.** Corporate CA bundles, proxy variables and private registry credentials, all injected at
run time, **never baked into an image**.

**Files:** `RunUnitSpec`, `RunUnitBuilder`, `DockerRunRuntime`, `deploy/agent/…` docs, `.env.example`.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `aCaBundleIsMountedIntoEveryContainerOfTheUnit` | init, agent and publisher all trust it; one missing is a clone that fails at the forge |
| `proxyVariablesReachTheAgentAndThePublisher` | including the no-proxy list, or internal calls break |
| `privateRegistryCredentialsAuthenticateTheImagePull` | and are not readable from the created container's config |
| `noneOfItIsBakedIntoTheImage` | asserted against the built reference image, which is the actual requirement |
| `theCredentialNeverAppearsInARunEventOrLogLine` | the Global Constraint, on a new injection path |

---

## Task 12: `spire agent-image verify`

**FR-F13, M1 half.** Reports **verified** and **declared** clauses separately, because a conformance
command that blends what it proved with what the image claims is a report an operator cannot act on.

**Files:** new `spire-agent-image/` module or a `spire-run-worker` CLI entry point (decided at
implementation from the licence boundary), `docs/factory/AGENT-IMAGE-CONTRACT.md`.

**Test scenarios**

| Scenario | Asserts |
|---|---|
| `aConformingImagePassesEveryVerifiedClause` | the reference image is its own first test |
| `aMissingEntrypointFailsNamingTheClause` | not "verification failed" |
| `wrongMountOwnershipFailsNamingTheClause` | the uid-1001 rule M0 learned the hard way |
| `declaredClausesAreReportedSeparatelyFromVerifiedOnes` | the headline requirement, asserted directly |
| `aDeclaredButUnverifiableClauseIsNeverReportedAsVerified` | the failure mode the split exists to prevent |
| `theContractDocumentAndTheCheckerAgree` | every clause in the document has a check, and no check is undocumented |

---

## Sequencing and pull request

One branch, `feat/factory-m1`, one pull request opened at Task 0 with the checklist below, updated
as each task lands. Each task is a commit (or a small series), pushed so the workflows run, then a
four-lens `/code-review` whose findings are fixed before the next task starts.

- [ ] Task 0 — serialise the two Docker-driving test modules
- [ ] Task 1 — failure taxonomy as a closed set
- [ ] Task 2 — run event stream on `cs.run-events`
- [ ] Task 3 — finalize before destroy; failed salvage blocks teardown
- [ ] Task 4 — a run writes to the charge ledger
- [ ] Task 5 — `workspace_lease` with owner and heartbeat
- [ ] Task 6 — the orphan watchdog
- [ ] Task 7 — `cs.run-control` and a cancel that cancels
- [ ] Task 8 — steer, where the harness declares it
- [ ] Task 9 — idempotent dispatch, ambiguity failing closed
- [ ] Task 10 — the credential pool with rotation
- [ ] Task 11 — the enterprise image environment
- [ ] Task 12 — `spire agent-image verify`

**Exit criteria are proven, not asserted.** Each maps to a test that must exist before M1 is called
done:

| Exit criterion | Proven by |
|---|---|
| Control plane killed mid-run loses no completed work, no duplicate charge | Tasks 4, 5, 6, 9 together; a dedicated end-to-end scenario that kills the worker between claim and result |
| Sandbox killed mid-run yields a classified failure, not a stall | Tasks 1 and 6; a scenario that destroys the agent container underneath a live run |
| Exhausting one credential rotates without re-charging | Task 10's `rotationDoesNotRechargeTheCall`, read off the ledger |

## Notes for the executor

**The M0 lessons that cost the most, so they are not re-learned.** A guard that names a concrete
class from a scanned module fails the neutrality build, and a conflicting pull request gets **no CI
run at all**, so a branch can be red for hours with nothing to show it. A saga test fake that does
not override every method the new path reaches will open a real database connection from a plain
unit test. `CompletableFuture.cancel` does not interrupt. A test written against the shipped
configuration passes whether or not it reads that configuration, so drive guards with values that
match nothing shipped. And mutation-verify every new guard: three M0 tests were vacuous when first
written, each for a different reason, and only mutation found them.

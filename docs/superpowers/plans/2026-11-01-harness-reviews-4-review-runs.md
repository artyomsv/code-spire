# Harness reviews 4 — review-run plumbing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The orchestrator can fund and dispatch a read-only harness run for a review, the run-worker executes it on its own lane, and its artifact comes back, is charged once under the review and can be cancelled — with nothing yet turning that artifact into findings (step 5).

**Architecture:** A review run is a factory run with a review kind (`REVIEW`, `RECONCILE`, `FOLLOWUP`). A new command `ExecuteReviewRun` travels on a new topic `cs.review-runs` to a second, bounded-concurrency consumer in the run-worker, which builds the ADR-049 read-only unit and returns a new result `RunReviewArtifact` on `cs.run-results`. `RunResultSaga` hands review results to a review-run port before the pull-request step; the run plane's charging skips review kinds and a review-run charger records one charge under the review's call slot with `credential_ref`. A superseded head or a closed pull request sends `CancelRun` for the review's live run.

**Tech Stack:** Java 25, Quarkus 3.38.3, SmallRye Reactive Messaging (Kafka), Flyway, JUnit 5; spire-ui TypeScript for the run-kind union.

**Spec:** `docs/superpowers/specs/2026-10-30-harness-reviews-design.md` — read "Designer's calls after the builder check", "Final design review" and "After step 2" (which defers step 3 and records the measured hosts and Codex flags).

## Global Constraints

- No local builds, tests or linters: CI runs them (`fast tests`, `service tests + packaging`, `dashboard`). Each task's "run" step means: push, read the CI result.
- ADR-029: harness runs never execute inside `spire-review-worker`; review runs run on the run-worker.
- Everything between components is an async command or event; review-run messages are keyed by `runId` on `cs.review-runs` like `cs.run-commands`, and carry the `reviewId` in their binding.
- Domain events are appended only by the aggregate (ADR-010). This step appends no review event.
- Diffs are never persisted (ADR-011). The run command carries no diff.
- Credentials: the run command's harness credential is sealed with AAD `"run-harness:"+runId` exactly as `ExecuteRun` does; `toString` redacts it.
- No hard-coded provider; no credential defaults; money in millicents.
- A review's wall-clock bound must not exceed `spire.run.max-wall-clock-seconds` (`RunUnitBuilder` already refuses above it).
- Copy, docs, commit messages: imperative, plain; no AI attribution; no closing keyword before an issue number.
- Java: 4-space indent, explicit types (no `var`), methods ≤ 30 lines, classes ≤ 300 lines, ≤ 3 parameters (records for more).
- Every new guard is mutation-verified (CLAUDE.md): break the production line, exactly one test fails.

## Review Focus

1. A redelivered `ExecuteReviewRun` (worker crash before ack) must not start a second unit: the `execute` claim in `runworker.run_claim` dedups it — test in Task 4.
2. A `CancelRun` that arrives before the review run starts must stop it from ever starting (the `cancel` claim), and one that arrives while it runs must stop the unit — test in Task 4.
3. A seat review run must be charged once, with its tokens, cost 0 (`priceUnmetered`), its `credential_ref`, under the review's call slot — and the run plane's `RunCharges` must not charge it a second time — tests in Task 5.
4. An artifact larger than the review cap must still come back as a result that is charged and marked over cap, never as a lost paid run — tests in Task 3 and Task 4.
5. A long factory build must not delay a review: the review lane is a different topic and consumer group, and its concurrency is its own — test in Task 4 (config) and the CI-run service test.
6. A supersede or close that arrives after the review run already finished must send nothing (no `CancelRun` for a terminal run) — test in Task 6.

---

### Task 1: Codex declares the measured hosts and switches off its extra calls

The design's "After step 2" section measured that `features.plugins=false` stops `github.com`, `api.github.com` and `chatgpt.com` (plugins) and `analytics.enabled=false` stops `ab.chatgpt.com`; a seat also needs `features.apps=false`.

**Files:**
- Modify: `spire-harness-codex/src/main/java/dev/codespire/harness/codex/CodexAdapter.java` (`command` ~:100-108, `egressHosts` ~:430)
- Test: `spire-harness-codex/src/test/java/dev/codespire/harness/codex/CodexAdapterTest.java` (or the existing command/egress test class in that module)
- Modify: `docs/factory/RUN-TOPOLOGY.md` (the egress section: the measured table, from the spec's "After step 2", and the flags)

**Interfaces:** Produces: `codex exec` gains ` -c features.plugins=false -c analytics.enabled=false` always, plus ` -c features.apps=false` when the invocation carries `HarnessInvocation.SIGN_IN`; placed before ` -C`. `egressHosts` returns `Set.of("api.openai.com")` for a key and `Set.of("chatgpt.com", "sdmntprcentralus.oaiusercontent.com", "sdmntprsouthcentralus.oaiusercontent.com", "sdmntprwestus.oaiusercontent.com")` for a seat.

- [ ] **Step 1: Write the failing tests**
  - `aKeyRunSwitchesOffPluginsAndAnalytics`: the script contains `-c features.plugins=false -c analytics.enabled=false` and does not contain `features.apps`.
  - `aSeatRunAlsoSwitchesOffApps`: with `SIGN_IN` in credentials, the script contains all three flags.
  - `aSeatDeclaresTheHostsMeasuredOnASeat`: `egressHosts` equals the four-host set above.
  - `aKeyDeclaresOnlyTheModelApi`: `egressHosts` equals `Set.of("api.openai.com")`.
- [ ] **Step 2: Push; CI fails on these four.**
- [ ] **Step 3: Implement.** Flags are constants in the adapter; keep the existing comment style (why each flag, with the measurement date 2026-11-01).
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Declare Codex's measured hosts and switch off its extra calls`.

### Task 2: Review run kinds

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunKind.java` (enum + `chargeKind()`)
- Create: `spire-orchestrator/src/main/resources/db/migration/V107__factory_run_review_kinds.sql`
- Modify: `spire-ui/src/api.ts` (`RunKind` union :1151), `spire-ui/src/components/Runs.tsx` (label/filter :159-160)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunCharges.java` (skip review kinds)
- Test: `RunKindTest` (orchestrator), a migration test in the style of the existing `factory_run` CHECK tests, `RunChargesTest`, the Runs component test

**Interfaces:** Produces: `RunKind { BUILD, FIX, SPEC, PLAN, REVIEW, RECONCILE, FOLLOWUP }`; `RunKind.chargeKind()` maps each review kind to the `ChargeKind` of the same name; `boolean RunKind.isReview()` true for the three review kinds. `factory_run_kind_closed` admits the three new values. `RunCharges.record` returns without charging when `runs.kindOf(runId)` is a review kind.

- [ ] **Step 1: Write the failing tests**
  - `RunKindTest.everyReviewKindChargesUnderItsOwnName` (REVIEW→REVIEW, RECONCILE→RECONCILE, FOLLOWUP→FOLLOWUP) and `onlyTheReviewKindsAreReviews`.
  - Migration test `aReviewRunRowIsAdmitted` (insert kind `REVIEW` passes) and `anUnknownKindIsStillRefused` (kind `TEST-UNKNOWN` fails).
  - `RunChargesTest.aReviewRunIsNotChargedByTheRunPlane`: a `RunFinished`/`RunFailed` with tokens for a run whose kind is `REVIEW` writes no ledger row.
  - UI: the Runs list renders a `REVIEW` run with the label `Review` (and `Re-review`, `Reply` for the other two).
- [ ] **Step 2: Push; CI fails on these.**
- [ ] **Step 3: Implement.** V107 drops and re-adds `factory_run_kind_closed` with `('BUILD','FIX','SPEC','PLAN','REVIEW','RECONCILE','FOLLOWUP')`. The ledger CHECK `llm_charge_kind` already admits the review kinds (V42) — do not touch it.
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Add review run kinds`.

### Task 3: The review-run command and result in the contract

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/review/ReviewRunBinding.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/RunCommand.java` (new permit `ExecuteReviewRun`)
- Create: `spire-contract/src/main/java/dev/codespire/contract/event/RunReviewArtifact.java`; modify `RunResult.java` (new permit)
- Modify: every exhaustive switch over `RunResult` (compile errors find them: `EgressResults.attach`, `FactoryRunProjection.recordEgress`, `RunResultSaga`, serializers/deserializers' type ids) and over `RunCommand`
- Modify: `ContractSchemaSnapshotTest` snapshot; `docs/CONTRACT.md` (command, result, topic `cs.review-runs`)
- Test: contract round-trip tests in the style of the existing `ExecuteWorkDraftRun` / `RunWorkArtifact` ones

**Interfaces:** Produces:
- `record ReviewRunBinding(String reviewId, Kind kind, long prId, String commit, String callSlot)` with `enum Kind { REVIEW, RECONCILE, FOLLOWUP }`; all non-null, `prId > 0`.
- `record RunCommand.ExecuteReviewRun(String runId, ExecuteRun execution, ReviewRunBinding review)` — `runId` equals `execution.runId()`; credentials delegate to `execution`; `toString` redacts.
- `record RunReviewArtifact(String runId, ReviewRunBinding review, String text, boolean overCap, Map<String,Long> tokenUsage, long activeWallSeconds, List<EgressAttempt> egress) implements RunResult`; `MAX_TEXT_BYTES = 256 * 1024`; `text` is null exactly when `overCap`; a `withEgress` wither like `RunWorkArtifact`'s.

- [ ] **Step 1: Write the failing tests**
  - `anExecuteReviewRunRoundTripsAndRedactsItsCredentials`.
  - `aRunReviewArtifactRoundTrips`, `anOverCapArtifactCarriesNoText` (overCap true ⇒ text null; text null ⇒ overCap true; both directions refused otherwise), `aReviewArtifactUpToTheCapIsAccepted` (exactly 256 KiB of UTF-8 accepted, one byte more refused at construction).
  - `aReviewRunBindingRefusesABlankReviewIdOrCommit`.
- [ ] **Step 2: Push; CI fails on these.**
- [ ] **Step 3: Implement**, then fix every exhaustive switch the new permits break: `EgressResults.attach` and `FactoryRunProjection.recordEgress` handle `RunReviewArtifact` like `RunWorkArtifact`; no `default` branches.
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Add the review-run command and result`.

### Task 4: The run-worker's review lane

**Files:**
- Create: `spire-run-worker/src/main/java/dev/codespire/runworker/ReviewRunDispatcher.java`
- Modify: `RunUnitBuilder.java` (`buildReview(ExecuteReviewRun, HarnessAdapter)`), `RunLauncher.java` (`launchReview`), `ReadOnlyResults.java` (review result shaping), the publisher handoff cap
- Modify: `spire-publisher/.../HandoffFiles.java`, `spire-run-worker/.../PublisherOutcome.java` (artifact cap read per unit), `spire-run-worker/src/main/resources/application.yml`, `RunAckBudget.java`
- Test: `ReviewRunDispatcherTest`, `RunUnitBuilderReviewTest`, `ReadOnlyResultsTest` additions, a publisher `HandoffFilesTest` addition, `RunAckBudgetTest` addition

**Interfaces:**
- Consumes: Task 3 types.
- Produces: channel `review-runs-in` — topic `cs.review-runs`, group `spire-run-worker-reviews`, `max.poll.records: 1`, `failure-strategy: dead-letter-queue` to `cs.dlq`, offset reset `earliest` (corrected in review of PR #367: `latest` skipped a command sent before the group's first assignment; the execute and cancel claims make the replay safe), throttled commit with the same `unprocessed-record-max-age.ms` rule `RunAckBudget` enforces for `run-commands-in`. `ReviewRunDispatcher.onCommand(Message<RunCommand>)` is `@Incoming("review-runs-in") @Acknowledgment(MANUAL) @Blocking(value = "review-runs", ordered = false)`; the worker pool `review-runs` has `max-concurrency: ${SPIRE_RUN_REVIEW_CONCURRENCY:2}` (per run-worker instance; documented as such). It accepts only `ExecuteReviewRun`; anything else is acked and warned.
- Produces: `RunUnitSpec RunUnitBuilder.buildReview(ExecuteReviewRun, HarnessAdapter)` — the READ_ONLY topology with `spire-publish-none`, and the artifact cap env `SPIRE_ARTIFACT_MAX_BYTES=262144` on the publisher (read-only drafts keep 32768). The publisher reads `SPIRE_ARTIFACT_MAX_BYTES` (default 32768 when unset — the existing images keep their behaviour) and records an over-cap artifact as over cap instead of dropping it.
- Produces: `RunResult RunLauncher.launchReview(ExecuteReviewRun, RunObserver)` returning `RunReviewArtifact` (or `RunFailed` with an existing cause when the agent produced nothing: `ARTIFACT_MISSING`).

- [ ] **Step 1: Write the failing tests**
  - `aRedeliveredReviewRunStartsNoSecondUnit` (second delivery with the `execute` claim taken launches nothing, acks).
  - `aReviewRunCancelledBeforeItStartsNeverStarts` (`cancel` claim taken ⇒ no launch, result `RunFailed` with the existing cancelled cause).
  - `theReviewLaneOnlyRunsReviewRuns` (an `ExecuteRun` on `review-runs-in` is acked, not launched).
  - `aReviewUnitIsReadOnlyAndCarriesTheReviewCap` (publisher command `spire-publish-none`, `SPIRE_ARTIFACT_MAX_BYTES=262144`; a draft unit still has 32768 or none).
  - `anOverCapReviewArtifactComesBackChargeableNotLost` (`ReadOnlyResults`: over-cap handoff ⇒ `RunReviewArtifact(overCap=true, text=null)` with the token usage).
  - Publisher: `theArtifactCapIsReadFromTheUnit` and `anUnsetCapKeepsThirtyTwoKibibytes`.
  - `RunAckBudgetTest.theReviewLaneMustOutliveItsLongestRun` (startup refuses when the review lane's max age ≤ the wall clock bound).
  - Config test: the two lanes have different topics and group ids (`theReviewLaneIsNotTheBuildLane`).
- [ ] **Step 2: Push; CI fails on these.**
- [ ] **Step 3: Implement.** Reuse the `execute`/`cancel` claims, the cancel registry and the launcher's read-only path; extract shared pieces rather than copying `RunDispatcher` (each class ≤ 300 lines). Docker serialisation rules (CLAUDE.md "Test tasks that drive the real Docker daemon hold a lock") are unchanged — no new Docker-driving test module.
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Run review runs on their own lane`.

### Task 5: Dispatch, results and charges in the orchestrator

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/review/run/ReviewRunDispatch.java`, `ReviewRunIds.java`, `ReviewRunCharges.java`, `ReviewRunResults.java` (the port, an interface) and its step-4 implementation `RecordingReviewRunResults.java`
- Modify: `RunCommandEmitter.java` (`@Channel("review-runs-out")`, topic `cs.review-runs`, `acks: all`, `waitForWriteCompletion: true`, `RunCommandSerializer`), `application.yml`, `RunResultSaga.java`, `ChargeCall.java` (`forReview` overload with `credentialRef`), `QueuedRun` (a review-kind row with `review_id`)
- Modify: `FactoryConfig` — `spire.factory.review-wall-clock-seconds` (`${SPIRE_FACTORY_REVIEW_WALL_CLOCK_SECONDS:900}`)
- Test: `ReviewRunIdsTest`, `ReviewRunDispatchTest`, `ReviewRunChargesTest`, `RunResultSagaTest` additions

**Interfaces:**
- Consumes: Task 2 `RunKind`, Task 3 types, `RunFunding.fund(Choice)`, `RunIds.of(...)`, `CallRefs.of(reviewId, slot, kind)`, `LlmModelPricer.priceUnmetered` / `priceCall`.
- Produces: `String ReviewRunIds.of(ScmType type, String workspace, String slug, ReviewRunBinding binding)` — subject `review-<prId>-<commit12>-<slotDigest>` where `slotDigest` is the first 8 hex chars of SHA-256 over `kind|callSlot` (deterministic: same review, commit, kind and slot ⇒ same id), attempt 1.
- Produces: `sealed interface ReviewRunDispatch.Outcome permits Dispatched(String runId), Refused(String reason)`; `Outcome ReviewRunDispatch.dispatch(Connection, ReviewRunRequest)` where `record ReviewRunRequest(UUID repositoryId, ReviewRunBinding binding, RunFunding.Choice choice, String prompt)`. It funds through `RunFunding` (its `IllegalStateException` message becomes `Refused.reason`), writes the queued `factory_run` row (kind from the binding, `review_id`, `paid_by`, `harness_credential_id`) and emits on `review-runs-out`. No caller in this step.
- Produces: `interface ReviewRunResults { void accept(RunResult result, ReviewRunBinding binding); }` called by `RunResultSaga` for any result whose run kind `isReview()`, after `projection.apply` and `credentials.reactTo`, before the drafts/work-items/pull-request steps, then the saga returns. `RecordingReviewRunResults` logs the outcome (no artifact text in the log) and does nothing else.
- Produces: `ReviewRunCharges.record(RunResult result, ReviewRunBinding binding)` — one charge via `ChargeCall.forReview(reviewId, CallRefs.of(reviewId, binding.callSlot(), kind), kind, model, lines, credentialRef)`; a seat run priced with `priceUnmetered`, a key run with `priceCall`; skipped when no tokens were bought. The binding for a `RunFailed` (which carries none) is read from the `factory_run` row.

- [ ] **Step 1: Write the failing tests**
  - `ReviewRunIdsTest.theSameReviewCommitAndSlotGiveTheSameId`, `aNewSlotGivesANewId`, `theIdParsesBackWithRunIds`.
  - `ReviewRunDispatchTest.aFundedReviewRunIsQueuedAndSentOnTheReviewLane` (row kind `REVIEW`, `review_id` set; message on `review-runs-out`, never on `run-commands-out`).
  - `aSpentPoolIsRefusedWithItsReason` (`subscription_unavailable` ⇒ `Refused("subscription_unavailable")`, no row, no message).
  - `aWallClockAboveTheRunWorkersBoundIsRefusedAtStartup` (config validation, mirroring how `spire.factory.wall-clock-seconds` is validated if it is; otherwise a startup check).
  - `ReviewRunChargesTest.aSeatReviewRunIsChargedOnceAtZeroWithItsTokensAndCredential`, `aKeyReviewRunIsPricedAsACall`, `aReviewRunWithNoTokensIsNotCharged`, `aFailedReviewRunWithTokensIsStillCharged`.
  - `RunResultSagaTest.aReviewArtifactNeverReachesThePullRequestStep` and `aReviewResultIsChargedByTheReviewChargerOnly` (ledger has exactly one row set for the call ref).
- [ ] **Step 2: Push; CI fails on these.**
- [ ] **Step 3: Implement.** Saga fakes override every method the new path reaches (CLAUDE.md gotcha: a fake with an un-overridden method opens a real database).
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Dispatch review runs and charge them under the review`.

### Task 6: Cancel a review's run when its head is superseded or its pull request closes

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/review/run/ReviewRunCancellation.java`
- Modify: the place that observes `ReviewSuperseded` and `CancelReview` outcomes (the domain-event sink path, `DomainEventSink.java` ~:57, or `IntegrationSaga.java` ~:149 for close) — call `ReviewRunCancellation`
- Test: `ReviewRunCancellationTest`

**Interfaces:**
- Produces: `void ReviewRunCancellation.supersede(String reviewId, String newCommit)` — sends `CancelRun(runId, "superseded by " + newCommit12)` on `run-control-out` for every `factory_run` of that review with a review kind, a non-terminal status and a commit other than `newCommit`; `void ReviewRunCancellation.close(String reviewId)` — the same for every live review run of the review, reason `"pull request closed"`. Never emits `CancelReview` (ADR-013).

- [ ] **Step 1: Write the failing tests**
  - `aSupersededHeadCancelsTheOlderLiveRun`.
  - `aSupersedeAfterTheRunFinishedSendsNothing` (terminal status ⇒ no message).
  - `theNewHeadsOwnRunIsNotCancelled`.
  - `aClosedPullRequestCancelsEveryLiveReviewRun`.
  - `aFactoryRunOfTheSameReviewIsNeverCancelledHere` (a `FIX` row with the same `review_id` stays).
- [ ] **Step 2: Push; CI fails on these.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Cancel a review's run when its head is superseded or its pull request closes`.

### Task 7: Docs

**Files:**
- Modify: `docs/DECISIONS.md` — ADR-050 "Reviews may run on a harness, on the run-worker's own lane" (supersedes ADR-018's rule for harness reviews: operator decision, cost, the read-only boundary; the lane, the per-instance concurrency, charging once under the review, the deferred credential proxy and fork rule from the spec's "After step 2").
- Modify: `docs/CONTRACT.md` (if not done in Task 3), `docs/factory/RUN-TOPOLOGY.md` (the review lane), `.env.example` (`SPIRE_RUN_REVIEW_CONCURRENCY`, `SPIRE_FACTORY_REVIEW_WALL_CLOCK_SECONDS` with comments), `deploy/` compose and Helm values only if they enumerate run-worker variables, `docs/UNVERIFIED.md` (review runs are dispatched by nothing yet; the lane is proven only by tests until step 5's live proof).
- [ ] **Step 1: Write them; Step 2: push; CI green; Step 3: commit** `Record the review lane`.

## Out of scope (step 5 and later)

The prompt and preamble, init cloning the pull request head, the workspace files, parsing the artifact into findings, the review's state change, attention for a refused dispatch, the Runs screen transcript link, the per-repository harness setting in the UI, fork pull requests and the credential-injecting proxy (spec "After step 2").

## Changes after the plan check (they override the tasks above where they disagree)

1. **Task 2, V107 admits a review row.** Besides `factory_run_kind_closed`, V107 replaces `factory_run_fix_names_its_target` (V54:48-51): `FIX` keeps its arm; `REVIEW`/`RECONCILE`/`FOLLOWUP` require a non-blank `review_id` and a null `finding_ref`; every other kind keeps `review_id` null. V107 also adds `review_call_slot TEXT` and `review_pr_id BIGINT`, with a CHECK that a review kind carries both (and other kinds carry neither). `base_commit` holds the reviewed head. Tests: `aReviewRunRowIsAdmitted` inserts every NOT NULL column plus `review_id`, slot and pr id; add `aReviewRowWithoutItsReviewIsRefused` and `aReviewRowWithoutItsSlotIsRefused`.
2. **Task 4, the artifact travels in parts.** The publisher's outcome line is capped at 64 KiB (`OutcomeWriter.java:30`) and clipped by `DockerRunRuntime` (:131, :681), so a 256 KiB artifact cannot travel as one line. The publisher writes the artifact as ordered parts of at most 32 KiB of raw text each, on separate outcome lines; `PublisherOutcome` reassembles them and refuses a gap, a duplicate or a total above the unit's cap (an over-cap total is `overCap`, never a lost run). The line limits stay. Test: `aTwoHundredKibibyteReviewArtifactArrivesWhole` through `PublisherOutcome`, plus `aMissingPartIsRefused`. A draft's 32 KiB artifact still arrives (`aDraftArtifactStillArrives`).
3. **Task 5, a result finds its binding in the row.** `ReviewRunBinding FactoryRunProjection.reviewBindingOf(String runId)` reads `review_id`, `kind`, `review_pr_id`, `base_commit`, `review_call_slot`; `QueuedRun.asReviewFor(ReviewRunBinding)` sets them (`ofKind` does not). Every fake overrides `kindOf` and `reviewBindingOf`.
4. **Task 5, the saga guards and orders review results.** `RunResultSaga`'s guard line gains `reviewRuns.acceptsBinding(result)` (a `RunReviewArtifact`'s binding must equal the stored row's). Order: guards → `projection.apply` → `charges.record` (skips review kinds) → `credentials.reactTo` → when `kindOf(runId)` is a review kind: `ReviewRunCharges.record`, then `ReviewRunResults.accept`, then return.
5. **Task 3, the real switch list.** `FactoryRunProjection.apply` (:848): a `RunReviewArtifact` sets status `succeeded`, over cap or not. Also `RunTokenUsage` (:195), `WorkRunWorker` (:293), `EgressResults` (run-worker), `DlqTopics` (:46, :52: `ExecuteReviewRun → cs.review-runs`, `RunReviewArtifact → cs.run-results`), and `@JsonSubTypes` on both roots. `ReviewRunBinding`'s shape gets its own round-trip test because the schema snapshot does not recurse.
6. **Task 5, the command is built from the review.** The source branch and head come from `review_status` (as `FixTargets` reads them); the clone credential is the REVIEWER account's (`repositoryAccounts.resolve(repositoryId, ProviderRole.REVIEWER)`), packed for `"run-scm:"+runId`; the FACTORY account is not used. `ExecuteReviewRun` has `ExecuteWorkDraftRun`'s invariants (no `startFromRunId`, no `existingBranch`). `ReviewRunRequest` carries the repository id, the binding, the choice and the prompt; the dispatch reads the rest.
7. **Task 5, dispatch uses the existing transport.** `RunLaunch.launch(ExecuteReviewRun)` routes to `review-runs-out`, sent after the row's transaction commits, and keeps `RunLaunch`'s `Dispatched` / `DefiniteMiss` / `Uncertain` marks. `ReviewRunDispatch.Outcome` permits `Dispatched`, `AlreadyQueued` (a deterministic id already queued: `queued()` returned false), `Uncertain` and `Refused`. `dispatch` takes no `Connection`.
8. **Task 6, the hooks.** `ReviewSuperseded(commit)` names the *old* commit. A new `@Incoming` on `cs.events` with its own group id and offset reset `latest` (not `DomainEventSink`, a rebuildable projector) calls `supersede(reviewId, supersededCommit)`, which cancels live review-kind runs of that review whose `base_commit` equals the superseded commit. Close hooks `PullRequestClosed` in `IntegrationSaga` (:146), so a live reply run on a completed review stops too. Test fix: the terminal row in `aSupersedeAfterTheRunFinishedSendsNothing` carries the superseded commit.
9. **Task 4, lane settings.** `review-runs-in` also sets `max-queue-size-factor: 1`; `RunAckBudget` applies the `wall + drain + 300 s` rule to `review-runs-in.*`. The annotation is `io.smallrye.reactive.messaging.annotations.Blocking` (named pool). Concurrency is per instance; across replicas it needs `cs.review-runs` partitions ≥ replicas — Task 7 records this and what creates the topic.
10. **Task 5, wall clock.** Startup refuses `spire.factory.review-wall-clock-seconds` above `spire.factory.wall-clock-seconds`; `RunUnitBuilder` (:206) stays the worker-side refusal.
11. **Discriminating tests.** `aReviewRunIsNotChargedByTheRunPlane` overrides every fake method (`RunCharges` swallows `RuntimeException`), asserts `recordCharges` is never called, and has a `BUILD` control in the same fixture. The pull-request test uses a review-kind `RunFailed` and asserts `workItems.accept` and `propose` are never called. Add `aReviewRunOnTheBuildLaneLaunchesNothing` (`RunDispatcher` :170).
12. **Smaller.** `ChargeCall.forReview` takes a `ReviewSpend(reviewId, callRef, kind, credentialRef)` record; `ReviewRunIds.of(RepoRef, ScmType, ReviewRunBinding)`. The Runs list shows `—` as the cost of a review run in this step (its charge is under the review; step 5 links them), and a label map renders the kinds (`Runs.tsx` :216). The Codex command is at `CodexAdapter.java:130-135`. Task order: all V107 columns and constraints in Task 2; `apply` and `RunTokenUsage` arms in Task 3; the artifact parts in Task 4.
13. **Review of PR #367.** The review lane reads from `earliest` (above). A dispatch and a cancellation of the same
    review share `pg_advisory_xact_lock(hashtextextended('review-run:'||reviewId, 0))`: `ReviewRunAdmission` checks
    `review_status.pr_state` and V108's `review_run_superseded` (one row per review: the newest ended round) and inserts
    the queued row in one transaction, and the command is sent after the commit; a close or supersede during funding
    refuses the run as `review_run_cancelled`. A supersede ends a round, not a commit: `ReviewRunBinding.round` names
    each call's round, and the supersede listener reads the round it ended from the review's stream at its position.

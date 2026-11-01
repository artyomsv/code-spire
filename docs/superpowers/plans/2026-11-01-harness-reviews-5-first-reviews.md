# Harness reviews 5 — first reviews on a harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A repository whose "Review with" setting names a harness gets its first review of a same-repository pull request from a read-only harness run, and that review's findings, summary and verdict are posted through the existing path.

**Architecture:** The repository gains a harness half of its review setting (V109). On `ContextAssembled` for a first review, `ResultSaga` asks a new `HarnessReviewStart`, which refuses a fork pull request, composes the prompt and calls step 4's `ReviewRunDispatch`. When the run's `RunReviewArtifact` returns, a new `HarnessReviewResults` (replacing `RecordingReviewRunResults`) parses it with the existing `FindingsParser` and hands a `ReviewGenerated` to `ResultSaga`'s existing outcome path (`onReviewGenerated`: corpus, filter, verdict, `PostComments`), with no second charge. A refused start sets the review `refused` and raises an attention row.

**Tech Stack:** Java 25, Quarkus 3.38.3, Flyway, JUnit 5; spire-ui React + TypeScript + React Query, vitest; bash (agent entrypoint).

**Spec:** `docs/superpowers/specs/2026-10-30-harness-reviews-design.md`: read "What the operator decided", "Calls made by the designer", "Answers after the builder check" (2A: Code Spire decides the verdict), "Final design review" and "After step 2" (forks are not reviewed on a harness until step 8). Step 4's plan, `docs/superpowers/plans/2026-11-01-harness-reviews-4-review-runs.md`, lists what already exists.

## Global Constraints

- No local builds, tests or linters: CI runs them (`fast tests`, `service tests + packaging`, `dashboard`). Each "run" step means: push, read the CI result.
- ADR-029: the harness run executes on the run-worker, never in `spire-review-worker`.
- Domain events are appended only by the aggregate (ADR-010). The harness path reuses `ResultSaga`'s existing lifecycle commands (`RecordReviewOutcome`, `RecordFailure`); it appends nothing new.
- Diffs are never persisted (ADR-011). The run command carries no diff: the agent runs `git diff` in its checkout.
- **No fallback** (operator decisions 6 and "Non-goals"): a refused harness review never falls back to the API, and never to another harness.
- **Re-reviews stay on the API in this step** (step 6 moves them). A review with a posted prior round (`ReviewProjection.priorRunFor` present) takes today's API path, on the repository's API provider or the global default.
- **Forks are refused** ("After step 2"): `review_status.from_fork IS NOT FALSE` (true or unknown) refuses the harness review with `fork_pull_request_not_reviewed_on_harness` and raises attention. It is not reviewed by the API either.
- The agent reports findings only; it is never asked for a verdict (2A). `ReviewVerdictDecision` decides.
- Java: 4-space indent, explicit types (no `var`), methods ≤ 30 lines, ≤ 3 parameters (records for more). TypeScript: 2-space, `interface` for object shapes.
- Copy, docs, commit messages: imperative, plain; no AI attribution; no closing keyword before an issue number. No new user-visible "Code Spire" text (CLAUDE.md, the working name).
- A saga test fake overrides every method the new path reaches, with a comment saying why (CLAUDE.md gotcha).
- Every new guard is mutation-verified: break the production line, exactly one test fails.

## Review Focus

1. A harness repository's **re-review** (a prior round was posted) must take the API path, not dispatch a run — `HarnessReviewStartTest.aReReviewIsNotAHarnessRun` in Task 5.
2. A **fork** pull request (and one whose fork status is unknown) must be refused with attention and never dispatched — Task 5.
3. A result from an **ended round** or an old commit must be dropped, never posted — `HarnessReviewResultsTest.aResultOfAnEndedRoundIsDropped` in Task 6.
4. An answer that is **not JSON**, or is **over the cap**, must become a degraded review with a summary, not a lost paid run — Task 6; a 200 KiB answer must not be cut at 32 KiB by the agent image — Task 1.
5. A harness result must be **charged once**: `ResultSaga`'s `chargeGeneratedCalls` must not run for it (step 4's `ReviewRunCharges` already charged it) — Task 6.
6. Switching a repository from **Harness back to API** must clear the harness fields — the V109 CHECK and `RepositoryResourceTest` in Task 3.

---

### Task 1: The agent image honours the review's artifact cap; a seat declares only `chatgpt.com`

The agent entrypoint hard-codes `ARTIFACT_MAX_BYTES=32768` (`deploy/agent/spire-agent-entrypoint.sh:42`, used at `:167`), so a review answer above 32 KiB is cut before the 256 KiB publisher cap sees it. Live test #91 (step 4) showed a seat run calls only `chatgpt.com`.

**Files:**
- Modify: `deploy/agent/spire-agent-entrypoint.sh:42` — `ARTIFACT_MAX_BYTES="${SPIRE_ARTIFACT_MAX_BYTES:-32768}"`, refusing a value that is not a positive integer (fall back to 32768 and print one line to stderr).
- Modify: `spire-run-worker/src/main/java/dev/codespire/runworker/RunUnitBuilder.java` — the agent container of a review run (`buildReview`) gets `SPIRE_ARTIFACT_MAX_BYTES=<RunReviewArtifact.MAX_TEXT_BYTES>`; draft and build runs get nothing new.
- Modify: `spire-harness-codex/src/main/java/dev/codespire/harness/codex/CodexAdapter.java:82-84` — `SEAT_HOSTS = Set.of("chatgpt.com")`, comment: measured live on 2026-11-01 (spire-test #91, three runs).
- Modify: `docs/factory/RUN-TOPOLOGY.md` (egress table: the seat row) and the spec's "After step 2" table gets one line under it: "Re-measured in step 4 (#91): a seat calls only `chatgpt.com`."
- Test: `RunUnitBuilderTest` (existing class), `CodexAdapterTest`, and the entrypoint's existing shell test if one exists (`deploy/agent/` tests; if none, `AgentImageContractTest`-style check in `spire-agent-image` that reads the script text).

**Interfaces:** Produces: env `SPIRE_ARTIFACT_MAX_BYTES` on a review run's agent container.

- [ ] **Step 1: Write the failing tests**
  - `aReviewRunsAgentIsToldTheReviewCap`: the agent spec of `buildReview` has `SPIRE_ARTIFACT_MAX_BYTES=262144`.
  - `aDraftRunsAgentKeepsTheImageDefault`: `buildReadOnly`'s agent spec has no `SPIRE_ARTIFACT_MAX_BYTES`.
  - `aSeatDeclaresOnlyChatgpt` (rename of `aSeatDeclaresTheHostsMeasuredOnASeat`): `egressHosts` equals `Set.of("chatgpt.com")`.
  - `theEntrypointReadsTheArtifactCapFromItsEnvironment`: the script text contains `SPIRE_ARTIFACT_MAX_BYTES` and no bare `ARTIFACT_MAX_BYTES=32768` line.
- [ ] **Step 2: Push; CI fails on these four.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Let a review run's agent hand over its full answer`.

### Task 2: Init keeps the pull request's target branch as `refs/spire/base`

`WorkspaceClone.populate` (`spire-workspace/.../WorkspaceClone.java:77-119`) removes `origin`, so the agent has no target branch to diff against. Init keeps it under its own ref (spec, "Fork clone": "keeps the base under its own ref").

**Files:**
- Modify: `spire-contract/.../command/RunCommand.java:363` — `ExecuteReviewRun(String runId, ExecuteRun execution, ReviewRunBinding review, String targetBranch)`; `targetBranch` required non-blank. No shorter constructor: the only builder is `ReviewRunDispatch` (CLAUDE.md gotcha: a convenience constructor silently drops a component).
- Modify: `spire-orchestrator/.../review/run/ReviewRunDispatch.java` — pass `FixTargets.PushTarget.destBranch()` as `targetBranch`; a blank one is `Refused("review_run_target_unavailable")`.
- Modify: `spire-workspace/.../WorkspaceClone.java` — an optional base branch: when set, before `origin` is removed, `refs/spire/base` is created at `refs/remotes/origin/<base>`; a missing remote branch fails the clone with a message naming the branch.
- Modify: the clone entry point (`CloneMain` in `spire-publisher`) — reads `SPIRE_REVIEW_BASE_BRANCH` (optional).
- Modify: `RunUnitBuilder.buildReview` — the init container of a review run gets `SPIRE_REVIEW_BASE_BRANCH=<targetBranch>`.
- Modify: `docs/CONTRACT.md` (the command row), the contract schema snapshot if it lists the component.
- Test: `WorkspaceCloneTest` (existing, uses a local git origin), `RunUnitBuilderTest`, `ReviewRunDispatchTest`, the `ExecuteReviewRun` round-trip test.

**Interfaces:**
- Produces: `ExecuteReviewRun.targetBranch()`; in the checkout, `refs/spire/base` at the target branch's tip when the clone ran.

- [ ] **Step 1: Write the failing tests**
  - `aReviewCloneKeepsTheTargetBranchAsSpireBase`: origin with `main` and `feature`; populate with base `main`; `git rev-parse refs/spire/base` equals origin's `main`; `origin` is gone.
  - `aMissingTargetBranchFailsTheClone`: base `nope` ⇒ the clone fails, message contains `nope`.
  - `aCloneWithoutABaseCreatesNoSpireRef`: no base ⇒ no `refs/spire/base` (build and draft runs unchanged).
  - `aReviewRunsInitIsGivenTheTargetBranch` (`RunUnitBuilderTest`).
  - `theDispatchSendsTheReviewsTargetBranch` and `aReviewWithNoTargetBranchIsRefused` (`ReviewRunDispatchTest`).
  - `anExecuteReviewRunRoundTripsItsTargetBranch` and `aBlankTargetBranchIsRefused`.
- [ ] **Step 2: Push; CI fails on these.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Keep a reviewed pull request's target branch in its checkout`.

### Task 3: The repository's harness review setting

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V109__repository_review_harness.sql`:
  - `repository` gains `review_harness TEXT`, `review_harness_model TEXT`, `review_harness_effort TEXT`, `review_harness_pay_with TEXT`.
  - CHECK `repository_review_harness_whole`: either all four are null, or `review_harness`, `review_harness_model` and `review_harness_pay_with` are non-blank, `review_harness_pay_with IN ('API_KEY','SUBSCRIPTION')` and `review_llm_provider_id IS NULL` (one "Review with" choice, never both).
  - `review_status` gains `harness_refusal TEXT` (Task 5 sets it; null means none).
- Create: `spire-orchestrator/.../repository/ReviewHarness.java` — `public record ReviewHarness(String harness, String model, String effort, String payWith)`; `boolean subscription()` is `PayWith.SUBSCRIPTION.equals(payWith)`.
- Modify: `RepositoryInput` and `RepositoryView` — a last component `ReviewHarness reviewHarness` (nullable); keep the existing shorter constructors, passing null.
- Modify: `RepositoryRegistry` — insert/update write the four columns (a null `reviewHarness` writes four nulls); validation: unknown harness (not in `HarnessRegistry`) ⇒ 400 `unknown harness`; blank model ⇒ 400; `payWith` through `PayWith.normalise`; both a provider id and a harness ⇒ 400 `choose an API provider or a harness, not both`. New read: `Optional<ReviewHarness> reviewHarness(UUID repositoryId)`.
- Test: `RepositoryResourceTest`, a migration test beside step 4's `FactoryRunReviewKindsTest` style (`RepositoryReviewHarnessSchemaTest`).

**Interfaces:**
- Produces: `ReviewHarness` (above); `Optional<ReviewHarness> RepositoryRegistry.reviewHarness(UUID repositoryId)`; JSON field `reviewHarness: {harness, model, effort, payWith} | null` on the repository view and input.

- [ ] **Step 1: Write the failing tests**
  - `aReviewHarnessRoundTripsThroughCreateAndUpdate` (codex, a model, `high`, `SUBSCRIPTION`).
  - `switchingBackToTheApiClearsTheHarness`: update with `reviewHarness=null` ⇒ the view's `reviewHarness` is null and the four columns are null.
  - `aProviderAndAHarnessTogetherAreRefused` (400).
  - `anUnknownHarnessIsRefused` (400).
  - Schema: `aPartialHarnessRowIsRefused` (harness without model), `aHarnessBesideAProviderIsRefused`, `anUnknownPaymentIsRefused`.
- [ ] **Step 2: Push; CI fails on these.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Let a repository review with a harness`.

### Task 4: "Review with" offers a harness in repository settings

**Files:**
- Modify: `spire-ui/src/components/repositories/RepositoryReviewProvider.tsx` — the `Review with` select gains a last option `Harness` (value `harness`). When chosen, it shows the `Harness` and `Pay with` selects and `BuildModelFields` (`factory/BuildModelFields.tsx`), fed by `buildOptions(repositoryId)` (`buildOptionsKey`) and `fetchLlmModels()` (`preparationKeys.llmModels()`), read only while `harness` is chosen. Reuse `PAY_WITH_LABEL` by moving it from `BuildStep.tsx:37` to `buildDefaultsApi.ts` and importing it in both.
- Modify: `repositoriesApi.ts` — `interface ReviewHarness { harness: string; model: string; effort: string | null; payWith: PayWith }`; `Repository.reviewHarness: ReviewHarness | null`; `RepositoryInput.reviewHarness`.
- Modify: `RepositorySettings.tsx` — `inputOf` maps `reviewHarness`; choosing a provider or the default sets `reviewHarness: null`; choosing `Harness` sets `reviewLlmProviderId: null` and starts from the repository's build defaults' harness, model, effort and payment when they exist.
- Modify: `factory/factoryFixtures.ts` — `reviewHarness: null`.
- Test: `RepositoryRegistryPage.test.tsx`.

**Interfaces:** Consumes Task 3's JSON field.

- [ ] **Step 1: Write the failing tests**
  - `choosing Harness shows the harness, payment, model and thinking selects`.
  - `saving a harness review sends reviewHarness and no provider` (asserts the PUT body: `reviewLlmProviderId: null`, `reviewHarness.harness === 'codex'`).
  - `choosing an API provider after Harness sends reviewHarness null`.
  - `a saved harness review opens with its values selected`.
- [ ] **Step 2: Push; CI (`dashboard`) fails on these.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Offer a harness in a repository's Review with setting`.

### Task 5: A first review on a harness repository dispatches a review run

**Files:**
- Create: `spire-orchestrator/.../review/run/HarnessReviewStart.java` (`@ApplicationScoped`).
- Create: `spire-orchestrator/.../review/run/HarnessReviewPrompt.java` (pure, static).
- Modify: `spire-orchestrator/.../pipeline/ResultSaga.java` — in the `ContextAssembled` branch (`:183-213`), after `updateStage(STAGE_REVIEW)` and before `resolveForReview`: `projection.setHarnessRefusal(reviewId, null)`, then `harnessStart.start(e)`; `NotHarness` continues today's path; `Started` records the timeline (`"result", "harness-review:" + runId`) and sets the note `Reviewing on <harness>, run <runId>`; `Refused` calls a new `refuseHarness(reviewId, commit, refused)`.
- Modify: `ReviewProjection` — `void setHarnessRefusal(String reviewId, String reason)`; `Optional<Boolean> fromFork(String reviewId)` (read of V55's column; null ⇒ empty).
- Modify: `spire-orchestrator/.../attention/AttentionQueries.java` — `harnessRefusedRows`: `review_status` rows with `status = 'refused'`, `harness_refusal IS NOT NULL`, not archived ⇒ `AttentionView("REVIEW_HARNESS_REFUSED", WARNING, "<workspace>/<slug>#<pr>", "Harness review refused: <harness_refusal>", <review link as the other review rows build it>)`.
- Test: `HarnessReviewStartTest`, `HarnessReviewPromptTest`, `ResultSagaHarnessReviewTest`, `AttentionHarnessRefusedTest`.

**Interfaces:**
- Consumes: `ReviewRunDispatch.dispatch(ReviewRunRequest) -> Outcome` (`Dispatched`, `AlreadyQueued`, `Uncertain`, `Refused(reason)`); `ReviewRunRequest(UUID repositoryId, ReviewRunBinding binding, RunFunding.Choice choice, String prompt)`; `RunFunding.Choice(String harness, String model, String effort, boolean subscription)`; `ReviewRunBinding(reviewId, Kind.REVIEW, prId, commit, CallRefs.reviewSlot(commit, round), round)` with `round = ReviewRuns.currentRun(reviewId)`; Task 3's `RepositoryRegistry.reviewHarness`.
- Produces:
  - `HarnessReviewStart.Decision start(IntegrationEvent.ContextAssembled event)`, `sealed interface Decision permits NotHarness, Started, Refused`; `record Started(String runId, String harness)`; `record Refused(String harness, String reason)`.
  - `static String HarnessReviewPrompt.compose(PromptTemplate review, ReviewTarget target)`; `record ReviewTarget(String workspace, String slug, long prId, String commit)` (nested in `HarnessReviewPrompt`).

`start` decides in this order: no repository or no `reviewHarness` ⇒ `NotHarness`; `priorRunFor(reviewId)` present ⇒ `NotHarness` (re-review stays on the API); `fromFork` not `Optional.of(false)` ⇒ `Refused(harness, "fork_pull_request_not_reviewed_on_harness")`; else dispatch: `Dispatched`, `AlreadyQueued` and `Uncertain` ⇒ `Started(runId, harness)` (an uncertain launch has its queued row; a lost one surfaces as `REVIEW_STUCK`), `Refused(reason)` ⇒ `Refused(harness, reason)`.

`refuseHarness` does what `refuse` (`:582-592`) does — clear the scheduled retry, timeline `refused:harness-review`, status `refused`, note `Harness review refused: <reason>`, `RecordFailure(commit, "harness-review", false)` — plus `setHarnessRefusal(reviewId, harness + ": " + reason)`.

The prompt is exactly: the template's `system` text, a blank line, then this preamble with the values filled in, then `PromptCatalog.REVIEW_CONTRACT`, then the last line. The template's `body` is not used: it frames a pasted diff the agent does not get. (If the operator's editable instructions live in `body` rather than `system`, use the editable part and record a ruling.)

```text
Review pull request #<prId> in <workspace>/<slug>. Your working directory is a checkout of its head commit <commit>.
The pull request's target branch is kept as refs/spire/base. The change under review is:
  git diff $(git merge-base refs/spire/base HEAD) HEAD
Work like a careful senior reviewer:
1. Read the repository's rules first: .codespire, CLAUDE.md, AGENTS.md and the rule files they name.
2. Read every changed hunk, then the whole changed method and its callers.
3. Check four lenses: security; correctness and design; the project's rules; tests and build. Judge tests and build by reading them. Never build, test or install anything.
4. Report a finding only with a file and a line in the head commit, a concrete failure scenario and a fix.
5. Never edit, commit or push.
Write your answer as the whole content of the file named by $SPIRE_ARTIFACT: one JSON object and nothing else, in this shape:
```

Last line: `Do not give a verdict. The verdict is decided from your findings.`

- [ ] **Step 1: Write the failing tests**
  - `HarnessReviewStartTest` (fakes for registry, projection, dispatch, rounds; every method the path reaches overridden):
    - `aRepositoryWithoutAHarnessIsNotAHarnessRun` (no dispatch call).
    - `aReReviewIsNotAHarnessRun` (prior run present ⇒ `NotHarness`, no dispatch call).
    - `aForkIsRefusedAndNeverDispatched` and `anUnknownForkStatusIsRefused`.
    - `aFirstReviewDispatchesARunOfItsRound`: the request's binding is `REVIEW`, the event's commit and pr id, `round = currentRun`, `callSlot = CallRefs.reviewSlot(commit, round)`; the choice carries the repository's harness, model, effort and `subscription == true` for `SUBSCRIPTION`.
    - `aRefusedDispatchIsRefusedWithItsReason` (`subscription_unavailable`).
    - `anUncertainLaunchCountsAsStarted`.
  - `HarnessReviewPromptTest`: `thePromptCarriesTheTargetHeadAndDiffCommand`, `thePromptCarriesTheFindingsContractVerbatim` (contains `PromptCatalog.REVIEW_CONTRACT`), `thePromptNeverAsksForAVerdict` (no `APPROVE`/`REQUEST_CHANGES` tokens; ends with the last line), `thePromptStartsWithTheRepositorysReviewSystemText`.
  - `ResultSagaHarnessReviewTest`: `aStartedHarnessReviewSendsNoGenerateReview`, `aRefusedHarnessReviewIsRefusedWithAttentionReason` (status `refused`, `harness_refusal` = `codex: subscription_unavailable`, no `GenerateReview`), `aNonHarnessReviewSendsGenerateReviewAsToday`.
  - `AttentionHarnessRefusedTest`: `aRefusedHarnessReviewRaisesAttention` (message names the harness and the reason), `aRefusedReviewWithoutAHarnessReasonRaisesNothing`, `anArchivedReviewRaisesNothing`.
- [ ] **Step 2: Push; CI fails on these.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Start a first review on a harness repository as a review run`.

### Task 6: A review run's answer becomes the review's outcome

**Files:**
- Create: `spire-orchestrator/.../review/run/HarnessReviewResults.java` — `@ApplicationScoped implements ReviewRunResults`; replaces `RecordingReviewRunResults` (delete it and its test, keeping its log lines for `RECONCILE` and `FOLLOWUP`, which step 6 and 7 take).
- Modify: `ResultSaga` — `public void acceptHarnessOutcome(IntegrationEvent outcome)`: accepts a `ReviewGenerated` or a `ReviewFailed` (anything else: `IllegalArgumentException`); runs `ifCurrentRun(reviewId, commit, "HarnessReview", …)` around the existing `onReviewGenerated` / `onReviewFailed`; never calls `chargeGeneratedCalls`.
- Test: `HarnessReviewResultsTest`, `ResultSagaHarnessReviewTest` (extend).

**Interfaces:**
- Consumes: `ReviewRunResults.accept(RunResult result, ReviewRunBinding binding)`; `RunReviewArtifact(runId, review, text, overCap, tokenUsage, activeWallSeconds, egress)`; `RunTokenUsage.of(result, model, maxReportedTokens)` as `ReviewRunCharges` uses it (`ReviewRunCharges.java:61`), with the run row's model; `FindingsParser.parse(Completion) -> ReviewResult` (`spire-llm`); `ReviewResult.degraded(String summary, ModelUsage usage)`; `IntegrationEvent.ReviewGenerated(reviewId, prId, commit, result)` (4-argument first-review constructor); `IntegrationEvent.ReviewFailed(reviewId, commit, phase, error, retryable, attempt, credentialRejected)`.
- Produces: `ResultSaga.acceptHarnessOutcome(IntegrationEvent)`.

`accept` for `binding.kind() == REVIEW`:
- `binding.round() != ReviewRuns.currentRun(reviewId)` ⇒ drop, log INFO with run id and both rounds (the ended round's answer is never posted).
- `RunReviewArtifact` not over cap ⇒ `FindingsParser.parse(new Completion(text, usage))` (an unparsable answer is already a degraded result there).
- over cap ⇒ `ReviewResult.degraded("The harness answer was larger than 256 KiB and was not read.", usage)`.
- then `acceptHarnessOutcome(new ReviewGenerated(reviewId, prId, commit, result))`.
- `RunFailed` ⇒ `acceptHarnessOutcome(new ReviewFailed(reviewId, commit, "harness-review", cause, false, 1, cause is CREDENTIAL_REJECTED))` — retry budget 1 (spec, "A refused seat is classified").
- `RunCancelled` ⇒ nothing (a supersede or a close; the review has moved on).

- [ ] **Step 1: Write the failing tests**
  - `aFindingsAnswerIsPostedThroughTheReviewOutcome`: an artifact with one finding JSON ⇒ `acceptHarnessOutcome` gets a `ReviewGenerated` with that finding, path and line.
  - `aProseAnswerIsADegradedReview`: text `I looked and it is fine.` ⇒ `degraded == true`, no findings.
  - `anOverCapAnswerIsADegradedReview` (`overCap=true, text=null`).
  - `aResultOfAnEndedRoundIsDropped` (binding round 1, current run 2 ⇒ no call).
  - `aFailedRunFailsTheReviewWithoutRetry` (`retryable == false`, phase `harness-review`).
  - `aRejectedSeatIsMarkedCredentialRejected`.
  - `aCancelledRunDoesNothing`.
  - `aReconcileResultIsOnlyLogged` (no call; step 6).
  - `ResultSagaHarnessReviewTest.aHarnessOutcomeIsNotChargedAgain`: `recordCharges` never called (fake overrides it and every other method reached), while a `ReviewGenerated` through `handle` in the same fixture does charge (the control).
  - `ResultSagaHarnessReviewTest.aHarnessOutcomeForAnOldCommitIsDropped` (`ifCurrentRun` guard).
  - `ResultSagaHarnessReviewTest.aHarnessOutcomeEmitsPostComments` (the verdict is set by `ReviewVerdictDecision`).
- [ ] **Step 2: Push; CI fails on these.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Post a review run's answer as the review's outcome`.

### Task 7: The review page links its harness run; run detail names review kinds

**Files:**
- Modify: `spire-ui/src/components/ReviewDetail.tsx` — while the review has a run of kind `REVIEW` (read with the existing runs list, `GET /api/runs?reviewId=<id>&kind=REVIEW`, a new `runsForReviewKey(reviewId)` in `api.ts`), show `Harness run` linking to `/runs/<runId>` with its status. No socket: the review page re-reads it when its own review entry changes (as `reviewDetailKey` does).
- Modify: `RunDefinitionCard.tsx:29` — `['Kind', runKindLabel(run.kind)]` (export `runKindLabel` from `Runs.tsx` or move it with `REVIEW_KIND_LABEL` to a small `runKinds.ts`).
- Modify: `RunSpendCard.tsx:10` — a review-kind run shows `Charged under its review` instead of its own cost (use `isChargedUnderItsReview`).
- Modify: `api.ts:1165` and `FactoryRunProjection.java:284` — the `reviewId` doc comments: also set for review runs.
- Test: `ReviewDetail.test.tsx` (or the existing review page test), `RunDefinitionCard` / run detail test.

- [ ] **Step 1: Write the failing tests**
  - `the review page links its harness run`.
  - `the review page shows no run link for an API review`.
  - `run detail labels a review run Review`.
  - `run detail says a review run is charged under its review`.
- [ ] **Step 2: Push; CI fails on these.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** `Link a harness review to its run`.

### Task 8: Docs, then the live proof

**Files:**
- Modify: `docs/DECISIONS.md` — ADR-050 gains "Step 5": first reviews only, re-reviews on the API until step 6, forks refused, no fallback, the prompt's parts, the agent gets no assembled context yet (see below).
- Modify: `docs/UNVERIFIED.md` — the step 4 entry: dispatch is now wired; what the live proof shows, and what stays unproven (`Uncertain` launch recovery, an over-cap answer, `CREDENTIAL_REJECTED` live).
- Modify: `docs/CONTRACT.md`, `docs/DATA-MODEL.md` (V109), `docs/factory/RUN-TOPOLOGY.md` §6B (the init base ref, the artifact cap env).
- Modify: `CLAUDE.md` Status — rewrite the harness reviews bullet (never append).

- [ ] **Step 1: Write the docs; push; CI green; commit** `Record first reviews on a harness`.
- [ ] **Step 2: Rebuild the dev images from the branch:** `./deploy/agent/build-codex.sh` (entrypoint), `./gradlew :spire-publisher:installDist && docker build -t spire-publisher:latest spire-publisher` (clone), the orchestrator and run worker from the branch. These are builds the operator asked for as part of the live test (the "no local builds" rule allows a requested local stack).
- [ ] **Step 3: Live proof on `spire-test`, API key first.** The operator sets Settings → Repositories → spire-test → Review with: Harness, Codex, a model, Pay with an API key. A same-repository TEST pull request is reviewed: the run appears on the Runs screen as `Review`, its egress is `api.openai.com` only, findings and a verdict are posted as a GitHub review, the charge is under the review with `credential_ref`.
- [ ] **Step 4: Then the seat.** Pay with: a Codex subscription. A second TEST pull request: cost 0, tokens recorded, egress `chatgpt.com` only.
- [ ] **Step 5: Refusal.** With no seat signed in (or the seat switched off), a third TEST pull request is `refused`, and the attention bell shows `Harness review refused: codex: subscription_unavailable`. The operator signs the seat back in.
- [ ] **Step 6: Record** `docs/factory/HARNESS-REVIEWS-5-ACCEPTANCE.md` (what was run, run ids, links, egress hosts, charges) and a `docs/HISTORY.md` entry; commit `Record the live proof of first reviews on a harness`.

## Calls made in this plan (the operator may overrule)

1. **The agent gets no assembled context yet** (linked issues, Confluence, code knowledge). It reads the repository and its rules itself. The design wants context in a read-only volume written by init; that mechanism does not exist, and putting context in the prompt sends it through an environment variable, which the design forbids. It moves to step 6, with the re-review's prior findings, which need the same volume.
2. **A fork, and a pull request whose fork status is unknown, are refused,** not reviewed by the API: the operator ruled out fallback.
3. **A failed harness run is not retried automatically** (retry budget 1). The operator re-runs the review from the dashboard.
4. **One "Review with" choice per repository:** an API provider or a harness, never both (V109 CHECK).

## Out of scope (steps 6–8)

Re-reviews and the prior findings, replies, assembled context, fork pull requests and the credential-injecting proxy.

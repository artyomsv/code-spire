# Harness Reviews Step 8: Fork Pull Requests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A repository that reviews with a harness reviews fork pull requests on GitHub and GitLab too (first
reviews, re-reviews and replies), only in a unit whose model credential is held by the model proxy; Bitbucket fork
pull requests stay refused with a named reason.

**Architecture:** An untrusted pull request (a fork, or fork status unknown) is dispatched as a new run command,
`ExecuteUntrustedReviewRun`, with its own wire discriminator, so a run-worker from before this step refuses it
instead of running it as a trusted review. It carries the pull request's own ref (`refs/pull/<n>/head`,
`refs/merge-requests/<iid>/head`) and clones onto a fixed local branch, `spire/review-<prId>`. The run worker hands
the ref to init (`SPIRE_FETCH_REF`) and refuses the unit when the model proxy would not hold the credential. Init
(`CloneMain` → `WorkspaceClone`) fetches the ref after the clone, retrying briefly while the forge catches up. A
refusal comes back to the review as a named `Refused` with an attention row, and to a reply as `FollowUpRefused`.
A trusted pull request's command, clone and run are unchanged.

**Tech Stack:** Java 25, Quarkus 3.38.3, JGit, Kafka wire records (`spire-contract`), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-30-harness-reviews-design.md` (decision 3 "Fork pull requests are
reviewed", "The untrusted-code risk", "Final design review" → *Fork clone*, delivery order item 8, "After step 2").

## Global Constraints

- An untrusted run executes only when the unit has a model proxy (`RunUnitSpec.modelProxy() != null`). Otherwise
  it fails `BAD_COMMAND` before any container starts, detail `fork_requires_credential_proxy`; the review is
  `Refused` with that reason and a `REVIEW_HARNESS_REFUSED` attention row.
- Untrusted = `from_fork` true, NULL, or no row. A deleted fork reads as `false` at ingress today; it stays a
  trusted command with no pull ref and fails init as it does today (its head is on no branch).
- Bitbucket Cloud and Data Center: an untrusted pull request is `Refused`, reason `fork_pull_request_has_no_pull_ref`
  (Data Center has `refs/pull-requests/<n>/from`, deliberately not used in this step).
- The ref and the allowlist come from Code Spire, never from the reviewed repository. A ref must match
  `^refs/(pull/[1-9][0-9]*/head|merge-requests/[1-9][0-9]*/head)$`; the run worker and init each refuse anything else.
- The fork's branch name never reaches the run unit.
- `/fix` on a fork stays refused (`FixTargets.whyNotPushable`, unchanged).
- A Claude subscription is still refused at every door. Never run a Claude Code subscription test.
- No local builds or tests: CI only. Commits ≤72 chars, imperative, no AI attribution, `Part of #391`.
- Upgrade order (docs/HISTORY.md Upgrading): rebuild `spire-publisher:latest` and roll the run worker before the
  orchestrator; an old publisher image ignores `SPIRE_FETCH_REF` and every fork fails `CLONE_FAILED`.

## Review Focus

1. The pull ref lags the webhook: init fetches, the commit is absent, it refetches (3 tries, 2 s apart), then fails
   `CLONE_FAILED` naming the commit and the ref.
2. Proxy mode `OFF`, egress `OFF`, or a harness arm with no route, with a fork: refused, never run with the real
   credential in the agent (Codex silently falls back to direct for trusted units).
3. A re-review of a fork: the prior head is on no fetched ref; the agent is told to judge against the current code
   (existing prompt text), and nothing crashes.
4. A fork whose branch is named like the target (`main`) or like an internal ref: the unit never sees that name.
5. An old run worker receives the new command: it refuses the unknown type and never runs it.

---

### Task 1: The untrusted review run command

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/review/PullRefs.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/RunCommand.java` (new record beside
  `ExecuteReviewRun`, registered in the `@JsonSubTypes` list)
- Test: `spire-contract/src/test/java/dev/codespire/contract/review/PullRefsTest.java`,
  `spire-contract/src/test/java/dev/codespire/contract/command/ExecuteUntrustedReviewRunTest.java`,
  `ContractSchemaSnapshotTest` snapshot; the run worker's existing unknown-type test (`DeserializersNeverThrowTest`)

**Interfaces:**
- Produces: `PullRefs.forType(ScmType, long prId) -> Optional<String>` (GITHUB, GITLAB only; `prId < 1` throws);
  `PullRefs.isValid(String) -> boolean`; `PullRefs.PATTERN` (the regex string);
  `PullRefs.localBranch(long prId) -> "spire/review-<prId>"`.
  `RunCommand.ExecuteUntrustedReviewRun(String runId, ExecuteRun execution, ReviewRunBinding review,
  HarnessReviewInputs inputs, String pullRef)`: the same checks as `ExecuteReviewRun`, plus `pullRef` valid and
  `execution.branch()` equal to `PullRefs.localBranch(review.prId())`. `asReview() -> ExecuteReviewRun` for the
  shared unit build.

- [ ] **Step 1: Write the failing tests**: `githubRefNamesThePull`, `gitlabRefNamesTheMergeRequest`,
  `bitbucketHasNoRef` (Cloud and DC), `aRefOutsideTheTwoShapesIsInvalid` (`refs/heads/main`, `refs/pull/0/head`,
  `refs/pull/1/head;rm`, `refs/pull/1/head\n`, `refs/pull/01/head`), `theCommandRefusesAnInvalidRef`,
  `theCommandRefusesTheForkBranchName`, `theCommandRoundTripsUnderItsOwnDiscriminator`,
  `aWorkerWithoutTheTypeRefusesIt` (a mapper whose `RunCommand` lacks the subtype fails to read it; reuse the
  pattern of the `ExecuteWorkDraftRun` discriminator test if present).
- [ ] **Step 2: Push; CI `fast tests` fails on the missing types.**
- [ ] **Step 3: Implement.** Update the schema snapshot.
- [ ] **Step 4: Push; CI `fast tests` passes.**
- [ ] **Step 5: Commit** `Add an untrusted review run command with its pull ref`.

### Task 2: Init fetches the pull ref

**Files:**
- Modify: `spire-workspace/src/main/java/dev/codespire/workspace/WorkspaceClone.java:72-121`
- Modify: `spire-publisher/src/main/java/dev/codespire/publisher/CloneMain.java:59-70`
- Test: `spire-workspace/src/test/java/dev/codespire/workspace/WorkspaceCloneTest.java`,
  `spire-publisher/src/test/java/dev/codespire/publisher/CloneMainTest.java`

**Interfaces:**
- Ruling (made): `spire-workspace` and `spire-publisher` do not depend on `spire-contract`; `WorkspaceClone` keeps its
  own copy of the pattern. Task 3's run worker test asserts the two patterns are equal strings.
- Produces: `WorkspaceClone.FETCH_REF_PATTERN`; `populate(…, baseBranch, String fetchRef)`; the 8-argument form
  passes `null`. With a ref: refuse an invalid one before any network call; after the clone, `git.fetch()` with
  refspec `+<fetchRef>:refs/spire-head/pull` and the same credential; if `baseCommit` is still absent, refetch up to
  3 tries in total, 2 s apart (the sleep injectable for tests), then fail naming the commit and the ref. Init env
  `SPIRE_FETCH_REF` (blank = none).

- [ ] **Step 1: Write the failing tests** (local bare origin, as the existing tests do):
  `aCommitOnlyOnThePullRefIsCheckedOut` (without the ref it fails "is not reachable"),
  `aLaggingRefIsRefetched` (the ref moves to the commit after the first fetch), `aRefThatNeverHoldsTheCommitFails`
  (message names commit and ref; 3 fetches), `anInvalidRefIsRefusedBeforeAnyFetch`, `theBaseIsStillKeptWithAPullRef`,
  `aGitattributesFilterASubmoduleAndASymlinkRunNothing` (the fork commit declares a filter driver with a marker
  command, a `.gitmodules` entry and a symlink out of the workspace: no marker file, no submodule clone, the symlink
  is a link and nothing outside the workspace is written). `CloneMainTest`: `theFetchRefReachesPopulate`,
  `aBadFetchRefIsCloneFailed`.
- [ ] **Step 2: Push; CI fails on the missing overload.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI `fast tests` passes.**
- [ ] **Step 5: Commit** `Fetch the pull request ref when init clones`.

### Task 3: The run worker builds untrusted units and refuses unproxied ones

**Files:**
- Modify: `spire-run-worker/src/main/java/dev/codespire/runworker/RunUnitBuilder.java:156-165`,
  `RunLauncher.java:181` (route the new command), the review lane consumer that accepts `ExecuteReviewRun`
  (accept the new type there too)
- Test: `RunUnitBuilderReviewTest`, `RunUnitBuilderModelProxyTest`, `RunLauncherModelProxyTest`,
  `RunLauncherClaudeCodeTest`, `ReviewLaneDeliveryTest`

**Interfaces:**
- Consumes: `ExecuteUntrustedReviewRun`, `PullRefs` (Task 1), `WorkspaceClone.FETCH_REF_PATTERN` (Task 2, test only).
- Produces: `RunUnitBuilder.FETCH_REF = "SPIRE_FETCH_REF"`;
  `RunUnitBuilder.FORK_PROXY_REQUIRED = "fork_requires_credential_proxy"`;
  `buildUntrustedReview(ExecuteUntrustedReviewRun, HarnessAdapter) -> RunUnitSpec`: `buildReview(asReview())`, then
  throw `IllegalStateException(FORK_PROXY_REQUIRED)` when `modelProxy()` is null, else add `FETCH_REF` to init
  (re-checking `PullRefs.isValid`).

- [ ] **Step 1: Write the failing tests**: `thePullRefReachesInit`, `aTrustedReviewSetsNoFetchRef`,
  `anUntrustedReviewIsProxiedOnCodex` (key and seat: placeholders only in the agent),
  `anUntrustedReviewIsProxiedOnClaudeCode`, `anUntrustedReconcileAndFollowupAreProxied`,
  `anUntrustedReviewIsProxiedWithEgressInReportMode`, `anUntrustedReviewIsRefusedWhenTheModeIsOff`,
  `anUntrustedReviewIsRefusedWhenEgressIsOff`, `anUntrustedReviewIsRefusedOnAnArmWithNoRoute`,
  `theRefusalCarriesNoCredentialText`, `bothRefPatternsAgree`, `theLaneDeliversTheUntrustedCommand`; in
  `RunLauncherModelProxyTest`: `anUnproxiedForkFailsBadCommandBeforeAnyContainer` (runtime never called; detail is
  the constant).
- [ ] **Step 2: Push; CI `service tests + packaging` fails.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI passes.** Mutation-check: delete the refusal → only the refusal tests fail.
- [ ] **Step 5: Commit** `Refuse a fork review the model proxy does not hold`.

### Task 4: The orchestrator dispatches forks and names their refusals

**Files:**
- Modify: `spire-orchestrator/.../review/run/HarnessReviewStart.java` (javadoc `:22`, `:34-35`, check `:120-124`),
  `HarnessReplyStart.java:27,86-90`, `ReviewRunDispatch.java:80-148` (`Target`, `command()`),
  `HarnessReviewResults.java:186-191`, `HarnessReplyResults` (failure mapping)
- Test: `HarnessReviewStartTest`, `HarnessReplyStartTest`, `ReviewRunDispatchTest`, `ConversationHarnessReplyTest`,
  `HarnessReviewResultsTest`, `HarnessReplyResultsTest` (or their existing equivalents), `FixTargetsTest`

**Interfaces:**
- Consumes: `PullRefs`, `ExecuteUntrustedReviewRun` (Task 1); `FORK_PROXY_REQUIRED` text (Task 3, duplicated as a
  constant here: the orchestrator does not depend on the run worker).
- Produces: `HarnessReviewStart.FORK_REFUSED = "fork_pull_request_has_no_pull_ref"`, refused only when the pull
  request is untrusted and `PullRefs.forType` is empty. `ReviewRunDispatch` sends `ExecuteUntrustedReviewRun` (branch
  `PullRefs.localBranch(prId)`, ref from `PullRefs.forType`) when untrusted, the unchanged `ExecuteReviewRun`
  otherwise; `ReviewRunDispatch.Target` gains `boolean untrusted`. A run failure with cause `BAD_COMMAND` and detail
  `fork_requires_credential_proxy` becomes `Refused` (review: attention row) or `FollowUpRefused` (reply).

- [ ] **Step 1: Rewrite the four refusal tests and add**: `aGitHubForkIsPrepared`, `aGitLabForkIsPrepared`,
  `anUnknownForkStatusOnGitHubIsPrepared`, `aBitbucketForkIsRefusedNamingTheMissingRef`,
  `aBitbucketDataCenterForkIsRefused`, `aBitbucketUnknownForkStatusIsRefused`, the same for replies;
  `ReviewRunDispatchTest`: `aForkIsSentUntrustedWithItsRefAndFixedBranch`, `aSameRepositoryPullRequestIsSentAsBefore`,
  `anUnknownForkStatusIsSentUntrusted`, `aGitLabForkNamesTheMergeRequestRef`, `aForkBranchNamedMainNeverReachesTheCommand`;
  results: `anUnproxiedForkIsARefusalWithAttention`, `anUnproxiedForkReplyIsFollowUpRefused`,
  `anotherBadCommandStaysAFailure`; `FixTargetsTest`: `aForkIsStillNotPushable`.
- [ ] **Step 2: Push; CI fails.**
- [ ] **Step 3: Implement.** Replace the old literal in `ConversationHarnessReplyTest.java:89-95`.
- [ ] **Step 4: Push; CI passes.** Mutation-check the Bitbucket refusal, the `untrusted` derivation and the
  failure-to-refusal mapping.
- [ ] **Step 5: Commit** `Review fork pull requests on GitHub and GitLab harnesses`.

### Task 4b: An untrusted pull request loads no linked context

Operator ruling (2026-11-13): a fork author writes the PR title, branch and description, the only texts links are
read from (`DiffWorker.java:63`), so they would choose which private issues and documents are loaded, and a
prompt-injected review could post them in public. For an untrusted pull request (the same derivation as Task 4), on
the API path and the harness path alike, `GatherContext` carries no references: the review keeps the PR text, the
target-branch rules and the code symbols. Links chosen in comments by people with write access are the next PR (8b),
not this one.

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java:234-239`
- Test: the existing `ResultSaga` DiffFetched test class (find it by `GatherContext` in
  `spire-orchestrator/src/test`)

**Interfaces:**
- Consumes: `FixTargets.forReview(reviewId)` → `PushTarget.fromFork()` (already used by `HarnessReviewStart`).
- Produces: `GatherContext.references()` is `Set.of()` when the pull request is untrusted; the review's event log
  gets one line, `context links skipped: untrusted pull request`.

- [ ] **Step 1: Write the failing tests**: `aForkGathersNoLinks`, `anUnknownForkStatusGathersNoLinks`,
  `aSameRepositoryPullRequestKeepsItsLinks`, `aForkKeepsItsRulesAndCodeReferences`.
- [ ] **Step 2: Push; CI fails.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; CI passes.** Mutation-check the untrusted branch.
- [ ] **Step 5: Commit** `Load no linked context for an untrusted pull request`.

### Task 5: Docs and live acceptance

**Files:**
- Modify: `docs/DECISIONS.md` (ADR-050 "Step 8"; the old reason literal at `:91`), `docs/factory/RUN-TOPOLOGY.md`
  §6B, `docs/SECURITY.md` (untrusted review runs), `docs/UNVERIFIED.md` (a step 8 entry; close the fork lines of the
  step 5 entry), `docs/HISTORY.md` (with the Upgrading order), `CLAUDE.md` Status.
- Create: `docs/factory/HARNESS-REVIEWS-8-ACCEPTANCE.md`

- [ ] **Step 1: Docs for what Tasks 1–4 built**, with what is proven against fakes only (Bitbucket, a lagging ref, a
  force-pushed fork, a fork re-review's unreachable prior head unless the live run reaches it, a deleted fork).
- [ ] **Step 2: Live on `spire-test`** (the operator opens the fork pull requests from a second account or an
  organisation fork): a fork PR from the fork's `main` reviewed on a Codex seat, on a Codex API key and on Claude Code
  (`claude-sonnet-5-5`, API key); a canary file in the fork asking the agent to print its environment and post it
  (the posted review holds only placeholders); a fork PR whose description links a private issue (the context
  shows no linked item and the event log says why); a push to the fork re-reviewed as one `RECONCILE` run; a reply on a
  fork finding answered; with `SPIRE_RUN_CREDENTIAL_PROXY_MODE=OFF`, a real fork PR refused
  `fork_requires_credential_proxy` with its attention row. Capture the model proxy log for every run (#98's was not).
- [ ] **Step 3: Commit** `Record the fork pull request acceptance`.

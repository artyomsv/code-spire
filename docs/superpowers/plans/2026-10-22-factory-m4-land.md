# Factory M4 slice 2 — land: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan slice by slice. Steps use checkbox (`- [ ]`) syntax.

**Goal:** `land: approve` becomes real on GitHub. An approved land merges the delivered pull request at the
approved head with the repository's merge method, deletes the item branch, applies the ticket rule, and
completes the item. A refusal caused by code loops through build, verify, deliver, review and a new land
gate. Any other refusal, or a spent budget, hands the item to the operator with GitHub's reason. A hand
merge or close on GitHub is recognised. A landed item cannot be readmitted.

**Spec:** [`docs/superpowers/specs/2026-10-21-factory-m4-land-design.md`](../specs/2026-10-21-factory-m4-land-design.md).
Section 10 (review amendments, "A1".."A13" below) overrides the sections it names. Section 6 (operator
decisions) is fixed. §n refers to the spec.

**Tech stack:** Java 25, Quarkus 3.38.3, PostgreSQL/Flyway, JGit, WireMock, React + vitest.

## Global Constraints

- **No local builds, tests or linters.** Never run `gradlew`, `npm test`, `npx vitest|tsc|eslint` on the host.
  CI on GitHub is the only verifier.
- **CI runs on pull requests only** (`.github/workflows/ci.yml`: `on: pull_request`). Slice 0 opens one draft
  PR from `feat/m4-land` to `master`. Every later "read CI" step:
  1. `git push`, note the short sha (`git rev-parse --short=8 HEAD`).
  2. `gh run list --branch feat/m4-land --limit 10 --json databaseId,headSha,name --jq '.[] | select(.headSha|startswith("<sha8>")) | "\(.databaseId) \(.name)"'`
     (no `--arg` with jq: it fails on this host).
  3. `gh run watch <id> --exit-status` for the run named `ci` (jobs `dashboard`, `fast tests`,
     `service tests + packaging`).
  4. On red: `gh run view <id> --log-failed`, and read only the failure.
- **A new push cancels an in-progress `ci` run on the same branch** (`concurrency: cancel-in-progress`). Never
  push while you still need the evidence of a running run.
- **TDD, per slice:** write the failing tests first, commit and push them alone (RED). Read the CI log and
  confirm each fails for the reason the slice names, not on compilation of an unrelated file. Where a new type
  must exist for a test to compile, the RED commit adds the type with a body that throws
  `UnsupportedOperationException("RED")` or returns the wrong constant, so the failure is an assertion. Then
  implement, push (GREEN), and confirm all three `ci` jobs succeed.
- **Commits:** imperative subject of 72 characters or fewer; a body for non-trivial changes; the body ends with
  `Part of #115`. No closing keyword (`close/closes/fix/fixes/resolve…`) before an issue number. No AI, agent,
  model or vendor names, no "generated", no `Co-Authored-By` trailer.
- **Git safety:** never bare `git stash`; never force-push `feat/m4-land`; never merge anything.
- **Line endings:** count CR bytes before editing (`tr -cd '\r' < f | wc -c`). For a CRLF file, edit through a
  small node script written to the scratchpad that reads, replaces and writes with the file's own eol. Never
  convert a file.
- **Java:** 4-space indent, explicit types in new code (no `var`). When editing the dense `work/` files, keep
  their compact one-line style and comment density. `spire-contract` stays framework-free (Jackson annotations
  only; `PureModulesAreFrameworkFreeTest`).
- **TypeScript:** 2-space indent, `interface` for object shapes.
- **Test data:** `TEST-` prefixed identifiers only, hosts `*.example.test`, numeric GitHub ids in the existing
  fixtures' `9000xx` style. No real names, prices or tickets.
- **Domain events** are appended only through a `WorkItemLifecycle` decision under
  `WorkItemTransitions.lockItem` (ADR-010). Forge and tracker writes are durable rows drained by sweeps; an
  uncertain write is recovered by reading, never re-sent blindly (ADR-043).
- **Wire records:** a new component goes in through a wither next to the record; every shorter constructor
  passes the default explicitly. `ContractSchemaSnapshotTest` does not recurse, so every new nested wire type
  is added to its nested list (`ContractSchemaSnapshotTest.java:153`) and to `contract-schema.txt`.
- **Saga test fakes override every method a new path reaches**, each with a comment saying why (CLAUDE.md
  gotcha: an un-overridden method opens a real database).
- **New reasons:** every reason a slice introduces gets its `workReasons.ts` sentence in the same slice.
  No new `workflowStatus` is introduced (A12), so `api.ts`'s status union, `STATUS_LABEL`, `miniPipeline` and
  `needsAttention` do not change; if a builder finds they must, change all four together.
- **Migrations:** `V90__land_settings.sql` (slice 2), `V91__work_land_effect.sql` (slice 4),
  `V92__post_land_tracker_effect.sql` (slice 5). If a number is taken on master when you start, take the next
  free one and keep the name.
- **Exact strings.**
  - Event milestones: `WORK_ITEM_LANDED`, `LAND_HANDED`, `LAND_FIX_REQUESTED`, `PULL_REQUEST_CLOSED`,
    `LAND_RETRY_REQUESTED`, `LAND_STOPPED`.
  - Item reasons: `landed` (on `completed`), `pr_closed_by_human` (on `stopped`), `land_stopped_by_operator`
    (on `stopped`, slice 8), and the hand-off reasons (on `land / awaiting_input`): `land_head_moved`,
    `land_checks_timeout`, `land_checks_not_code`, `land_blocked`, `land_pr_draft`, `land_merge_refused`,
    `land_merge_method_not_allowed`, `land_fix_attempts_exhausted`, `land_item_limit_reached`,
    `land_fix_review_blocked`, `land_fix_run_failed` (slice 7b), and (slices 4–7a only, removed from the set
    in slice 7b) `land_checks_failed`, `land_conflict`, `land_branch_behind`.
  - Publisher failure: `BASE_TIP_UNAVAILABLE` (slice 7a), beside the existing failure codes.
  - Fix reasons (on `LAND_FIX_REQUESTED`): `land_checks_failed`, `land_conflict` (counted),
    `land_branch_behind` (not counted).
  - Command refusals: `readmission_landed`, `readmission_land_pending`, `retry_land_required`,
    `retry_land_unavailable`, `land_head_moved`.
  - Effect notes: `branch_delete_failed`, `ticket_close_unsupported`, `ticket_rule_changed`.
- **Numbers (spec "my call"):** checks re-read every 60 s, `waiting_checks` gives up after 6 h; branch delete
  tried 3 times; check-summary cap 20 failed entries, 2000 chars per summary, 5 annotations of 300 chars;
  fix attempts 0..10, default 3.

**Mutation checks (no local runs).** Each slice lists its guard lines. For each:
1. On a scratch branch `mut/m4-land-s<N>-<k>` off the slice's GREEN commit, commit one mutant per guard.
2. Push it and open a draft PR against `feat/m4-land` titled `TEST mutant s<N>-<k> (do not merge)`.
3. Read that PR's `ci` run. Exactly the named test must fail (others may not).
4. Close the PR, delete the branch.
5. Record line, mutant, failing test and run URL in `.claude/reviews/global/factory-m4-land-mutations.md`.

Batch one slice's mutants as one branch with one commit per mutant when CI budget matters, and read each
commit's run (push them one at a time, waiting for each, because a push cancels the previous run).

## Review Focus

Inputs the spec implies but no slice's main path reaches. Each has its test in the owning slice.

1. **A repository with no checks and no statuses.** The combined status is `pending` with zero entries; the
   reduction must say PASSED (A4). Slice 1: `noChecksAndNoStatusesIsPassed`.
2. **Check runs on a second page.** A failed run on page 2 must not be read as PASSED. Slice 1:
   `aFailureOnTheSecondCheckRunPageFails`.
3. **A merge whose response is lost.** Recovery reads the pull request and never sends the merge again while
   it is unknown; a second merge only after a read proved it open at `approved_head`. Slice 4:
   `anUncertainMergeIsRecoveredByReadingNeverResent`.
4. **The factory's own merge webhook arrives before the effect records the merge.** Exactly one
   `WORK_ITEM_LANDED`, merger `factory`. Slice 6: `theFactorysOwnMergeWebhookIsLeftToTheEffect`.
5. **A hand merge while the fix run is building.** The item completes with the human as merger; the running
   fix is held and cancelled; its late build result is accounted, not failed. Slice 7b (an item can only be in
   `build` with a pull request once the land fix exists; slice 6 cannot reach this state):
   `aHandMergeDuringAFixHoldsTheRunAndAccountsItsLateResult`.
6. **A ticket effect drained after the item completed, and a stale phase row in the same batch.** Neither
   stalls the outbox (A1). Slice 5: `aPostLandCommentIsSentAfterCompletion`,
   `anUnknownPhaseRowDoesNotStallTheBatch`.
7. **Retry land after someone pushed to the PR.** Refused `land_head_moved`; no gate opens on an unreviewed head
   (A3). Slice 8: `retryLandRefusesAMovedPullRequestHead`.
8. **A verify Retry build inside a land fix.** Still on the item branch, still gate-free for build/deliver, PR
   kept, starts from the forge at `approved_head`, not from a checkpoint. Slice 7b:
   `aVerifyRetryInsideALandFixKeepsThePullRequestAndTheBranch`.
9. **A rotated FACTORY account pushes the fix.** Not takeover (A10). Slice 6:
   `aRotatedFactoryPushIsNotTakeover`.
10. **`fix_attempts = 0`.** A failed check hands the item `land_fix_attempts_exhausted`; `behind` still
    fixes (not counted). Slice 7b: `zeroAttemptsHandsAFailedCheckButStillMergesBehindBase`.
11. **The repository forbids the saved merge method.** Handed `land_merge_method_not_allowed` in `checking`,
    before any merge call (A5). Slice 4: `aForbiddenMethodIsHandedBeforeAnyMergeCall`.
12. **Branch delete returns 422 while the ref still exists.** Re-read; present → `branch_delete_failed`, not
    done (A13). Slice 1: `a422WithTheRefStillPresentIsNotDeleted`.
13. **A human takes over while the merge is uncertain.** `HUMAN_TAKEOVER` refuses pending effects today. A land
    row already past the merge call (`uncertain`, `merged`, `cleaning`) must keep reading and record what GitHub
    did, or a real merge is never recorded. Slice 4: `aTakeoverDuringAnUncertainMergeStillRecordsTheMerge`.
14. **A base merge brings a protected path from the base.** `PushGate` judges the tip tree against the run's
    base commit (`PublishRepo.changesSince`), so merging `origin/<base>` into the branch shows every base change
    since divergence — a workflow file edited on `master` would refuse the fix's push. Paths whose tip content
    equals the merged base tip are not the agent's; an agent edit to a protected path is still refused.
    Slice 7a: `aBaseMergeDoesNotBlameTheAgentForTheBasesProtectedPaths`, `anAgentEditInsideABaseMergeIsStillRefused`.
15. **Readmit while the fix loop waits for a person.** Inside a land fix the item can stop at `build /
    awaiting_input` (an assembly refusal such as missing pricing). Today `canReadmit` offers Readmit on every
    `awaiting_input`, and a readmit there opens a second pull request beside the open one. Slice 7b:
    `readmitIsRefusedWhileALandFixIsInProgress`.
16. **The fix run itself fails** (`PHASE_FAILED` at build). Today the item ends `failed` and Readmit rebuilds
    the delivered work beside the open pull request. Slice 7b hands it `land_fix_run_failed` instead:
    `aFailedFixRunIsHandedNotFailed`.
17. **A handed item the operator wants to end.** §7.6 offers Retry land **or Stop**, but a handed item has no
    open gate, so the gate panel's Stop does not exist there. Slice 8: `stopLandEndsAHandedItemWithANote`.

## Spec coverage

| Spec item | Slice → proving test |
|---|---|
| Goal 1, §7.3 merge at approved head, branch delete, complete | 4 → `anApprovedLandMergesDeletesAndCompletes`, `theMergeIsPinnedToTheApprovedHead` |
| Goal 2, §7.1 settings | 2 → `LandSettingsTest`, `LandStep.test` |
| Goal 3, §7.5 fix loop, §6 "after an agent fix, wait for me" | 7a, 7b → `aFailedCheckRunsOneFixAndOpensANewLandGate` |
| Goal 4, §7.6 hand-off, Retry land or Stop | 4, 7b, 8 → `blockedWithPassedChecksIsHanded`, `theSecondFailureWithOneAttemptIsExhausted`, `retryLandOpensAFreshGateThenLandsAfterApproval`, `stopLandEndsAHandedItemWithANote` |
| Goal 5, §7.8 hand merge / close | 6, 7b → `aHandMergeCompletesWithTheHumanAsMerger`, `aHandMergeDuringAFixHoldsTheRunAndAccountsItsLateResult` |
| Goal 6, §7.9 no readmit after land | 3, 7b → `aLandedItemCannotBeReadmitted`, `readmitIsRefusedWhileALandFixIsInProgress` |
| §6 merge method / ticket rule / branch delete not a setting / attempts default 3 | 2, 4, 5 → `noRowReadsTheDefaults`, `theCloseRuleClosesAfterTheComment` |
| §6 attempt cost (seat free, key priced) | 7b → `aLandFixRunGoesThroughTheBuildsSpendChecks` |
| §6 reviewer blocks a fix → operator | 7b → `aBlockedReviewOfAFixIsHanded` |
| §6 build/deliver gates skipped, verify kept | 7b → `theLandFixFlagAloneSkipsTheBuildGate`/`…DeliverGate`, `theLandFixFlagNeverSkipsVerifyOrLand` |
| Non-goals: `auto_if_green`, GitLab/Bitbucket | 4 → `autoIfGreenStaysUnavailable`, `landStaysUnavailableOffGitHub` |
| Anti-goals: blind retry, racing a human, foreign branch delete | 4, 6, 1 → `anUncertainMergeIsRecoveredByReadingNeverResent`, `aHumanPushIsStillTakeover`, `deleteOnlyAtTheExpectedSha` |
| A1 … A13 | A1 → 5; A2 → 6; A3 → 8; A4 → 1, 4; A5 → 1, 4; A6 → 7b; A7 → 7b; A8 → 3, 7b; A9 → 7b; A10 → 6; A11 → 2, 4; A12 → 3; A13 → 1 |

---

### Slice 0: draft PR

**Files:** none.

- [ ] `git fetch origin && git status` — on `feat/m4-land`, clean, up to date with origin.
- [ ] Open the draft PR (CI only runs on PRs):
  ```bash
  gh pr create --draft --base master --head feat/m4-land \
    --title "Land approved factory items on GitHub" \
    --body "M4 slice 2 (land). Spec: docs/superpowers/specs/2026-10-21-factory-m4-land-design.md. Plan: docs/superpowers/plans/2026-10-22-factory-m4-land.md.

  Part of #115"
  ```
  Do not use `--fill`. Do not link the issue in the Development panel. Record the PR number.
- [ ] Read the first `ci` run for the plan commit: it must be green before slice 1 starts (baseline).

---

### Slice 1: `PullRequestLander` port and the GitHub adapter

**Goal:** a forge port that reads a pull request's land state, reads the allowed merge methods, merges at an
exact head and deletes a branch after reading it at an exact sha. GitHub implements it; other forges do not. Nothing
calls it yet.

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/port/PullRequestLander.java`
- Create: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubPullRequestLander.java`
- Create: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubCheckReduction.java` (pure)
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubClient.java` (add `delete(String path)`,
  204-aware; non-2xx raises `GitHubApiException(status,…)`, I/O stays `UncheckedIOException`)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderClients.java`
  (`pullRequestLander(ScmProvider)`: GitHub → adapter; others → `new PullRequestLander() {}`; FACTORY role
  asserted like `pullRequestSink`)
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubPullRequestLanderTest.java` (new, WireMock,
  pattern of `GitHubPullRequestApprovalSourceTest`)
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubCheckReductionTest.java` (new, pure)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderClientsLanderTest.java` (new, plain)

**Interfaces — Produces:**
```java
// dev.codespire.contract.port
public interface PullRequestLander {
    enum MergeMethod { MERGE, SQUASH, REBASE }
    enum Checks { PASSED, PENDING, FAILED, NOT_CODE }
    record FailedCheck(String name, String conclusion, String title, String summary, List<String> annotations) {}
    record CheckSummary(Checks state, int done, int total, List<FailedCheck> failed) {}   // capped
    record LandState(String state, boolean merged, String mergeCommitSha, String mergedBy, String headSha,
                     String headBranch, String baseBranch, String baseSha, Boolean mergeable, String mergeableState,
                     CheckSummary checks) {}   // baseSha = pull_request.base.sha; slice 7a's base-merge gate needs it
    record AllowedMethods(boolean checked, Set<MergeMethod> allowed) {}
    sealed interface MergeOutcome {
        record Merged(String sha) implements MergeOutcome {}
        record HeadConflict(String message) implements MergeOutcome {}          // 409: caller re-reads (A5)
        record Refused(int status, String message) implements MergeOutcome {}   // 403/404/405/422
        record Uncertain(String cause) implements MergeOutcome {}               // I/O only (A5)
    }
    enum BranchDeletion { DELETED, ABSENT, STILL_PRESENT, MOVED }
    default boolean available() { return false; }
    default LandState read(RepoRef repo, long pullRequest) { throw new UnsupportedOperationException("land unavailable"); }
    default AllowedMethods allowedMethods(RepoRef repo) { throw new UnsupportedOperationException("land unavailable"); }
    default MergeOutcome merge(RepoRef repo, long pullRequest, String expectedHead, MergeMethod method) { throw …; }
    default BranchDeletion deleteBranch(RepoRef repo, String branch, String expectedSha) { throw …; }
}
```
`mergedBy` is `pull_request.merged_by.id` as a string (A2), never `sender`. `mergeable_state: unknown` is passed
through; the effect treats it like `mergeable == null` (A4).

**Check reduction (A4), `GitHubCheckReduction.reduce(JsonNode statuses, List<JsonNode> checkRuns)`:** from the
`statuses[]` list of `GET /commits/{sha}/status` and every page of `GET /commits/{sha}/check-runs?per_page=100&page=n`
(stop when a page is short; a full last page at the page cap of 10 is NOT_CODE "history incomplete", mirroring
the approval source). Never from the combined `state`. Per entry: check-run conclusion `success|neutral|skipped`
PASSED; `failure` FAILED; status `queued|in_progress` (conclusion null) PENDING; `cancelled|timed_out|action_required|stale`,
any unknown conclusion, NOT_CODE. Commit status `success` PASSED, `failure` FAILED, `pending` PENDING, `error`
NOT_CODE (builder's call: an `error` status is infrastructure). Precedence NOT_CODE > FAILED > PENDING > PASSED.
Empty → PASSED.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `GitHubCheckReductionTest.noChecksAndNoStatusesIsPassed` | empty lists, combined `state: pending` in the fixture → PASSED, 0/0 | reduction reads combined `state` |
| `…aFailedCheckRunIsFailedWithItsSummary` | one `failure` run → FAILED, entry carries name, conclusion, `output.title`, `output.summary` | failure mapped to anything else; summary dropped |
| `…aPendingStatusIsPending` | status `pending`, runs all success → PENDING, done/total = 1/2 | pending ignored |
| `…cancelledTimedOutActionRequiredAndStaleAreNotCode` | each of the four (parameterised) → NOT_CODE with the name | any one mapped to FAILED or PASSED |
| `…anUnknownConclusionNeverPasses` | conclusion `TEST-new` → NOT_CODE | default branch returns PASSED |
| `…notCodeOutranksFailedOutranksPending` | mixed sets → precedence as above | precedence reordered |
| `…theSummaryIsCapped` | 30 failures with 5000-char summaries → 20 entries, each summary ≤ 2000, ≤ 5 annotations | cap removed |
| `GitHubPullRequestLanderTest.readMapsStateMergeableAndMergedBy` | `GET /pulls/42` fields map (including `base.sha`); `mergedBy` = `merged_by.id` not `sender` | field mis-mapped |
| `…annotationsAreReadOnlyForFailedRuns` | `GET /check-runs/{id}/annotations` requested for the failed run only, at most 5 kept; none for passed runs | annotations fetched for every run, or never |
| `…aFailureOnTheSecondCheckRunPageFails` | page 1 = 100 success, page 2 = 1 failure → FAILED; both pages requested | only page 1 read |
| `…allowedMethodsReadsTheRepositoryFlags` | `allow_merge_commit:false, allow_squash_merge:true, allow_rebase_merge:false` → `{SQUASH}`, checked | flags ignored |
| `…mergeSendsTheShaAndMethod` | `PUT /pulls/42/merge` body has `sha` and `merge_method:"squash"`; 200 → `Merged(sha)` | sha omitted |
| `…eachHttpStatusIsDefinite` | 405, 422, 403, 404 → `Refused(status,message)`; 409 → `HeadConflict` | any status → `Uncertain` |
| `…onlyAnIoFailureIsUncertain` | WireMock `CONNECTION_RESET_BY_PEER` and malformed body → `Uncertain` | I/O mapped to Refused |
| `…deleteOnlyAtTheExpectedSha` | ref at other sha → `MOVED`, no `DELETE` sent | delete sent without read |
| `…a204IsDeleted` | ref at sha, `DELETE` 204 → `DELETED` | |
| `…a404Or422IsReReadBeforeCallingItGone` | `DELETE` 422 then ref `GET` 404 → `ABSENT` | 422 returned as gone without re-read |
| `…a422WithTheRefStillPresentIsNotDeleted` | `DELETE` 422 then ref `GET` 200 → `STILL_PRESENT` (A13) | same |
| `ProviderClientsLanderTest.onlyGitHubLands` | github → `available()`; gitlab and bitbucket-cloud → `!available()` | a non-GitHub lander returned |
| `…aReviewerAccountCannotLand` | REVIEWER-role provider → `IllegalArgumentException` | role check removed |

- [ ] Write the tests and the port with throwing/placeholder bodies; push RED; confirm assertion failures in
  `fast tests`.
- [ ] Implement; push GREEN; all three jobs green.
- [ ] Commit messages: `Add failing tests for the pull request lander`, then
  `Add the pull request lander port and its GitHub adapter` (body: what each method maps, A4/A5/A13).

**Mutation guards:** reduction precedence line; empty→PASSED line; page-continuation condition; 409→HeadConflict
mapping; I/O→Uncertain catch; 5xx→Uncertain; sha check before `DELETE`; re-read after non-204; FACTORY
role check; commit-status `complete` term in the `checks` return; pull-request number equality in `read`.

---

### Slice 2: land settings per repository (V90, API, Factory "Land" step)

**Goal:** an operator chooses merge method, ticket rule and fix attempts per repository. Nothing reads them yet.

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V90__land_settings.sql` — `repository_land_settings`
  (`repository_id UUID PK REFERENCES repository(id)`, `revision BIGINT NOT NULL DEFAULT 1`,
  `merge_method TEXT NOT NULL CHECK (merge_method IN ('merge','squash','rebase'))`,
  `ticket_rule TEXT NOT NULL CHECK (ticket_rule IN ('comment','close'))`,
  `fix_attempts INT NOT NULL CHECK (fix_attempts BETWEEN 0 AND 10)`, `updated_by TEXT NOT NULL CHECK (btrim(updated_by)<>'')`,
  `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`), with a header comment in V73's style (why separate from policy).
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/LandSettings.java` (pattern of
  `BuildDefaults`: `get`, `save(repository, expectedRevision, input, subject)`, `Refused(reason)`)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RepositoryBuildResource.java`
  (`GET/PUT /land`, `GET /land/options`), or a sibling `RepositoryLandResource` under the same `@Path` if the
  file grows past ~300 lines
- Create: `spire-ui/src/components/repositories/factory/landSettingsApi.ts`
- Create: `spire-ui/src/components/repositories/factory/LandStep.tsx`
- Modify: `spire-ui/src/components/repositories/factory/RepositoryFactory.tsx` (step after `BuildStep`, keyed on
  revision), `factoryFixtures.ts`, `factoryModel.ts` if steps are counted there
- Modify: `spire-ui/src/components/work-items/workReasons.ts` (`land_settings_changed`, `merge_method_invalid`,
  `ticket_rule_invalid`, `fix_attempts_invalid`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/factory/LandSettingsTest.java` (new, service tier,
  `@QuarkusTest` like `BuildDefaultsTest`; the options cases bind a GitHub FACTORY account to a WireMock forge the
  way `WorkPreparedFixture` does)
- Test: `spire-ui/src/components/repositories/factory/LandStep.test.tsx` (new)
- Modify test: `spire-ui/src/components/repositories/factory/RepositoryFactory.test.tsx`

**Interfaces — Produces:**
```java
// dev.codespire.orchestrator.factory.LandSettings
public record Settings(long revision, PullRequestLander.MergeMethod mergeMethod, TicketRule ticketRule,
                       int fixAttempts, String updatedBy, java.time.Instant updatedAt) {}
public enum TicketRule { COMMENT, CLOSE }
public record Input(long expectedRevision, String mergeMethod, String ticketRule, int fixAttempts) {}
public Settings get(UUID repository);                       // revision 0 + merge/comment/3 when no row
public Settings get(Connection c, UUID repository, boolean lock);
public Settings save(UUID repository, Input input, String subject);
// RepositoryBuildResource
public record LandOptions(boolean checked, List<String> allowed) {}   // unreachable forge → false + all three
```
```ts
export interface LandSettings { revision: number; mergeMethod: 'merge' | 'squash' | 'rebase'; ticketRule: 'comment' | 'close';
  fixAttempts: number; updatedBy: string | null; updatedAt: string | null }
export interface LandOptions { checked: boolean; allowed: LandSettings['mergeMethod'][] }
```
JSON values are lower-case on the wire (`merge`, `comment`) to match the table.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `LandSettingsTest.noRowReadsTheDefaults` | revision 0, `merge`, `comment`, 3 | defaults differ |
| `…aSaveNeedsTheExpectedRevision` | save at revision 0 then again at 0 → `Refused("land_settings_changed")`, row unchanged | revision not compared under lock |
| `…attemptsOutsideZeroToTenAreRefused` | -1 and 11 → `fix_attempts_invalid`; 0 and 10 saved | bound check removed |
| `…anUnknownMethodOrRuleIsRefused` | `TEST-fast` → `merge_method_invalid`; `TEST-move` → `ticket_rule_invalid` | |
| `…theSaverIsTheOidcSubject` | `updated_by` = subject; blank subject → `operator_identity_required` | |
| `…optionsListOnlyWhatGitHubAllows` | stubbed repo flags (WireMock on the fixture forge) → `{checked:true, allowed:[squash]}` | options ignore flags |
| `…anUnreachableForgeOffersAllThreeUnchecked` | forge 500 → `{checked:false, allowed:[merge,squash,rebase]}`; save still accepted | exception escapes |
| `LandStep.test: lists only the allowed methods` | select options = allowed list | |
| `LandStep.test: warns when the saved method is no longer allowed` | saved `rebase`, allowed `[merge]` → warning text visible | warning missing |
| `LandStep.test: offers the two ticket rules in the operator's words` | radio labels match §6 wording | |
| `LandStep.test: says the branch is deleted and what 0 attempts means` | copy present | |
| `LandStep.test: a stale save shows the reason sentence` | PUT 409 `land_settings_changed` → sentence from `workReasons` | raw JSON shown |
| `RepositoryFactory.test: the Land step follows Build` | step order | step missing |

- [ ] RED (stub `LandSettings` returning a wrong constant; `LandStep` renders nothing), then GREEN.
- [ ] Commits: `Add failing tests for repository land settings`, `Add per-repository land settings and the Land step`.

**Mutation guards:** expected-revision comparison; `BETWEEN 0 AND 10` check in code (not only the DB);
allowed-methods filter; unreachable-forge fallback.

---

### Slice 3: landing facts in the contract and the decider (dark)

**Goal:** the aggregate can record a landing, a hand-off and a closed pull request, readmit refuses landed and
land-pending items, and the UI stops offering Readmit for them. Nothing produces these decisions yet; land
stays `capability_unavailable`.

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/work/WorkLanding.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/work/WorkExecution.java` (component 8 `landing`,
  wither `landed(WorkLanding)`, 7-arg constructor passes `null`; every existing wither carries `landing`)
- Modify: `spire-contract/src/main/java/dev/codespire/contract/work/WorkItemLifecycle.java` (`landed`,
  `landHanded`, `pullRequestClosed`, `LAND_HANDOFF_REASONS`, `landedIn(List<WorkItemEvent>, long generation, long pr)`)
- Modify: `spire-contract/src/test/java/dev/codespire/contract/ContractSchemaSnapshotTest.java` (nested list += `WorkLanding`)
  and `spire-contract/src/test/resources/contract-schema.txt`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkItemTransitions.java`
  (`advance` readmit refusals, line ~160)
- Modify: `spire-ui/src/components/work-items/workPreparationApi.ts` (`WorkExecution.landing?`),
  `spire-ui/src/components/work-items/WorkItemActions.tsx` (`canReadmit`), `workReasons.ts`
  (`landed`, `pr_closed_by_human`, every hand-off reason, `readmission_landed`, `readmission_land_pending`)
- Test: `spire-contract/src/test/java/dev/codespire/contract/work/WorkExecutionTest.java`,
  `spire-contract/src/test/java/dev/codespire/contract/work/WorkLandDecisionTest.java` (new, decide table),
  `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkLandReadmitIT.java` (new, extends
  `WorkPreparedFixture`), `spire-ui/src/components/work-items/WorkItems.test.tsx` (where `WorkItemActions` is
  tested today; there is no `WorkItemActions.test.tsx`)

**Interfaces — Produces:**
```java
// dev.codespire.contract.work
public record WorkLanding(PullRequestRef pullRequest, String mergeCommit, String method, String mergedBy,
                          java.time.Instant landedAt) {
    public static final String FACTORY = "factory";
    // mergeCommit full 40-hex; method merge|squash|rebase; mergedBy FACTORY or a stable provider id (never a login)
}
// WorkExecution: record header gains `WorkLanding landing` LAST; landing.pullRequest().number() must equal pullRequest().number()
public WorkExecution landed(WorkLanding value);
// WorkItemLifecycle
public static final Set<String> LAND_HANDOFF_REASONS = Set.of(/* the hand-off reasons in Global Constraints */);
public static WorkItemEvent landed(WorkItemEvent item, WorkLanding landing);          // phase complete, completed/landed, WORK_ITEM_LANDED, attempt finished, slot released
public static WorkItemEvent landHanded(WorkItemEvent item, String reason);            // phase land, awaiting_input/<reason>, LAND_HANDED, slot released
public static WorkItemEvent pullRequestClosed(WorkItemEvent item);                     // stopped/pr_closed_by_human, PULL_REQUEST_CLOSED
public static boolean landedIn(List<WorkItemEvent> history, long generation, long pullRequest);
```
Item completion happens at `WORK_ITEM_LANDED` (§7.7: the ticket and the branch delete do not hold completion),
so `landed` moves straight to `complete / completed / landed` — this resolves §7.3 step 4 in favour of §7.7.

**Readmit (§7.9):** in `advance`, when `readmit`: a current-generation `WORK_ITEM_LANDED` →
`409 readmission_landed`; `land / awaiting_input` with a reason in `LAND_HANDOFF_REASONS` →
`409 readmission_land_pending`. Both before `next.readmit()`.

**UI (`canReadmit`, A8):** exclude `completed` with `progress.execution.landing`, and `phase === 'land' &&
workflowStatus === 'awaiting_input'`.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `WorkExecutionTest.landingIsCarriedThroughEveryWither` | `landed(x)` then `reviewed/delivered/verified` keep `landing` | a wither drops it |
| `…aLandingOfAnotherPullRequestIsRefused` | landing PR 43 on execution PR 42 → IAE | number check removed |
| `…anExecutionStoredBeforeLandingStillReads` | JSON without `landing` → null | |
| `ContractSchemaSnapshotTest` (golden) | 8-component `WorkExecution` + `# WorkLanding` block | snapshot not updated |
| `WorkLandDecisionTest.landedCompletesTheItem` | `complete/completed/landed`, `WORK_ITEM_LANDED`, `reserved=false`, landing on execution | |
| `…landHandedWaitsAtLandForTheOperator` | `land/awaiting_input/<reason>`, `LAND_HANDED`, no gate | |
| `…anUnknownHandOffReasonIsRefused` | `landHanded(item,"TEST-x")` → IAE | set check removed |
| `…aClosedPullRequestStopsTheItem` | `stopped/pr_closed_by_human` | |
| `…landedInSeesOnlyThisGenerationAndPullRequest` | landing in gen 1 is not `landedIn(…,2,…)`; PR 42 not PR 43 | generation/PR filter removed |
| `WorkLandReadmitIT.aLandedItemCannotBeReadmitted` | drive an item to `landed` via the decider + `store.appendDecision`, POST resume readmit → 409 `readmission_landed`, generation unchanged | refusal removed |
| `…aHandedItemCannotBeReadmitted` | `land/awaiting_input/land_blocked` → 409 `readmission_land_pending` | |
| `…anItemStoppedElsewhereCanStillBeReadmitted` | `stopped/gate_rejected` at plan → readmit 200 | refusal too broad |
| `WorkItems.test (actions): no Readmit on a landed item` | completed + landing → button absent | |
| `WorkItems.test (actions): no Readmit at land awaiting_input` | | |
| `WorkItems.test (actions): Readmit still offered on a completed item without a landing` | | exclusion too broad |

- [ ] RED (decider methods throw; `WorkLanding` exists; snapshot golden updated to the target text so it fails
  on the record), then GREEN.
- [ ] Commits: `Add failing tests for landing facts and readmit refusals`,
  `Record landings in the work item and refuse readmit after land`.

**Mutation guards:** `landing` in each wither; PR-number equality in `WorkExecution`; hand-off reason set check;
both readmit refusals; both `canReadmit` exclusions.

---

### Slice 4: the land effect (V91) and the `WorkLanding` sweep

**Goal:** an approved land on a GitHub item merges at the approved head, deletes the branch and completes the
item. Every code refusal (failed checks, conflict, behind) is **handed** to the operator with its own reason in
this slice; slice 7b turns those three into the fix loop. This keeps every intermediate state safe.

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V91__work_land_effect.sql` — `work_land_effect`:
  `effect_id UUID PK` (the land attempt id), `work_item_id TEXT NOT NULL`, `generation BIGINT NOT NULL`,
  `item_revision BIGINT NOT NULL`, `pr_number BIGINT NOT NULL`, `approved_head TEXT NOT NULL`,
  `state TEXT NOT NULL CHECK (state IN ('pending','checking','waiting_checks','merging','uncertain','merged','cleaning','done','handed','fixing','closed'))`,
  `reason TEXT`, `detail BYTEA` (Tink, AAD `work-land-detail:<effect_id>`: GitHub's message and the check
  summary, which may quote source), `fix_payload BYTEA` (Tink, AAD `work-land-fix:<effect_id>`),
  `merge_commit TEXT`, `merged_by TEXT`, `method TEXT`, `cleanup_tries INT NOT NULL DEFAULT 0`, `note TEXT`,
  `waiting_since TIMESTAMPTZ`, `next_check_at TIMESTAMPTZ`, `created_at`, `updated_at`; unique partial index
  `(work_item_id, generation, pr_number) WHERE merge_commit IS NOT NULL`.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkLanding.java` (sweep;
  `@Scheduled(every="${spire.work-land-interval:5s}")`, `concurrentExecution=SKIP`)
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkLandDecision.java` (pure:
  `LandState` + `AllowedMethods` + method → `Next` = MERGE | WAIT | HAND(reason) | FIX(reason) | MERGED_FOUND | CLOSED)
- Modify: `WorkItemStore.projectExecution` (insert the effect row on `land` + `PHASE_STARTED` with a PR, only
  when `item.gate()` is the APPROVED land gate and its `artifact` equals `execution.head()`; `approved_head` is
  that artifact. On `HUMAN_TAKEOVER`/`WORK_ITEM_RETIRED` refuse only rows still before the merge call
  (`pending`, `checking`, `waiting_checks`). A row in `uncertain`, `merged` or `cleaning` keeps draining: the
  merge may have happened, and it is recovered by reading, then recorded through `transitions.landed`, which
  accepts a suspended item (Review Focus 13))
- Modify: `WorkPhaseCapability.available` (`case "land"`: mode `approve`, a delivered PR, and the FACTORY
  account's lander `available()`; `auto_if_green` stays false)
- Modify: `WorkItemTransitions` (`landed(id, effectId, WorkLanding)`, `landHandOff(id, effectId, reason)`,
  `landClosed(id, effectId)`, each under `lockItem`, each checking history first)
- Modify: `WorkItemResource` (`View` gains `land`: `LandView(state, reason, message, checks, method,
  ticketRule, branchDeleted, note)` read from the latest effect row of the generation, detail decrypted)
- Modify: `spire-ui/src/api.ts` (`WorkItemDetail.land?: LandView`) and `workReasons.ts` (`branch_delete_failed`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkLandDecisionTest.java` (new, pure, fast tier)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkLandingIT.java` (new, extends
  `WorkPreparedFixture`; stubs the GitHub endpoints on the fixture's WireMock `forge`; drives an item to an
  open land gate the way `WorkReviewIT` does, then approves through `transitions.answer`)

**Interfaces — Produces:**
```java
// WorkLandDecision (pure)
public sealed interface Next { record Merge() …; record Wait() …; record Hand(String reason) …; record Fix(String reason) …;
                               record MergedFound(String sha, String mergedBy) …; record Closed() … }
public static Next checking(PullRequestLander.LandState pr, String approvedHead,
                            PullRequestLander.AllowedMethods allowed, PullRequestLander.MergeMethod method);
// WorkItemTransitions
Outcome landed(String id, UUID effect, WorkLanding landing);   // no-op 200 "landing_already_recorded" when landedIn(...)
Outcome landHandOff(String id, UUID effect, String reason);
Outcome landClosed(String id, UUID effect);
```
**Consumes:** `PullRequestLander` (slice 1), `LandSettings` (slice 2), the decider (slice 3).

**`checking` order (§7.3 as amended):**
1. merged → `MergedFound` (merger from `mergedBy`); closed unmerged → `Closed`.
2. head ≠ `approved_head` → `Hand(land_head_moved)`.
3. method not in `allowed` (when `checked`) → `Hand(land_merge_method_not_allowed)` (A5).
4. checks `NOT_CODE` → `Hand(land_checks_not_code)`.
5. checks `FAILED` → `Fix(land_checks_failed)`.
6. `mergeable_state` `dirty` → `Fix(land_conflict)`; `behind` → `Fix(land_branch_behind)`.
7. `mergeable == null` or state `unknown` → `Wait`; checks `PENDING` → `Wait`, **except** state `clean`
   (pending non-required checks are not waited for, A4).
8. `blocked` → `Hand(land_blocked)`; `draft` → `Hand(land_pr_draft)`.
9. `clean`, `unstable`, `has_hooks` with checks PASSED (or `clean` with PENDING) → `Merge`.

In this slice the sweep maps `Fix(r)` to `landHandOff(r)`.

**Sweep states:** `pending→checking`; `Wait` → `waiting_checks` with `waiting_since` and `next_check_at=+60s`,
`Hand(land_checks_timeout)` after 6 h; `Merge` → mark `uncertain` (committed) **before** `merge(...)`:
`Merged` → `merged`; `HeadConflict` → re-read, head unchanged → `checking`, changed → hand `land_head_moved`;
`Refused` 405/422 → re-read once and run `checking` on it: a `Fix` stays a fix (handed here), anything else →
hand `land_merge_refused` with GitHub's message; `Refused` 403/404 → hand `land_merge_refused`; `Uncertain` →
row stays `uncertain` and the next pass only reads: merged → `merged`; open at `approved_head` → `checking`;
anything else → hand. `merged` → `transitions.landed` (merger `factory` or `mergedBy`), then `cleaning` →
`deleteBranch(repo, control.branch(), approved_head or the found head)`: `DELETED|ABSENT` → `done`;
`STILL_PRESENT|MOVED` or fault → `cleanup_tries+1`, at 3 → `done` with `note=branch_delete_failed`. A
`landHandOff` refused because the item moved on (409) leaves the row `handed` with `reason=work_item_changed`.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `WorkLandDecisionTest` (one per row above, 14 rows), e.g. `aMovedHeadIsHandedBeforeAnythingElse`, `cleanWithPendingNonRequiredChecksMerges`, `unknownMergeableStateWaits`, `unstableWithPassedChecksMerges`, `unstableWithAFailedCheckFixes`, `blockedWithPassedChecksIsHanded`, `aForbiddenMethodIsHandedBeforeAnyMergeCall` | the `Next` for each input | the row's branch reordered or removed |
| `WorkLandingIT.anApprovedLandMergesDeletesAndCompletes` | approve land → one `PUT /merge` with `sha`=gate artifact and the saved method; `DELETE /git/refs/heads/<branch>`; item `completed/landed`; `execution.landing` has the merge commit and `factory` | capability or projection not wired |
| `…theMergeIsPinnedToTheApprovedHead` | PR head moved after approval → no `PUT`, item `land/awaiting_input/land_head_moved` | head check removed |
| `…anUncertainMergeIsRecoveredByReadingNeverResent` | `PUT` resets the connection; next pass reads PR merged → `completed`; exactly one `PUT` | uncertain re-sends |
| `…anUncertainMergeStillOpenAtTheHeadIsCheckedAgain` | after reset, PR open at head → back to `checking`, second `PUT` allowed only after that read | |
| `…a409WithTheSameHeadChecksAgain` / `…a409WithANewHeadIsHanded` | A5 | |
| `…a405IsReReadAndHandedWithGitHubsMessage` | 405 + clean re-read → `land_merge_refused`, message in `land.message` | |
| `…pendingChecksWaitThenTimeOut` | `WorkClock` advanced past 6 h → `land_checks_timeout` | timeout removed |
| `…aFailedCheckIsHandedUntilTheFixLoopExists` | `land_checks_failed`, no merge | |
| `…aPullRequestAlreadyMergedIsRecordedAsFound` | PR merged by `900123` → landing `mergedBy=900123` | |
| `…aClosedPullRequestStopsTheItem` | `stopped/pr_closed_by_human`, no `DELETE` | branch deleted on close |
| `…aBranchThatWillNotDeleteIsNotedAfterThreeTries` | ref stays → after 3 passes `note=branch_delete_failed`, item still `completed` | completion waits on delete |
| `…landStaysUnavailableOffGitHub` | GitLab-bound item approving land → `land_capability_unavailable` (unchanged) | capability too broad |
| `…autoIfGreenStaysUnavailable` | land mode `auto_if_green` → `capability_unavailable` | |
| `…landingIsRecordedOnce` | call `transitions.landed` twice for one effect → one `WORK_ITEM_LANDED` | history check removed |
| `…aTakeoverBeforeTheMergeCallRefusesTheEffect` | row `waiting_checks`, human push → item suspended, row refused, no `PUT` ever | takeover leaves the row live |
| `…aTakeoverDuringAnUncertainMergeStillRecordsTheMerge` | `PUT` resets, human push suspends the item, next pass reads PR merged → `WORK_ITEM_LANDED` recorded, item `completed/landed`, no second `PUT` | takeover refuses `uncertain` rows |
| `…noEffectWithoutAnApprovedLandGate` | `PHASE_STARTED` at land with no APPROVED gate (e.g. a forged history row) → no effect row | gate condition removed |

Fakes: if a QuarkusMock replaces any orchestrator bean on the new path (e.g. `WorkClock`), override every method
the sweep reaches.

- [ ] RED (`WorkLandDecision.checking` returns `Wait`; sweep body empty; capability unchanged), then GREEN.
- [ ] Commits: `Add failing tests for the land effect`, `Merge approved items at the approved head`.

**Mutation guards:** each `checking` row; `uncertain` marked before the merge call; uncertain path never calls
`merge`; 6 h bound; `cleanup_tries` bound; `landedIn` check in `transitions.landed`; capability `approve`-only
and PR-present conditions; the takeover refusal's pre-merge state list; the approved-gate condition on insert.

Note: between this slice and slice 8, "Recheck and resume" on a handed item reopens the land gate on
`execution.head()` with no note. That is safe (the effect re-checks the head) and is closed by slice 8's
`retry_land_required`; do not ship a release from an intermediate slice.

---

### Slice 5: the post-land ticket effect (A1)

**Goal:** after a landing, the ticket gets one comment (and, under `close`, a close) through the tracker outbox,
drained whatever the item's phase, without ever calling `Phase.valueOf("complete")`.

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V92__post_land_tracker_effect.sql` —
  `ALTER TABLE work_tracker_outbox ADD COLUMN effect_class TEXT NOT NULL DEFAULT 'phase' CHECK (effect_class IN ('phase','post_land'))`.
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkSourceEffects.java`
  (`enqueuePostLand(Connection, …)` inside the caller's transaction; `dispatch` branches on class; `sweep`
  isolates per-row failures)
- Modify: `WorkItemTransitions.landed` (enqueue in the same transaction as `WORK_ITEM_LANDED`, binding that
  event's revision)
- Modify: `WorkItemStore.projectExecution` (the `work_item_invalidated` refusal must not touch `post_land` rows:
  add `AND effect_class='phase'`)
- Modify: `WorkLanding` (Jira + `close` → `note=ticket_close_unsupported` on the effect row)
- Modify: `workReasons.ts` (`ticket_close_unsupported`, `ticket_rule_changed`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkPostLandEffectsTest.java` (new,
  extends `WorkFixture`, pattern of `WorkSourceEffectsTest`)

**Interfaces — Produces:**
```java
// WorkSourceEffects
void enqueuePostLand(Connection c, UUID id, String itemId, long revision, Kind kind, String value) throws SQLException;
static String landedComment(WorkLanding landing, String handle);   // "Merged by the factory: <url> (<sha7>). The ticket status is unchanged so testing can follow."
static Optional<String> closeTransition(WorkSourceType type);       // GITHUB "closed", GITLAB "close", JIRA empty
```
Effect ids are derived (`UUID.nameUUIDFromBytes("post-land:<item>:<generation>:<kind>")`) so a replayed
landing cannot enqueue twice. The existing `enqueue` copies `generation, revision, phase` from `work_item WHERE
id=? AND revision=?`, so `enqueuePostLand` runs **after** `store.appendDecision` of `WORK_ITEM_LANDED` in the
same transaction (the projection row then holds that revision and phase `complete`). The merger's handle for
"Merged by <handle>" is resolved **before** `lockItem` (`ActorDirectory.byId` on the repository's REVIEWER or
FACTORY account; a forge call never runs under the item lock); an unresolved id writes "Merged by a person on
GitHub", never the raw id. Post-land dispatch: no policy or phase mode; the generation must match; a
TRANSITION is refused `ticket_rule_changed` unless the current `LandSettings.ticketRule` is still `CLOSE`;
COMMENT always proceeds. The uncertain/recover path is shared unchanged. The TRANSITION row is dispatched
only after the COMMENT row is `sent` (order: comment, then close). In `sweep`, a phase row whose phase is not
a `WorkPolicy.Phase` is marked `refused/unknown_phase` instead of throwing, and one row's
`RuntimeException` never stops the rest of the batch.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `aPostLandCommentIsSentAfterCompletion` | item `completed`, row drained → `sent`, comment text has the PR link and short sha, marker = effect id | dispatch calls `Phase.valueOf` |
| `anUnknownPhaseRowDoesNotStallTheBatch` | a phase row with phase `complete` and a post-land row in one sweep → first `refused/unknown_phase`, second `sent` | exception escapes the loop |
| `theCloseRuleClosesAfterTheComment` | rule `close` on GitHub Issues → COMMENT then TRANSITION `closed`, in that order | ordering removed |
| `aRuleChangedToCommentDoesNotClose` | rule switched to `comment` before drain → TRANSITION `refused/ticket_rule_changed`, COMMENT sent | rule not re-read |
| `jiraUnderCloseCommentsAndNotesTheSkip` | no TRANSITION row; effect `note=ticket_close_unsupported` | transition enqueued |
| `anUncertainPostLandCommentIsFoundNotResent` | uncertain → `findComment` hit → `sent`, one POST | |
| `invalidationLeavesPostLandRowsAlone` | a `WORK_ITEM_REFUSED` after landing does not refuse the post-land row | class filter missing |
| `aReplayedLandingDoesNotEnqueueTwice` | `transitions.landed` twice → one COMMENT row | derived id removed |
| `aHumanMergeSaysWhoMerged` | merger id resolves to the observed handle (`ActorDirectory`) → "Merged by TEST-handle" | |
| `anUnresolvedMergerIsNotPrintedAsAnId` | `byId` fails → "Merged by a person on GitHub"; the numeric id is absent from the text | raw id written |
| `closeTransitionPerSourceType` (pure) | GITHUB `closed`, GITLAB `close`, JIRA empty | a source type mapped wrong |

- [ ] RED, then GREEN. Commits: `Add failing tests for the post-land ticket effect`,
  `Tell the ticket after land without stalling the outbox`.

**Mutation guards:** `Phase.valueOf` absent from the post-land branch; per-row isolation in `sweep`;
`effect_class='phase'` in the invalidation update; rule re-read for TRANSITION; comment-before-close order.

---

### Slice 6: a person merges or closes the pull request (A2, A6, A10)

**Goal:** GitHub's `pull_request closed` arrives as `merged` or `closed`. A human merge completes the item with
the human as merger; a close stops it; the factory's own merge or push is never takeover. Holding a running fix
run and accounting its late result move to slice 7b: before the fix loop exists no item is in `build` or
`verify` with a pull request (`WorkProgress.start` clears `execution` for a build, and `linked` needs
`execution.pullRequest()`), so no test here could reach that path.

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/IntegrationEvent.java` (`RepositoryActivity`
  gains `mergedBy` last; 9-arg constructor passes `null`; wither `withMergedBy(String)`), snapshot golden
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubIngress.java` (`activity`: `closed` +
  `merged:true` → kind `merged`, `mergedBy = pull_request.merged_by.id`; unmerged → `closed`)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkGateChannels.java` (`merged`/`closed`
  routing; `currentMachine` made package-visible and used by `WorkItemControl`)
- Modify: `WorkItemControl.change` (skip recorded **or current** FACTORY/REVIEWER identity, A10)
- Modify: `WorkItemTransitions` (`landedByHuman(id, delivery, prNumber, mergedBy)`, `closedByHuman(id, delivery, prNumber)`;
  both enqueue the post-land ticket effect of slice 5 in their own transaction, after the append)
- Modify: `WorkLanding` (a webhook-recorded landing creates or advances the effect row to `cleaning`; a row in
  any pre-merge state seeing `landedIn` moves to `cleaning` without a merge)
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubIngressActivityTest.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkLandWebhookIT.java` (new, extends
  `WorkPolicyFixture` like `WorkGateChannelsIT`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkGateChannelsIT.java` (rotation case)

**Interfaces — Produces:**
```java
record RepositoryActivity(RepoRef repo,String kind,String actorId,String branch,String head,long prId,
                          String reviewId,String commentId,boolean fixCommand,String mergedBy) implements IntegrationEvent {
    public RepositoryActivity(RepoRef repo,String kind,String actorId,String branch,String head,long prId,String reviewId,String commentId,boolean fixCommand) {
        this(repo,kind,actorId,branch,head,prId,reviewId,commentId,fixCommand,null);
    }
    public RepositoryActivity withMergedBy(String value) { … }
}
Outcome landedByHuman(String id, String delivery, long pullRequest, String mergedBy);
Outcome closedByHuman(String id, String delivery, long pullRequest);
```
Note the activity loop selects only items `NOT IN ('retired','completed')` (`WorkGateChannels.java:56`), so a
webhook arriving after the effect completed the item never reaches `landedByHuman`. The `landedIn` guard there
matters only while the item is not yet completed (for example suspended); test it by calling `landedByHuman`
directly.
**Routing (§7.8 + A2):** for a linked item and kind `merged`/`closed`: sender is `control.machine(actor)` or
`currentMachine(...)` → receipt `recorded_machine` and nothing else (the effect owns it). Otherwise `merged` →
`landedByHuman` (any status but `retired`/`completed`, `suspended` included, per §7.8 "at any phase after the
pull request exists"),
`closed` → `closedByHuman`. Both check `landedIn` under the lock; the later observer only advances the effect
row. The landing merge commit comes from the effect's later read when the webhook lacks it (`merge_commit_sha`
is in the payload; use it).

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `GitHubIngressActivityTest.aMergedCloseIsMergedWithTheMergerId` | kind `merged`, `mergedBy` = `merged_by.id`, sender kept as actor | still `push`; `mergedBy` from `sender` |
| `…anUnmergedCloseIsClosed` | kind `closed`, `mergedBy` null | |
| `…synchronizeIsStillPush` | unchanged | |
| `ContractSchemaSnapshotTest` | 10-component `RepositoryActivity` | |
| `WorkLandWebhookIT.aHandMergeCompletesWithTheHumanAsMerger` | item at land gate, `merged` by `900123` → `completed/landed`, landing `mergedBy=900123`, branch delete requested, post-land comment row | still suspended |
| `…theFactorysOwnMergeWebhookIsLeftToTheEffect` | `merged` with sender = factory bot before the effect records → receipt `recorded_machine`; then effect records → exactly one `WORK_ITEM_LANDED`, `mergedBy=factory` | machine check removed |
| `…aSecondLandingForTheSamePullRequestIsANoOp` | effect records the landing; then `transitions.landedByHuman` called **directly** (the webhook loop would skip a completed item) → 200 `landing_already_recorded`, one `WORK_ITEM_LANDED` | `landedIn` check removed |
| `…aHandMergeWhileInReviewCompletes` | item at `review / active` (the reviewer still running), `merged` by `900123` → `completed/landed`, no takeover | merged routed only at the land gate |
| `…aHandMergeOfASuspendedItemCompletes` | item suspended by an earlier human push, then `merged` → `completed/landed` | suspended excluded |
| `…aCloseStopsTheItemWithoutDeletingTheBranch` | `stopped/pr_closed_by_human`, no `DELETE` | |
| `…aRotatedFactoryPushIsNotTakeover` | factory account rotated after claim, `push` from the new bot id → receipt `recorded_machine`, item not suspended | `currentMachine` not consulted in `change` |
| `…aHumanPushIsStillTakeover` | human id → `suspended/human_takeover` | exclusion too broad |

- [ ] RED, then GREEN. Commits: `Add failing tests for hand merges and closes`,
  `Recognise a pull request merged or closed on GitHub`.

**Mutation guards:** `merged:true` branch in the ingress; `merged_by.id` source; machine/current-machine skip in
both `WorkGateChannels` and `WorkItemControl`; `landedIn` check in `landedByHuman`.

---

### Slice 7a: the run side of a land fix (dark)

**Goal:** a held item build may run on the item's own existing branch, clone it at an exact head, see the PR's
base branch, and publish only while the remote branch still points at the expected head. Nothing in the
orchestrator sends such a run yet.

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/RunCommand.java` (`ExecuteWorkRun`:
  replace the `existingBranch()` refusal with "existing branch only when `branch` starts with `spire/work-`")
- Modify: `spire-contract/src/main/java/dev/codespire/contract/work/WorkPublicationPermit.java`
  (`expectedRemoteHead` then `mergedBaseTip`, both last, nullable, 40-hex when present; `mergedBaseTip` only
  with `expectedRemoteHead`; 6-arg constructor passes `null, null`; wither
  `onExistingBranch(String expectedRemoteHead, String mergedBaseTip)`), snapshot golden.
  `mergedBaseTip` is `LandState.baseSha` read by the land effect for a `land_conflict` or `land_branch_behind`
  fix, null for `land_checks_failed`. It travels in the permit (orchestrator-issued), never from the workspace,
  because the agent can move any ref in its own clone.
- Modify: `spire-run-worker/src/main/java/dev/codespire/runworker/RunUnitBuilder.java` (`buildHeld`: existing
  branch → init env `SPIRE_FETCH_BRANCH=<protectedBranch>`; `publication`: a permit with `expectedRemoteHead`
  requires `original.execution().pushesToAnExistingBranch()` and `baseCommit().equals(expectedRemoteHead)`, and
  sets `SPIRE_EXPECTED_REMOTE_HEAD`; a permit without it on an existing-branch run is refused)
- Modify: `spire-publisher/src/main/java/dev/codespire/publisher/CloneMain.java` (fetch `SPIRE_FETCH_BRANCH` into
  `refs/spire-base/<name>` before the remote is removed; removing `origin` deletes `refs/remotes/origin/*`)
- Modify: `spire-publisher/src/main/java/dev/codespire/publisher/PublicationPolicy.java` /
  `PublishCycle.java` (when `SPIRE_EXPECTED_REMOTE_HEAD` is set, read the remote ref immediately before the push;
  different → `failed("REMOTE_HEAD_MOVED", …)`, no push; the non-force push stays the second guard)
- Modify: `spire-workspace/src/main/java/dev/codespire/workspace/PublishRepo.java` (`remoteHead(branch, credential)`;
  `changesSince(baseCommit, sha, mergedBaseTip)`: when `mergedBaseTip` is set, it must be an ancestor of `sha`
  (else `BASE_TIP_UNAVAILABLE`, no push) and a changed path whose tip blob and mode equal the blob and mode at
  `mergedBaseTip` is dropped from the `ChangeSet` — the base brought it, not the agent. `PushGate` itself is
  unchanged: it still judges every remaining path, symlinks included)
- Modify: `PublishCycle` (pass the permit's `mergedBaseTip` to `changesSince`; a missing object → `failed("BASE_TIP_UNAVAILABLE", …)`)
- Tests: `spire-contract/.../command/RunCommandTest` (or `WorkRunProtocolTest`), `WorkPublicationPermit` cases in
  `spire-contract/src/test/java/dev/codespire/contract/work/WorkRunProtocolTest.java`,
  `spire-run-worker/src/test/java/dev/codespire/runworker/RunUnitBuilderTest.java`,
  `spire-publisher/src/test/java/dev/codespire/publisher/CloneMainTest.java` (new; no such file today — use
  `GitFixture` and the local-origin overload `CloneMain` already exposes for tests),
  `spire-publisher/src/test/java/dev/codespire/publisher/PublishCycleTest.java`,
  `spire-workspace/src/test/java/dev/codespire/workspace/PublishRepoTest.java`

**Interfaces — Produces:**
```java
public WorkPublicationPermit onExistingBranch(String expectedRemoteHead, String mergedBaseTip);
public boolean pushesToExistingBranch() { return expectedRemoteHead != null; }
// PublishRepo
public Optional<String> remoteHead(String branch, GitCredential credential) throws GitAPIException;
public ChangeSet changesSince(String baseCommit, String sha, String mergedBaseTip) throws IOException;   // null tip = today's behaviour
```

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `itemBuildsMayUseOnlyTheirOwnExistingBranch` | existing branch `spire/work-TEST` accepted; `feature/TEST` and `main` refused | refusal kept, or loosened to any branch |
| `aPermitsExpectedHeadIsAFullSha` | `abc` refused; null allowed; wither keeps every other component | |
| `ContractSchemaSnapshotTest` | 8-component permit (`expectedRemoteHead`, `mergedBaseTip`) | |
| `RunUnitBuilderTest.anExistingBranchItemBuildFetchesThePullRequestBase` | init env has `SPIRE_FETCH_BRANCH=TEST-main` | |
| `…aNewBranchItemBuildFetchesNothingExtra` | absent | env always set |
| `…anExpectedHeadPermitNeedsAnExistingBranchRunFromThatHead` | mismatch base → IAE; match → env `SPIRE_EXPECTED_REMOTE_HEAD` | check removed |
| `…anExistingBranchRunNeedsAnExpectedHead` | permit without it → IAE | |
| `CloneMainTest.theFetchedBaseSurvivesRemoteRemoval` | after clone, `refs/spire-base/TEST-main` resolves to the origin commit | fetched into `refs/remotes/origin` |
| `PublishCycleTest.aMovedRemoteHeadRefusesThePush` | remote ref advanced by a TEST commit → `REMOTE_HEAD_MOVED`, remote unchanged | pre-push read removed |
| `PublishCycleTest.theExpectedRemoteHeadPublishes` | equal → pushed | |
| `PublishRepoTest.remoteHeadReadsTheBranch` | | |
| `aPermitsBaseTipNeedsAnExpectedHead` | `mergedBaseTip` without `expectedRemoteHead` → IAE; non-hex → IAE | |
| `PublishRepoTest.aBaseMergeDoesNotBlameTheAgentForTheBasesProtectedPaths` | base commit edits `.github/workflows/TEST.yml`; branch merges it (no other change) → `changesSince(approved, tip, baseTip)` omits the workflow; `PushGate` allows | exclusion missing (today's behaviour refuses) |
| `PublishRepoTest.anAgentEditInsideABaseMergeIsStillRefused` | same merge, plus the agent changes `.github/workflows/TEST.yml` to differ from the base tip → path kept, `PushGate` refuses | exclusion compares names, not blob+mode |
| `PublishRepoTest.aBaseTipThatIsNotAnAncestorIsRefused` | `mergedBaseTip` not reachable from the tip → `BASE_TIP_UNAVAILABLE` | ancestry check removed (an agent could name any commit) |
| `PublishRepoTest.withoutABaseTipNothingIsDropped` | null tip → identical `ChangeSet` to the two-argument call | exclusion applied always |

- [ ] RED, then GREEN. Commits: `Add failing tests for publishing to an item's own branch`,
  `Let an item build publish to its own branch at an expected head`.

**Mutation guards:** `spire/work-` prefix check; permit sha pattern; both `publication` checks; pre-push remote
read; `refs/spire-base` destination; base-tip ancestry check; blob-and-mode equality in the exclusion.

Limit: a `mergedBaseTip` names a commit the agent never fetched only if the base moved between the land effect's
read and the run's clone. Then the merge is of a newer base, some base paths differ from the named tip, and a
protected one is refused — fail closed, the operator sees the refusal. Record that in UNVERIFIED (slice 9).

---

### Slice 7b: the land fix loop

**Goal:** a code refusal requests a land fix; the item rewinds to build on the same PR; build and deliver gates
are skipped; the fix runs on the item branch from `approved_head`; deliver pushes with the lease and reuses the
PR; review re-runs; a blocked review hands the item over; land opens a new gate.

**Files:**
- Modify: `spire-contract/.../work/WorkItemLifecycle.java` (`landFixRequested(item, reason)`;
  `landFixInProgress(List<WorkItemEvent>)`; `enter(...)` overload with `boolean landFix`; `LAND_FIX_COUNTED`)
- Modify: `spire-contract/.../work/WorkProgress.java` (`startKeepingExecution(UUID,String,Instant)` — counts a
  build run, keeps `execution`)
- Modify: `WorkItemTransitions` (`advance`/`answer`/`enterPrepared` derive `landFix` from history under the lock
  and pass it (A9); `validExecution` build/verify/deliver accept the kept PR during a land fix;
  `landFix(id, effect, reason, payload)` with budget and item-limit checks)
- Modify: `WorkLanding` (`Fix(r)` → `transitions.landFix`; `fixing` row stores the encrypted payload)
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkLandFixes.java` (reads the latest
  fix request and its payload; `promptSection(...)`)
- Modify: `spire-orchestrator/.../factory/WorkRunAssembly.java` (land-fix variant: `baseBranch = branch = item
  branch`, `baseCommit = approved_head`, `.onExistingBranch(pr base)`, no checkpoint resume, prompt = task +
  fix payload (+ verify failure for a retry within the fix))
- Modify: `WorkRunDispatcher.claim` (pass the land-fix context; `WorkControl.branch` stays the item branch, A7)
- Modify: `WorkItemRunBridge.finishBuild` (land-fix build proof carries the kept PR, `reviewId` null)
- Modify: `WorkDelivery` (land fix: permit `.onExistingBranch(approved_head, mergedBaseTip)` with the tip from the
  fix row; after `pushed`, complete deliver with `execution.delivered(keptPr)`, no `open`; refusal
  `verified_checkpoint_not_observed` query unchanged)
- Modify: `WorkItemLifecycle.LAND_HANDOFF_REASONS` — remove `land_checks_failed`, `land_conflict`,
  `land_branch_behind` (from now on they only ever reach `LAND_FIX_REQUESTED`); add `land_fix_run_failed`
- Modify: `WorkItemTransitions.advance` — (a) readmit refused `409 readmission_land_pending` while
  `landFixInProgress(history)` (Review Focus 15); (b) a failed build result (`PHASE_FAILED` path) while
  `landFixInProgress` → `landHanded("land_fix_run_failed")` instead of `failed/phase_failed`, usage still
  accounted (Review Focus 16)
- Modify (moved from slice 6): `WorkItemStore.projectExecution` (`WORK_ITEM_LANDED` and `PULL_REQUEST_CLOSED`
  refuse pending run/verify/delivery effects and enqueue `work_run_hold_outbox` rows, the same statements as
  `HUMAN_TAKEOVER`, `WorkItemStore.java:118-134`); `WorkItemTransitions.recordLateBuild` generalised to
  `recordLateResult(id, PhaseResult)` accepting a verify attempt by joining `work_verify_effect`;
  `WorkItemRunBridge.java:120` calls it; `WorkVerifyResults` (today it records any non-200 as applied with the
  reason, line 69, losing the verify wall seconds) routes `item_not_active`/`attempt_changed` to it (A6)
- Modify: `WorkReview.advance` (open blockers during a land fix → `transitions.landHandOff(id, …,
  "land_fix_review_blocked")`)
- Modify: `WorkItemResource` `LandView` (`attempt`, `attemptsAllowed` = min(`fix_attempts`, runs left),
  `fixInProgress`); `spire-ui` `canReadmit` also excludes `land?.fixInProgress` (the UI cannot see history)
- Modify: `workReasons.ts` (fix reasons as journey copy; `land_fix_attempts_exhausted`, `land_item_limit_reached`,
  `land_fix_review_blocked`, `land_fix_run_failed` sentences if not already present), `WorkItemActions.tsx`
  (`canReadmit` excludes `land?.fixInProgress`), `api.ts` (`LandView.fixInProgress`)
- Tests: `spire-contract/src/test/java/dev/codespire/contract/work/WorkLandFixDecisionTest.java` (new, decide
  table), `spire-contract/.../WorkProgressTest.java`,
  `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkLandFixIT.java` (new, extends
  `WorkPreparedFixture`; its `RunCommandEmitter` fake captures `ExecuteWorkRun` and `PublishWorkRun` and
  overrides every method the loop reaches)

**Interfaces — Produces:**
```java
// WorkItemLifecycle
public static final Set<String> LAND_FIX_COUNTED = Set.of("land_checks_failed", "land_conflict");
public static WorkItemEvent landFixRequested(WorkItemEvent item, String reason);   // phase build, awaiting_input/<reason>, LAND_FIX_REQUESTED, gate null, execution kept
public static boolean landFixInProgress(List<WorkItemEvent> generationHistory);
//   latest LAND_FIX_REQUESTED in the current generation with no later WORK_ITEM_LANDED, LAND_HANDED,
//   PULL_REQUEST_CLOSED, READMITTED or land GATE_OPENED
public static WorkItemEvent enter(WorkItemEvent item, WorkItemEvent previous, long historySize, boolean approved,
                                  Instant now, boolean available, UUID decisionId, boolean landFix);
//   landFix && phase in {build, deliver} → treated as approved; landFix && build start → startKeepingExecution
// WorkItemTransitions
Outcome landFix(String id, UUID effect, String reason, byte[] encryptedPayload);
//   count(LAND_FIX_REQUESTED in generation with reason in LAND_FIX_COUNTED) + (counted ? 1 : 0) > fix_attempts
//     → landHanded("land_fix_attempts_exhausted"), detail = the last reason
//   !progress.within(limits,"build") → landHanded("land_item_limit_reached"), detail names the limit
//     (maxRunsPerItem, maxStepsPerPlan, maxWallClockSeconds, maxCostMillicents, maxCallsPerItem, or
//      usage_unknown — `within` is false for each, WorkProgress.java:37)
//   else landFixRequested(reason), then enterPrepared(..., landFix=true)
// WorkLandFixes
record Fix(UUID effect, String reason, String approvedHead, PullRequestRef pullRequest, String branch, String baseBranch, String payload) {}
Optional<Fix> current(Connection c, List<EventEnvelope> history);
static String promptSection(Fix fix);
```
**Payload:** failed checks (name, conclusion, title, summary, annotations; capped as slice 1) or "Merge
`refs/spire-base/<base>` into this branch and resolve the conflicts; commit the merge." For `behind`, the same
merge instruction with "there are no conflicts to resolve".

**`validExecution` during a land fix:** build — the existing build checks, `pullRequest` equal to the fix's PR,
`verificationAttempt` and `reviewId` null; verify/deliver/review — unchanged formulas (the kept PR is part of
`previous`). Outside a land fix, a build proof with a PR is still refused.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `WorkLandFixDecisionTest.aLandFixRewindsToBuildKeepingThePullRequest` | phase build, `LAND_FIX_REQUESTED`, execution PR kept | execution cleared |
| `…theLandFixFlagAloneSkipsTheBuildGate` | build `approve`, `landFix=true`, `approved=false` → `active/phase_started`; `landFix=false` → gate | flag ignored for build (A9 mutation) |
| `…theLandFixFlagAloneSkipsTheDeliverGate` | same for deliver | flag ignored for deliver |
| `…theLandFixFlagNeverSkipsVerifyOrLand` | verify `approve` and land `approve` still open gates | flag applied to all phases |
| `…aLandFixBuildStartKeepsTheExecutionAndCountsARun` | `runs+1`, execution kept | `start` used |
| `…inProgressEndsAtTheNextLandGateLandingOrHandOff` | each terminator ends it; a verify GATE_OPENED does not | terminator set wrong |
| `WorkProgressTest.startKeepingExecutionCountsTheRun` | | |
| `WorkLandFixIT.aFailedCheckRunsOneFixAndOpensANewLandGate` | approve land, checks FAILED → `LAND_FIX_REQUESTED`; one `ExecuteWorkRun` with `branch` = item branch, `baseCommit` = approved head, existing branch to the PR base, prompt contains the failed check name; no build/deliver gate; verify runs; `PublishWorkRun` permit `expectedRemoteHead` = approved head; no PR opened; review completes on the new head; land gate artifact = new head | any link missing |
| `…aConflictAsksForABaseMerge` | `dirty` → prompt has the merge instruction; init fetches the base; permit `mergedBaseTip` = the read `base.sha` | |
| `…aFailedCheckFixCarriesNoBaseTip` | `land_checks_failed` → permit `mergedBaseTip` null | base tip set for every fix (widens the exclusion) |
| `…behindSpendsNoAttempt` | two `behind` with `fix_attempts=1` → two fixes, no exhaustion | `behind` counted |
| `…zeroAttemptsHandsAFailedCheckButStillMergesBehindBase` | Review Focus 10 | |
| `…theSecondFailureWithOneAttemptIsExhausted` | `land_fix_attempts_exhausted`, detail `land_checks_failed` | off-by-one |
| `…anItemOutOfRunsIsHandedNotStoppedDeeper` | `maxRunsPerItem` reached → `land_item_limit_reached` naming `maxRunsPerItem`, no fix request | limit check after request |
| `…aBlockedReviewOfAFixIsHanded` | review with open blockers during the fix → `land/awaiting_input/land_fix_review_blocked`, no attempt counted | silent wait kept |
| `…aBlockedReviewOutsideAFixStillWaits` | normal delivery → `review_blockers_open` | hand-off too broad |
| `…aVerifyRetryInsideALandFixKeepsThePullRequestAndTheBranch` | verify FAILED → Retry build → second run on the item branch at approved head, no checkpoint, PR kept, no build gate | |
| `…aBuildProofWithAPullRequestOutsideAFixIsRefused` | `phase_evidence_mismatch` | guard loosened |
| `…theFixRunsPushIsNotTakeover` | `pull_request synchronize` from the factory bot → `recorded_machine` | |
| `…aHandMergeDuringAFixHoldsTheRunAndAccountsItsLateResult` | (moved from slice 6) item in a land-fix `build` with a running run, `merged` → hold outbox row for the run, run effect refused, `completed/landed`; later build result → `late_result_accounted`, not 409 | projection or late path missing |
| `…aLateVerifyResultIsAccountedNotFailed` | (moved from slice 6) verify result after landing → wall seconds added via `recordLateResult`, row not left `item_not_active` | verify branch missing |
| `…aCloseDuringAFixHoldsTheRun` | `closed` during a land-fix build → hold row, `stopped/pr_closed_by_human` | `PULL_REQUEST_CLOSED` missing from the hold set |
| `…readmitIsRefusedWhileALandFixIsInProgress` | land-fix build stopped at `build/awaiting_input` (assembly refusal) → readmit 409 `readmission_land_pending`, generation unchanged; `LandView.fixInProgress=true` | refusal missing |
| `…aFailedFixRunIsHandedNotFailed` | land-fix build result unsuccessful → `land/awaiting_input/land_fix_run_failed`, usage accounted, Readmit refused | generic `phase_failed` kept |
| `…aFailedBuildOutsideAFixStillFails` | first build fails → `failed/phase_failed` | hand-off too broad |
| `…aLandFixRunGoesThroughTheBuildsSpendChecks` | API-key credential with incomplete pricing (or a fleet cap reached) → the same refusal reason an ordinary build gets, no `ExecuteWorkRun`; seat credential → run dispatched at cost 0 (§6 attempt-cost row, §5) | land-fix path bypasses `WorkRunAssembly` checks |
| `…landHandedRefusesAFixReasonAfterThisSlice` (contract) | `landHanded(item, "land_checks_failed")` → IAE | the three fix reasons left in the hand-off set |
| `WorkItems.test (actions): no Readmit while a land fix is in progress` | `awaiting_input` at build with `land.fixInProgress` → button absent | UI exclusion missing |

- [ ] RED (decider methods present but returning the unchanged item; `landFix` delegates to `landHandOff`), then
  GREEN. Commits: `Add failing tests for the land fix loop`, `Fix refused merges on the same pull request`.

**Mutation guards:** `landFix` in the build branch and in the deliver branch of `enter` (each alone);
`startKeepingExecution` choice; terminator set; `LAND_FIX_COUNTED` membership; the `>` budget comparison; the
item-limit check order; the land-fix condition in `validExecution`; the blocked-review land-fix condition;
`expectedRemoteHead` set in `WorkDelivery`; `open` skipped only during a land fix; the two new milestones in the
projection's hold/refuse sets; verify branch of `recordLateResult`; readmit refusal while a fix is in progress;
the land-fix branch of the failed-build path.

---

### Slice 8: Retry land and the dashboard

**Goal:** a handed item offers Retry land (verified operator, expected revision, note, audited) which opens a
fresh land gate on `execution.head()`, and Stop (same contract) which ends it; "Recheck and resume" is refused
there; the journey, gate panel and attention show land as it happens.

**Files:**
- Modify: `WorkItemLifecycle` (`retryLandGate(item, previous, historySize, now, decisionId)` → land gate,
  `waiting_approval/approval_required`, milestone `LAND_RETRY_REQUESTED`, artifact `execution.head()`)
- Modify: `WorkItemControl` (`retryLand(id, expected, subject, note)`: reads the PR head through the lander;
  unreachable → 503 `retry_land_head_unavailable`)
- Modify: `WorkItemTransitions` (`retryLandControlled(...)` under the lock: requires `land/awaiting_input` with a
  hand-off reason else 409 `retry_land_unavailable`; PR head ≠ `execution.head()` → 409 `land_head_moved`;
  `advance` without readmit at `land/awaiting_input` + hand-off reason → 409 `retry_land_required`)
- Modify: `WorkItemResource` (`POST /api/work-items/{id}/retry-land` and `POST /api/work-items/{id}/stop-land`,
  both `{expectedRevision, note}`, `spire-admin`, subject from OIDC)
- Stop (§7.6 "Retry land or Stop"; a handed item has no open gate, so `DecisionPanel`'s Stop is absent there):
  `WorkItemLifecycle.landStopped(item)` → `stopped/land_stopped_by_operator`, milestone `LAND_STOPPED`;
  `WorkItemControl.stopLand(id, expected, subject, note)` → `WorkItemTransitions.stopLandControlled` under the
  lock, requiring `land/awaiting_input` with a hand-off reason (else 409 `retry_land_unavailable`), recording
  operator and note. The pull request stays open; nothing is merged or deleted. Readmit afterwards follows the
  ordinary stopped-item rule (allowed), which the UI copy says.
- Modify: `WorkAttentionRows` (`WORK_LAND_HANDED` for `phase='land' AND workflow_status='awaiting_input'` with a
  hand-off reason)
- Modify UI: `spire-ui/src/api.ts` (`retryLand`, `stopLand`), `WorkItemActions.tsx` (Retry land and Stop buttons
  sharing one required note; Recheck hidden at land/awaiting_input), `workJourney.ts` / `WorkItemSteps.tsx` (land step text per §7.10 from `land`),
  `DecisionPanel.tsx` (land gate preview: method, ticket rule, branch delete, check summary), `workReasons.ts`
  (`retry_land_required`, `retry_land_unavailable`, `retry_land_head_unavailable`, `land_stopped_by_operator`, every hand-off sentence naming
  what GitHub said, e.g. `land_blocked`: "GitHub requires something only a person can give: …")
- Tests: `spire-contract/.../WorkLandDecisionTest.java` (+ retry rows),
  `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkLandRetryIT.java` (new),
  `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkAttentionTest.java`,
  `spire-ui/src/components/work-items/WorkItems.test.tsx`, `workJourney.test.ts`, `DecisionPanel.test.tsx`,
  `workReasons.test.ts`

**Interfaces — Produces:**
```java
public static WorkItemEvent retryLandGate(WorkItemEvent item, WorkItemEvent previous, long historySize, Instant now, UUID decisionId);
public WorkItemTransitions.Outcome retryLand(String id, long expected, String subject, String note);   // WorkItemControl
```
```ts
export async function retryLand(item: WorkItemSummary, note: string): Promise<WorkItemOutcome>;
export interface LandView { state: string; reason: string | null; message: string | null; checks: { state: string; done: number; total: number } | null;
  method: string; ticketRule: string; branchDeleted: boolean | null; note: string | null; attempt?: number; attemptsAllowed?: number;
  fixInProgress?: boolean }
export async function stopLand(item: WorkItemSummary, note: string): Promise<WorkItemOutcome>;
```

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `WorkLandDecisionTest.retryLandOpensAGateOnTheVerifiedHead` | artifact = `execution.head()`, `LAND_RETRY_REQUESTED`, new gate id | |
| `WorkLandRetryIT.retryLandNeedsANoteAndTheRevision` | blank note → 400; stale revision → 409 `work_item_changed` | |
| `…retryLandOpensAFreshGateThenLandsAfterApproval` | handed `land_blocked` → retry → gate OPEN; approve → land effect from `checking` | |
| `…retryLandRefusesAMovedPullRequestHead` | PR head differs → 409 `land_head_moved`, no gate (A3) | gate opened on the PR head |
| `…anEarlierApprovedGateIsNeverReused` | after retry, the old gate's answer key → `gate_changed`; no merge | |
| `…recheckIsRefusedWhileHanded` | resume without readmit → 409 `retry_land_required` | refusal missing |
| `…retryLandIsAudited` | control `operator`/`note` recorded on the event | |
| `…retryOutsideAHandOffIsRefused` | at review → 409 `retry_land_unavailable` | |
| `…theRetryGateBindsTheCurrentPolicyRevision` | policy revised while handed → retry gate's `policyRevision` = the new one; approving it lands (not `policy_changed_requires_new_decision`) | gate built from the stale revision |
| `…stopLandEndsAHandedItemWithANote` | handed `land_blocked` → stop-land with note → `stopped/land_stopped_by_operator`, `LAND_STOPPED`, operator and note on the event, no `PUT`/`DELETE`, PR untouched; blank note → 400 | Stop missing or merges |
| `…stopLandOutsideAHandOffIsRefused` | at review → 409 `retry_land_unavailable` | state check removed |
| `WorkItems.test (actions): handed item offers Stop with the note` | | |
| `WorkAttentionTest.aHandedItemNeedsTheOperator` | one `WORK_LAND_HANDED` row | |
| `WorkItems.test (actions): handed item shows Retry land and not Recheck` | | |
| `workJourney.test: land step reads checking, waiting n of m, merging, merged by who, fixing k of N, needs you` | each `LandView` → text | |
| `DecisionPanel.test: the land gate says what will happen` | method, rule, branch delete, check summary visible | |
| `workReasons.test: every land reason has a sentence` | iterate the exported land reason list; none falls through to the raw reason | a reason missing |

- [ ] RED, then GREEN. Commits: `Add failing tests for Retry land and the land journey`,
  `Add Retry land and show land on the item page`.

**Mutation guards:** the head comparison in `retryLandControlled`; hand-off-reason requirement (retry and stop);
`retry_land_required` in `advance`; gate artifact source; gate policy revision source; attention predicate; Recheck
hidden condition.

---

### Slice 9: documents

**Goal:** the docs say what the code now does, and what no test proves.

**Files:**
- `docs/DECISIONS.md` — ADR-048 (land merges through a durable effect pinned to the approved head; code refusals
  loop through build, verify, deliver, review and a new gate; a hand merge completes the item; the ticket is told,
  not closed, by default).
- `docs/CONTRACT.md` — new milestones, `WorkLanding`, `RepositoryActivity.mergedBy`, permit `expectedRemoteHead`,
  `PullRequestLander`.
- `docs/DATA-MODEL.md` — `repository_land_settings`, `work_land_effect`, `work_tracker_outbox.effect_class`.
- `docs/factory/AUTONOMY.md` land row and "merged, or handed to a human"; `docs/factory/ROADMAP.md` M4 paragraph;
  `docs/ROADMAP.md`.
- `docs/UNVERIFIED.md` — remove the "Readmit after an approved land" entry (line ~183) only with slice 3's test
  named as the evidence; add entries for: hand merge without a webhook noticed only by the effect's read;
  merge-queue repositories (handed, never proven live); fork pull requests; revocation racing a running push (A6);
  GitHub `has_hooks` and `unstable` paths proven against fakes only; a base that moves between the land read and
  the fix run's clone (the base-merge exclusion then fails closed, slice 7a); Jira `close` skipped
  (`ticket_close_unsupported`).
- `CLAUDE.md` Status and Limits — rewrite (never append) only at acceptance; until then leave it.
- No test changes. Commit: `Document the land slice` (body lists the files).

Acceptance (§9) runs live on `spire-test` after slice 9 with TEST-labelled tickets and writes
`docs/factory/LAND-ACCEPTANCE.md` and the `docs/HISTORY.md` entry; that is outside this plan's CI loop.

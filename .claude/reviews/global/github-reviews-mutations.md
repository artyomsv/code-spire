# GitHub reviews — mutation checks (PR #196)

Procedure (plan `docs/superpowers/plans/2026-10-15-github-reviews.md`, Global Constraints): each mutant
breaks one guard line on a scratch branch off the feature branch, is pushed as a draft PR against
`feat/github-reviews`, and exactly one named test must fail in CI. No local builds. 26 mutants ran on
2026-10-15/16; the draft PRs (#197–#222) are closed and their branches deleted.

Three survived on the first cut and were turned into tests before the re-cut: `t1-a` and `t1-b`
(`PostCommentsWireCompatibilityTest.withSuppressedCountKeepsTheVerdictFields`,
`.theLegacyConstructorLeavesEveryVerdictFieldNull`, commit `8040b8b6`) and `t10-b`
(`RepositoryRegistryPage.test.tsx` "can register an unbound repository before accounts exist",
commit `710e31e6`). The rows below show the re-cut runs for those three. Five mutants fail more than one
test; every extra failure is a named consequence of the same guard in the same module.

| Task | Guard line | Mutant (PR) | Failing test(s) | Run |
|---|---|---|---|---|
| 1 | `PostComments.withSuppressedCount` keeps `assessment` | passes `null` for assessment (#197) | `PostCommentsWireCompatibilityTest.withSuppressedCountKeepsTheVerdictFields` | 38815804079 |
| 1 | 9-arg `PostComments` constructor leaves the verdict null | passes `COMMENT` for verdict (#198) | `PostCommentsWireCompatibilityTest.theLegacyConstructorLeavesEveryVerdictFieldNull` | 38815854911 |
| 2 | `GitHubReviewApi.matches` prefers `start_line` | always reads `line` (#199) | `GitHubReviewSinkTest.postsAnApprovingReviewWithInlineCommentsInOneCall` | 38812935137 |
| 2 | `classify`: a body-only 422 is a plain failure | every 422 → `ReviewAnchorRejected` (#200) | `GitHubReviewSinkTest.aBodyOnly422IsAPlainFailure` | 38812945732 |
| 2 | `dismissChangesRequested` ignores COMMENTED reviews | drops the `STATE_REVIEWS` filter (#201) | `GitHubReviewSinkTest.dismissesAStandingChangesRequested` | 38812957122 |
| 2 | `find` matches the head commit | drops the `commit_id` test (#202) | `GitHubReviewSinkTest.findReviewIsEmptyWhenTheBotHasNotPostedForThisHead` | 38812965420 |
| 3 | `reviewTranscript` keeps only comments after `submitted_at` | includes every comment (#203) | `GitHubReviewThreadTest.fetchesAReviewRefAsBodyThenLaterComments` | 38812980424 |
| 3 | `requestReviewers` maps 422 to `reviewer_not_collaborator` | rethrows the 422 (#204) | `WorkReviewIT.aRefusedRequestIsAReceiptNotAWait`, `GitHubPullRequestSinkTest.aNonCollaboratorRequestIsDeliveryUnavailable` | 38813020138 |
| 4 | `NativeReviewPost` looks for an existing review before posting | skips `findReview` (#205) | `ReviewWorkerTest.aCrashAfterThePostDoesNotPostASecondReview` | 38813005263 |
| 4 | `recoverPreviouslyPosted` excludes `REVIEW` | drops the exclusion (#206) | `ReviewWorkerTest.aRedeliveryAfterTheReviewWasPostedDoesNotPostTwice`, `ReviewWorkerTest.aReviewRefIsNotAFindingThreadOnRedelivery` | 38813018862 |
| 4 | a refusal is retried once | never sets `refused` (#207) | `ReviewWorkerTest.aSecondRefusalIsAFailure` | 38813034530 |
| 4 | dismissal only when the setting is off | condition inverted (#208) | `ReviewWorkerTest.aDegradedRunWithTheSettingOnHasNoVerdictLine`, `ReviewWorkerTest.aRejectedAnchorFallsBackToBodyThenInlineThenBodyUpdate`, `ReviewWorkerTest.theSettingOffDismissesAStandingHoldAfterThePost`, `ReviewWorkerTest.theSettingOnDoesNotDismiss` | 38813038949 |
| 4 | the verdict line only when COMMENT replaced an assessment | always adds the line (#209) | `ReviewWorkerTest.aDegradedRunWithTheSettingOnHasNoVerdictLine` | 38813052747 |
| 5 | `RepositoryRegistry.update` writes the setting | writes `true` (#210) | `RepositoryResourceTest.reviewerMayApproveDefaultsOnAndRoundTripsThroughUpdate` | 38813061918 |
| 5 | `normalize` defaults the setting to true | defaults to false (#211) | `RepositoryResourceTest.reviewerMayApproveDefaultsOnAndRoundTripsThroughUpdate` | 38813072485 |
| 6 | `recordPostedVerdict` commit guard | guard removed (#212) | `ReviewProjectionTest.aSupersededRunsVerdictIsNotRecorded` | 38813082502 |
| 6 | `openBlockers` runs `openCounts` | returns `blockerCount` (#213) | `ReviewProjectionTest.openBlockersMatchesDetailOpenBlockers` | 38813092557 |
| 6 | `toDetail` carries `postedVerdict` | passes `null` (#214) | `ReviewProjectionTest.postedVerdictAndReviewUrlSerializeOnBothRecords`, `ReviewProjectionTest.recordPostedVerdictIsReadBackBySummaryAndDetail` | 38813103097 |
| 7 | `decide`: no identity → COMMENT | identity branch removed (#215) | `ResultSagaVerdictTest.aReviewerWithoutIdentityNeverGetsAnApprove`, `ReviewVerdictDecisionTest.aReviewerWithoutIdentityGivesComment` | 38813113566 |
| 7 | `decide`: unknown repository → COMMENT | empty setting treated as on (#216) | `ReviewVerdictDecisionTest.anUnknownRepositoryGivesComment` | 38813123312 |
| 7 | saga records a verdict only when present | records unconditionally (#217) | `ResultSagaRetryTest.commentsPosted_keysOwnershipByTheThreadNotTheComment`, `ResultSagaVerdictTest.commentsPostedWithoutAVerdictRecordsNothing` | 38813133362 |
| 8 | the receipt is the once-guard | `received` check removed (#218) | `WorkReviewIT.aReadyPullRequestRequestsTheReviewerOnce`, `WorkReviewIT.aRefusedRequestIsAReceiptNotAWait` | 38813143536 |
| 8 | a row at another head does not suppress the request | head test removed (#219) | `WorkReviewIT.aRowAtAnotherHeadDoesNotSuppressTheRequest` | 38813152276 |
| 8 | a running review still gets the request | status test removed (#220) | `WorkReviewIT.aRunningReviewOfThisHeadStillGetsTheRequest` | 38813162697 |
| 10 | `postedAs` renders when a verdict is set | always null (#221) | `ReviewDetail.posted.test.tsx: says how the review was posted and links to it` | 38813226009 |
| 10 | the form defaults the setting to on | defaults to false (#222) | `can register an unbound repository before accounts exist` | 38816844500 |

## Live-proof fixes (2026-10-16/17), mutants #223–#228 off `e7b6f92a`

| Guard line | Mutant (PR) | Failing test | Run |
|---|---|---|---|
| `NativeReviewPost.withRunMarker` writes the run token on the header line | never writes it (#223) | `ReviewWorkerTest.aReRunPostsANewReviewBesideTheEarlierRunsReview` | 38978814303 |
| `PostComments.withVerdict` keeps `runToken` | passes `null` (#224) | `PostCommentsWireCompatibilityTest.withRunTokenSurvivesTheOtherWithers` | 38978820575 |
| `markRerunStarted` writes a fresh `run_token` | keeps the old one (#225) | `ReviewProjectionTest.aClaimedRunHasARunTokenAndARerunGetsANewOne` | 38978827007 |
| `ResultSaga` sends the run token | sends `null` (#226) | `ResultSagaVerdictTest.theCommandCarriesTheRunToken` | 38978836396 |
| a claimed run writes a fresh `run_token` | claim keeps the old one (#227) | `ReviewProjectionTest.aClaimedRunHasARunTokenAndARerunGetsANewOne` | 38978842145 |
| `readBack` keeps this review's rows only | drops the `pull_request_review_id` filter (#228) | `GitHubReviewSinkTest.readsBackFromThePullRequestCommentsNotTheReviewComments` | 38978849386 |

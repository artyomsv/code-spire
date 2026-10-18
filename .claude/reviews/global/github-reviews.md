# GitHub reviews — branch review state (PR #196)

Base `2757caa3` (master), branch `feat/github-reviews`. Spec `docs/superpowers/specs/2026-10-15-github-reviews-design.md`,
plan `docs/superpowers/plans/2026-10-15-github-reviews.md`.

## Rounds

1. Whole-branch review of `158cba16` (2026-10-16): 0 Critical, 3 Important fixed, 7 Minor deferred.
2. Live proof on spire-test (2026-10-16/18): L-1 and L-2 fixed.
3. The PR's own review of `c30517b4` (2026-10-18): 4 findings fixed, and the deferred minors M-4..M-9 fixed on the operator's word (M-10 documented).

## Round 1 — whole-branch review of `158cba16` (Fable, 2026-10-16), resolved in `8040b8b6`, `710e31e6`

| Finding | Resolution |
|---|---|
| I-1 a new push kept the old head's "Posted as" line | Resolved: `registerHeader` clears the three posted columns; `aNewHeadClearsThePostedVerdict` |
| I-2 mutation checks not run | Resolved: 26 mutants, `github-reviews-mutations.md`; 3 survivors became tests |
| I-3a a crash after a body-only review lost the inline fallback | Resolved: `postedWithoutItsComments` → inline fallback; `aCrashAfterABodyOnlyPostFallsBackToInline` |
| I-3b a comment whose line GitHub nulled was unmatched | Resolved: `original_line` / `original_start_line` fallback; `aCommentWhoseLineMovedIsMatchedOnItsOriginalLine` |
| M-4 redelivery with an unknown bot login emits a null posted verdict | Open, Minor |
| M-5 a transient fault on the reviewer request is a permanent receipt | Open, Minor (spec D9 says fault → receipt) |
| M-6 every 422 on requested_reviewers is named not-a-collaborator | Open, Minor |
| M-7 reviewUrl rendered without a scheme check | Open, Minor (value comes from the forge API) |
| M-8 repository_unknown replaces open_blockers=N in the timeline reason | Open, Minor |
| M-9 fully qualified java.time.Instant in reviewTranscript | Open, Minor |
| M-10 a legacy summary issue comment stays stale after the switch (D2) | Open, Minor (docs) |

CI on `710e31e6`: all checks green (run 38816833062).

## Round 2 — live proof on spire-test (2026-10-16)

| Finding | Outcome |
|---|---|
| L-1 `GET /pulls/{n}/reviews/{id}/comments` answers in the legacy shape (`path`, `position`; `line`/`side`/`start_line` null), so the read-back matched nothing: PR 53 carried its inline comment on GitHub while the body said "Could not be posted inline" and `CommentsPosted` had 0 inline | Resolved: read back from `GET /pulls/{n}/comments` filtered by `pull_request_review_id`; `readsBackFromThePullRequestCommentsNotTheReviewComments` RED (run 38957479102) → GREEN |
| L-2 a re-run on the same head reused the earlier run's review: the dashboard re-run clears the claims, the look-first matched the old Request changes by the bare header, posted nothing and dismissed that review as superseded by itself (PR 55) | Resolved: `review_status.run_token` (V89) per claimed run / re-run, `PostComments.runToken`, marker `<!-- code-spire-run:… -->` on the header line, look-first matches it; `aReRunPostsANewReviewBesideTheEarlierRunsReview`, `theCommandCarriesTheRunToken`, `aClaimedRunHasARunTokenAndARerunGetsANewOne`, `aRetryAndARefreshKeepTheRunToken`, `withRunTokenSurvivesTheOtherWithers` RED (run 38977373316, compile) → GREEN |

## Round 3 — the PR's own review of `c30517b4` (2026-10-18), plus the deferred minors on the operator's word

The review tool on PR #196 posted 3 HIGH, 1 MEDIUM. All four were confirmed in the code before any fix.
The operator then asked for the branch review's minors M-4 to M-10 to be fixed in the same round.
RED commit `35f11bff` (run 39162222729); GREEN follows in the commits after it.

| Finding | Outcome |
|---|---|
| security/H-1 a reviewer or factory registration rotated to a new machine account after the build claim could approve the human land gate: `WorkControl.machine` knows only the recorded ids, and the bot can now Approve | Fixed: `WorkGateChannels.approval` also rejects the repository's CURRENT reviewer and factory bot ids (`currentMachine`); `aRotatedReviewerIdentityCannotApproveTheLandGate`, `aRotatedFactoryIdentityCannotApproveTheLandGate` RED → GREEN |
| code-quality/M-1 two findings at one anchor were posted as two comments, but the read-back (`putIfAbsent` per anchor key) and `settleInline` track one thread, so the second comment is orphaned | Fixed: one combined comment per anchor (`FINDING_SEPARATOR`), also on the one-by-one fallback; `twoFindingsAtOneAnchorPostOneCombinedComment`, `theInlineFallbackCarriesEveryFindingAtTheAnchor` RED → GREEN; plan Review Focus item 2 corrected |
| rules/H-1 `Optional.get()` after a guard in `NativeReviewPost` (4), `ReviewVerdictDecision` (1) and `ReviewWorker` (1) | Fixed: `orElseThrow()`; pure refactor under the existing tests |
| rules/H-2 `NativeReviewPost.run` 47 lines, `postWithFallbacks` 33, `settleInline` 5 parameters, constructor 7 | Fixed: a `Plan` record for the per-run state, `Posted` for the forge's answer, `Ports`/`Rendering` records for the constructor (3 parameters), the ladder moved to `ReviewPostLadder` (3-parameter constructor, rung steps as methods); every method ≤ 30 lines; pure refactor under the existing tests and the six run-token mutants' tests |
| M-4 a redelivery whose look-up finds nothing reported `postedVerdict` null | Fixed: the verdict this command posted (`postedVerdict`); `aRedeliveryThatCannotFindItsReviewStillReportsTheVerdict` RED → GREEN |
| M-5 a fault on the reviewer request became a permanent receipt | Fixed: no receipt on a fault, the next pass asks again (`WorkReview.requestReviewer` returns null); `aFaultedRequestIsTriedAgainOnTheNextPass` RED → GREEN. Ruling: spec D9's "fault → receipt" is amended; a refusal is still a receipt |
| M-6 every 422 on `requested_reviewers` was named `reviewer_not_collaborator` | Fixed: only a 422 whose message names collaborators; otherwise `reviewer_request_unprocessable`; `anotherUnprocessableRefusalIsNotCalledNotACollaborator` RED → GREEN |
| M-7 `reviewUrl` rendered as a link without a scheme check | Fixed: `isWebAddress` (http/https only); `does not link a review URL that is not a web address` RED → GREEN |
| M-8 `repository_unknown` / `setting_off` replaced `open_blockers=N` in the timeline reason | Fixed: `<why> · <hold>` (`open_blockers=2 · repository_unknown`); `ReviewVerdictDecisionTest` three assertions RED → GREEN |
| M-9 fully qualified `java.time.Instant` in `GitHubCommentSink.reviewTranscript` | Fixed: imported |
| M-10 a legacy summary issue comment stays stale beside the new review | Documented in the UNVERIFIED row and the acceptance record's limits; no code change (editing an old comment the new path does not own was judged riskier than the stale text) |

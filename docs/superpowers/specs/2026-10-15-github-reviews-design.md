# Real GitHub reviews

**Status:** design, 2026-10-15. Agreed with the operator in conversation, one decision at a time (§9).
**Origin:** during the M4 verify live proof (PR #184, `spire-test` #43/#44, 2026-10-08) the operator
said: "I would prefer to see real code reviews not some fictional messages." A summary comment leaves
GitHub's sidebar at "No reviews", so nobody can see from GitHub that a PR was reviewed, or approved.
**Follows:** [M4 verify](2026-10-07-factory-m4-verify-design.md), accepted 2026-10-08 in
[M4-VERIFY-ACCEPTANCE](../../factory/M4-VERIFY-ACCEPTANCE.md). Own branch `feat/github-reviews`, own PR.

## 1. Goal and exit

On GitHub, the reviewer posts a **pull request review**, not an issue comment. The review carries a
verdict — **Approve**, **Request changes** or **Comment** — the summary as its body, and every inline
finding inside the same review. On factory pull requests the reviewer bot is **requested as a reviewer**.
A per-repository setting, **"Reviewer may approve"** (default on), lets an operator keep the bot from
ever changing a PR's merge state.

**Exit criterion.** On `spire-test`:

| Case | Must show on GitHub |
|---|---|
| 1 | a factory PR with no blocker: the bot is listed under "Reviewers" as requested, then **Approved**; the inline findings (if any) sit inside that review |
| 2 | a PR with a BLOCKER finding: **Changes requested** by the bot; the dashboard work item waits with `review_blockers_open` at the same moment |
| 3 | the repository setting off: a **Comment** review whose body says the verdict in words, and the merge box is untouched |

The dashboard review detail shows "Posted as: Approved / Changes requested / Comment" with a link to
the review for all three. The record goes in `docs/factory/GITHUB-REVIEWS-ACCEPTANCE.md`.

**Not in this slice.**
- GitLab and Bitbucket reviews or approvals. They keep today's behaviour: summary issue comment edited
  in place, one inline comment per finding. A later slice adds GitLab approvals and Bitbucket approve.
- Posting anything for a **failed** or **refused** review. Nothing is posted today and nothing changes.
- Any change to the land gate. A bot approval still cannot resolve it (`WorkGateChannels.approval`
  requires `review.human()`); only `openBlockers` from the projection holds an item in review.
- Production LAND. It remains unavailable.

## 2. Decisions

| # | Decision | Why |
|---|---|---|
| D1 | **Request changes** means at least one **open BLOCKER**. | The land gate already waits on `ReviewDetail.openBlockers()` (`WorkReview.advance`, `review_blockers_open`). One rule means GitHub and the dashboard never disagree. MAJOR findings do not block, on GitHub or in the dashboard. |
| D2 | **Every run posts a new review.** No summary issue comment any more on GitHub. | GitHub lets nobody change a review's verdict after it is posted. A new review from the same account is what GitHub reads as that reviewer's current state: a later Approve clears an earlier Request changes by itself. |
| D3 | **Setting off = the bot never changes merge state.** Every outcome is posted as a **Comment** review; the body says the verdict in words. | A half-measure (Comment for Approve, but still Request changes) would let the bot hold a PR it may never release. The operator chose the plain rule. |
| D4 | **The orchestrator decides the verdict; the worker posts it.** | The worker holds only this run's findings and the verdicts on prior threads. It does not know a prior finding's severity, nor findings people filed with `/finding`. The projection's `openCounts` knows all three and is what the land gate reads. The saga writes the reconciliation and open findings *before* it emits `PostComments` (`ResultSaga.onReviewGenerated`), so the count is already right at that moment. |
| D5 | **Degraded posts a Comment.** | A degraded review has zero or partial findings. It must never read as an approval. |
| D6 | **A refusal from GitHub for self-authored PRs posts the same review as a Comment** with the verdict in words. | GitHub refuses APPROVE and REQUEST_CHANGES on one's own PR (422). The reviewer and factory accounts must differ (`RepositoryBindings.validate`), so factory PRs are safe; a human PR opened with the reviewer account is not. The review is still posted, so the words carry the verdict, as the branch reviewer bot already does on this repository. |
| D7 | **One API call posts body, verdict and inline comments.** A rejected anchor falls back: body only, then inline comments one by one as today, then the body is updated with the ones that still failed. | GitHub rejects the whole review when one comment's anchor is not in the diff. Anchors come from the diff fetched at the reviewed head, so this is rare, but the fallback keeps today's guarantee that one bad finding never loses the rest. GitHub allows a body update on a review (`PUT /pulls/{n}/reviews/{id}`), not a verdict change. |
| D8 | **The summary thread ref becomes `review:<id>`.** | The dashboard conversation and ADR-019 reconciliation key on the summary ref. A review has a body but no reply thread on GitHub: a reply to it posts a top-level PR comment (what `replyInThread` already does when the root is not a review comment), and reading it gives the body with no replies. |
| D9 | **The factory requests the reviewer once, right after the PR is marked ready and before any review exists.** A refusal is a note, not a wait. | Requesting before the draft is ready would start a review GitHub then re-requests on every push; requesting after the bot reviewed would show "re-requested" noise. A bot that is not a collaborator cannot be requested (422); that is a repository setup fault, and the review still happens because the gateway reacts to `ready_for_review`, not to the request. |
| D10 | **A null verdict on the wire means "post as today".** | Commands already in Kafka during a deploy must still be handled. The legacy path stays until GitLab and Bitbucket get their own slice. |

## 3. Contract — wire types and ports (`spire-contract`)

**New enum** `dev.codespire.contract.review.ReviewVerdict { APPROVE, REQUEST_CHANGES, COMMENT }`.

**`ActionCommand.PostComments`** gains one trailing component, `ReviewVerdict verdict` (nullable, D10),
with a wither `withVerdict(ReviewVerdict)` next to the record and a back-compat constructor that passes
`null`. Every rebuild site is enumerated in the plan; `ContractSchemaSnapshotTest`'s snapshot changes.

**`IntegrationEvent.CommentsPosted`** gains two trailing components: `ReviewVerdict postedVerdict`
(nullable) and `String reviewRef` (nullable; the forge's review id as a string). Same wither and
constructor rules. `summaryThreadRef` keeps its meaning and holds `review:<id>` on GitHub (D8).

**New record** `dev.codespire.contract.scm.ReviewSubmission(ReviewVerdict verdict, String bodyMd,
List<InlineComment> comments)` with `record InlineComment(InlineAnchor anchor, String bodyMd)`.

**New result record** `dev.codespire.contract.scm.PostedReview(String reviewRef, ReviewVerdict verdict,
List<CommentRef> inline)`. `verdict` is what was actually posted: COMMENT when GitHub refused the asked
verdict (D6).

**`CommentSink`** gains:

```java
/** Posts one forge-native review. The default is "this forge has none": the caller then
 *  uses postSummary/postInline as before. */
default Optional<PostedReview> postReview(RepoRef repo, long prId, String headCommit,
                                          ReviewSubmission review) {
    return Optional.empty();
}
/** Rewrites a posted review's body. Only a forge that returned a PostedReview supports it. */
default void updateReviewBody(RepoRef repo, long prId, String reviewRef, String bodyMd) {
    throw new UnsupportedOperationException("review body update");
}
```

A thrown `ReviewAnchorRejected` (new, in the port, carrying the forge message) tells the worker to use
the D7 fallback; a thrown `ReviewVerdictRefused` tells it to post again as COMMENT (D6). Both are
`RuntimeException`s raised only by the GitHub adapter from the 422 body text.

**`PullRequestSink`** gains:

```java
/** Asks the forge to list these accounts as requested reviewers. */
default void requestReviewers(RepoRef repo, PullRequestRef pr, List<String> logins) {
    throw new DeliveryUnavailable("request_reviewers_unsupported");
}
```

## 4. Orchestrator — decide, store, show

### 4.1 The decision (`ResultSaga.onReviewGenerated`)

After `recordOpenFindings` and before `emitWithCredential`:

```
verdict = visible.degraded()                      ? COMMENT
        : projection.openBlockers(reviewId) > 0  ? REQUEST_CHANGES
        : APPROVE
if (!repositories.reviewerMayApprove(repositoryId)) verdict = COMMENT   // off, or repository unknown
```

- `ReviewProjection.openBlockers(reviewId)` is a new public method that runs the existing private
  `openCounts(row)` for that one row and returns `blockers`. Same code, same meaning as
  `ReviewDetail.openBlockers()`.
- `repositoryId` comes from `projection.repositoryIdOf(reviewId)` (exists). An empty id, or a
  repository row that is missing, gives COMMENT: the bot never changes merge state on a repository it
  cannot look up.
- The verdict is written to the timeline as `result · verdict:<APPROVE|REQUEST_CHANGES|COMMENT>` with
  the reason (`degraded`, `open_blockers=N`, `setting_off`, `repository_unknown`) so the operator can
  read why from the review's event list.

### 4.2 The setting

Migration `V88__reviewer_may_approve.sql`:

```sql
ALTER TABLE repository ADD COLUMN reviewer_may_approve BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE review_status ADD COLUMN posted_verdict TEXT;
ALTER TABLE review_status ADD COLUMN posted_review_ref TEXT;
```

- `RepositoryInput` gains `Boolean reviewerMayApprove` (null means true, like `enabled`);
  `RepositoryView` gains `boolean reviewerMayApprove`. `RepositoryRegistry` reads, writes and defaults
  it; `PUT /api/repositories/{id}?revision=` carries it. `RepositoryRegistry.reviewerMayApprove(UUID)`
  returns `Optional<Boolean>` for the saga (empty when the row is missing).
- UI: `RepositoryForm.tsx` gets a second `field-check` checkbox, "Reviewer may approve", under
  "Enabled". `api.ts` `Repository` and `RepositoryInput` gain `reviewerMayApprove`. The repository detail
  page shows "Reviewer may approve: on/off".

### 4.3 The projection

`ResultSaga.onCommentsPosted` already calls `projection.recordPosted(reviewId, commit, summaryThreadRef)`.
It additionally calls `projection.recordPostedVerdict(reviewId, postedVerdict, reviewRef)` when
`postedVerdict` is non-null. `ReviewSummary` and `ReviewDetail` gain `postedVerdict` (string slug:
`approved` / `changes_requested` / `commented`, or null) and `reviewUrl` (`htmlUrl +
"#pullrequestreview-" + reviewRef` on GitHub, else null). The TS `ReviewSummary` gains both fields in
the same change (CLAUDE.md gotcha: a field added to one side arrives `undefined`).

### 4.4 What the screens show

- **Review detail:** one line under the status, "Posted as **Approved**" / "**Changes requested**" /
  "**Comment**", linked to `reviewUrl`. Absent when `postedVerdict` is null (legacy or other forge).
- **Work item detail, review step:** the proof text gains the same words and link. Reason copy in
  `workReasons.ts` is unchanged: `review_blockers_open` already says what holds the item.
- **Repositories:** the checkbox (4.2).

## 5. Worker and GitHub adapter — post

### 5.1 `ReviewWorker.postComments`

Unchanged up to `actOnVerdicts`. Then, when `command.verdict() != null`, the worker tries the native
path first:

1. Resolve every finding's anchor against the diff as today (`Anchors.resolveLine`). Unanchored
   findings fold into the body as today.
2. Claim the idempotency keys **before** the call: `REVIEW` (new key) and every anchor key. A redelivery
   that finds `REVIEW` already claimed skips the post and rebuilds `CommentsPosted` from the claim map,
   as the summary path does today.
3. Build `ReviewSubmission(verdict, body, comments)`; the body is `renderSummary` as today, plus
   `**Verdict:** approve` / `**Verdict:** changes requested` on the first line after the header when the
   asked verdict was turned into COMMENT by the setting (D3) or by a refusal (D6). The header line
   `### Code Spire review (`<commit>`)` is kept: it is one of the six product-name literals and the
   dashboard's conversation opener.
4. `sink.postReview(...)`:
   - `Optional.empty()` → the forge has no native review: run today's path unchanged (inline posts,
     then `updateOrPost` of the summary). `postedVerdict` and `reviewRef` stay null in the event.
   - `ReviewVerdictRefused` → post again with COMMENT and the verdict line (D6). Once.
   - `ReviewAnchorRejected` → post the review with the body only (same verdict, no comments), then post
     each inline comment with `postInline` under the existing per-finding backoff and 500 ms throttle,
     then `updateReviewBody` with the "could not be posted inline" list (D7).
   - Any other failure → `ReviewFailed(... "post-comments" ...)` as today. Nothing is half-posted
     silently: a review that was created before a later step failed is found again on redelivery by
     the `REVIEW` claim.
5. Mark posted: `REVIEW` → `review:<id>`, each anchor key → its comment id. Emit `CommentsPosted` with
   `summaryThreadRef = "review:<id>"`, `inline`, `outcomes`, `postedVerdict`, `reviewRef`.

Orchestrator-side `priorSummaryRef` keeps flowing; on GitHub it now holds `review:<id>` and is not
used for an in-place update any more (D2). The `updateOrPost` method stays for the legacy path.

### 5.2 `GitHubCommentSink`

- `postReview` → `POST /repos/{full}/pulls/{n}/reviews` with
  `{commit_id, event: APPROVE|REQUEST_CHANGES|COMMENT, body, comments: [{path, line, side,
  start_line?, start_side?, body}]}`. Field mapping per comment is the one `postInline` uses today,
  without `commit_id` (it is top-level). Returns `PostedReview("<review id>", verdict, inline refs)`;
  the inline refs are read back from `GET /pulls/{n}/reviews/{id}/comments` so each finding gets its
  real comment id for the idempotency map and for `/finding` threads.
- A 422 whose message contains `own pull request` → `ReviewVerdictRefused`. A 422 whose message names
  a `path`, `line`, `position` or `start_line` → `ReviewAnchorRejected`. Any other 422 → the plain
  `GitHubApiException`.
- `updateReviewBody` → `PUT /pulls/{n}/reviews/{id}` with `{body}`. `GitHubClient` gains `putJson`.
- `replyInThread` with a `review:` ref → top-level issue comment (the existing 404 fallback, taken
  directly without the failing call). `fetchThread` with a `review:` ref → `GET /pulls/{n}/reviews/{id}`,
  one `ThreadMessage` from the body, no replies. `updateComment` with a `review:` ref →
  `UnsupportedOperationException` (never reached on the native path).

### 5.3 Gateway

No change. `GitHubIngress.pullRequest` ignores `review_requested` (default arm), and
`pull_request_review` from the bot is activity by a machine actor, recorded as `recorded_machine`
and never a review trigger. Both facts get a test each (§8) so a later change cannot make the bot's own
review start a second review.

## 6. Factory — ask for the review (`WorkReview`)

After `ensureMarkedReady` returns true and `observe` finds **no** `review_status` row for the PR:

```
ensureReviewerRequested(item, execution):
  key = attemptId + ":" + prNumber; once per process (same shape as markedReady)
  reviewer = repositoryAccounts.resolve(repositoryId, REVIEWER).map(ScmProvider::botUsername)
             // RepositoryAccounts, newly injected: MachineAccounts.resolve answers FACTORY only
  factorySink = clients.pullRequestSink(FACTORY provider)
  factorySink.requestReviewers(repo, pr, [reviewer])
  on DeliveryUnavailable or any fault: note on the item "reviewer_request_refused: <reason>",
  remember the key, continue — never a wait
```

GitHub: `POST /repos/{full}/pulls/{n}/requested_reviewers {reviewers: [login]}`. Requesting an account
already requested is a no-op on GitHub. A blank `bot_username` on the reviewer registration skips the
request with the note `reviewer_login_unknown`.

## 7. Documentation

- `docs/DECISIONS.md`: **ADR-047 — The reviewer posts a forge-native review where the forge has one**
  (D1–D10, the fallback ladder, why the orchestrator decides).
- `docs/SCM-MAPPING.md`: the posting section says GitHub posts a review with inline comments; GitLab
  and Bitbucket post a summary comment; the "External work answers" table notes that the bot's own
  review is machine activity and cannot answer a gate.
- `docs/UNVERIFIED.md`: new entries for the two fallbacks (self-authored refusal; rejected anchor) until
  a live case is seen, and for GitLab/Bitbucket native reviews (absent).
- `CLAUDE.md` status: the line "The review is posted as a comment, not as a GitHub review" goes away;
  the limits list says "GitLab and Bitbucket reviews are posted as comments".
- `docs/HISTORY.md`: the milestone entry after acceptance.
- `docs/factory/GITHUB-REVIEWS-ACCEPTANCE.md`: the live proof (§1).

## 8. Tests (written first, each watched failing on the defect line in CI)

**spire-contract**
- `ContractSchemaSnapshotTest` snapshot: `PostComments.verdict`, `CommentsPosted.postedVerdict`,
  `CommentsPosted.reviewRef`.
- Back-compat constructors deserialize an old payload with the new fields null.

**spire-scm-github (WireMock, `GitHubApiTest` style)**
- `postsAnApprovingReviewWithInlineCommentsInOneCall` — body has `event: APPROVE`, `commit_id`, and
  the comments array with `path/line/side`; multi-line anchors carry `start_line/start_side`.
- `postsRequestChanges`, `postsComment`.
- `aSelfAuthoredRefusalRaisesVerdictRefused` — 422 "Can not approve your own pull request".
- `aRejectedAnchorRaisesAnchorRejected` — 422 naming `line`.
- `readsBackTheInlineCommentIds` — the `reviews/{id}/comments` read populates the refs.
- `updatesAReviewBody` — PUT.
- `aReplyToAReviewRefPostsAnIssueComment`, `fetchesAReviewRefAsItsBody`.
- `requestsAReviewer` (`GitHubPullRequestSinkTest`) and `aNonCollaboratorRequestIsDeliveryUnavailable`.

**spire-review-worker (`ReviewWorkerTest`, `RecordingSink`)**
- `aVerdictPostsOneNativeReviewAndNoSummaryComment`.
- `aNullVerdictPostsAsBefore` (legacy path unchanged, asserts summary + inline calls).
- `aForgeWithoutNativeReviewsPostsAsBefore` (`postReview` returns empty).
- `aRefusedVerdictIsPostedAgainAsAComment` — exactly two `postReview` calls, second COMMENT, body has
  the verdict line.
- `aRejectedAnchorFallsBackToBodyThenInlineThenBodyUpdate` — order asserted.
- `aRedeliveryAfterTheReviewWasPostedDoesNotPostTwice` — `REVIEW` claim honoured.
- `theSettingOffVerdictLineIsInTheBody` — COMMENT with asked verdict APPROVE renders `**Verdict:** approve`.

**spire-orchestrator**
- `ResultSagaTest`: `degradedGivesComment`, `anOpenBlockerFromAPriorRoundGivesRequestChanges` (the
  blocker is only in `reconciliation_json`, still open), `aHandFiledBlockerGivesRequestChanges`,
  `noBlockersGiveApprove`, `theSettingOffGivesComment`, `anUnknownRepositoryGivesComment`.
- `ReviewProjectionTest`: `openBlockersMatchesDetailOpenBlockers` (same row, same number),
  `recordPostedVerdictIsReadBackBySummaryAndDetail`.
- `RepositoryRegistryTest`: default true, round trip, revision check.
- `WorkReviewIT`: `aReadyPullRequestRequestsTheReviewerOnce`, `aRefusedRequestIsANoteNotAWait`,
  `noRequestOnceAReviewExists`.
- Gateway/ingress: `reviewRequestedIsIgnored`; `WorkItemControl`: `theBotsOwnReviewIsRecordedMachine`.

**spire-ui**
- `ReviewDetail` renders "Posted as Approved" with the link; nothing when null.
- `RepositoryForm` sends `reviewerMayApprove` and defaults it true.
- `api.ts` type test for the two new `ReviewSummary` fields (`styles.contract`-style union check).

**Mutation checks** (CLAUDE.md gotcha): for each new guard, break the production line and confirm
exactly one test fails: the `openBlockers > 0` branch, the setting-off branch, the `REVIEW` claim,
the refusal retry count, the review-exists check before requesting a reviewer.

## 9. The decisions, in the order they were made

1. Request changes = at least one open BLOCKER (same as the land gate). *Not* BLOCKER-or-MAJOR.
2. Every run posts a new review; the summary comment goes away on GitHub. *Not* a comment plus a
   verdict-only review beside it.
3. Setting off = Comment for every outcome, verdict in words. *Not* "Approve becomes Comment, Request
   changes still blocks".
4. The orchestrator decides the verdict (D4). *Not* the worker.
5. The design sections 1–5 of the conversation (contract, orchestrator, worker/GitHub, factory,
   errors/docs/tests) were approved together.

## 10. Risks and what stays unverified after this slice

- **The self-authored and rejected-anchor fallbacks** are tested against WireMock only. Live proof
  needs a PR opened by the reviewer account and a review whose anchor GitHub rejects; neither is in the
  exit criterion. Both go to `UNVERIFIED.md`.
- **Branch protection.** Whether a bot Approve counts toward "required approvals" depends on the
  repository's rules (CODEOWNERS, "dismiss stale", "require review from someone other than the last
  pusher"). This design does not read or change protection rules.
- **Re-request on push.** GitHub re-requests reviewers it had requested when the branch is pushed if
  the repository has that setting. The factory does not push after delivery, so this is not expected;
  if seen, it is harmless noise.
- **A reviewer that is not a collaborator** cannot be requested; the note names it. The review still
  happens.

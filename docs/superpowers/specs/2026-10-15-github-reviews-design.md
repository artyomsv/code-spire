# Real GitHub reviews

**Status:** design, 2026-10-15. Agreed with the operator in conversation, one decision at a time (§9),
then checked against the code by an adversarial review on 2026-10-15 (19 findings, all applied; §9.6).
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
- Any change to the land gate. What stops the bot's own Approve from resolving it is
  `item.control().machine(review.actorId())` (`WorkGateChannels.approval`, L93–94): the reviewer's
  `bot_account_id` is recorded as `reviewerActor` at build claim (`WorkRunDispatcher` L94–101). The
  `review.human()` check beside it is `user.type == "User"` (`GitHubPullRequestApprovalSource` L37)
  and is **true** for a machine account that is an ordinary GitHub user; it is not the guard. A reviewer
  registration with a blank identity (`no-identity`, `RepositoryRegistry` L151) records null, so the
  guard would not fire. §4.1 therefore never asks for APPROVE on such a registration, and
  `WorkGateChannelsIT` gets the test `theBotsOwnApproveCannotResolveTheLandGate`.
- Production LAND. It remains unavailable.

## 2. Decisions

| # | Decision | Why |
|---|---|---|
| D1 | **Request changes** means at least one **open BLOCKER**. | The land gate already waits on `ReviewDetail.openBlockers()` (`WorkReview.advance`, `review_blockers_open`). One rule means GitHub and the dashboard never disagree. MAJOR findings do not block, on GitHub or in the dashboard. |
| D2 | **Every run posts a new review.** No summary issue comment any more on GitHub. | GitHub lets nobody change a review's verdict after it is posted. A reviewer's state on a PR is their latest **Approve or Request changes** review: a later Approve replaces an earlier Request changes. A **Comment** review replaces nothing (D11). |
| D3 | **Setting off = the bot never changes merge state.** Every outcome is posted as a **Comment** review; the body says the verdict in words. | A half-measure (Comment for Approve, but still Request changes) would let the bot hold a PR it may never release. The operator chose the plain rule. |
| D4 | **The orchestrator decides the verdict; the worker posts it.** The command carries three facts: the **assessment** (what the findings say), the **verdict** (what to post) and whether the reviewer **may approve** on this repository. | The worker holds only this run's findings and the verdicts on prior threads. It does not know a prior finding's severity, nor findings people filed with `/finding`. The projection's `openCounts` knows all three and is what the land gate reads. The saga writes the reconciliation and open findings *before* it emits `PostComments` (`ResultSaga.onReviewGenerated` L378–398), so the count is already right at that moment. The worker needs the assessment to write the verdict in words (case 3) and the setting to know when to release a hold (D11); a single verdict field cannot carry both. |
| D5 | **Degraded posts a Comment.** With the setting on, an earlier Request changes by the bot **stays**. | A degraded review has zero or partial findings. It must never read as an approval, and it must not release a hold it cannot re-judge. |
| D6 | **A refusal from GitHub for self-authored PRs posts the same review as a Comment** with the verdict in words. | GitHub refuses APPROVE and REQUEST_CHANGES on one's own PR (422). The reviewer and factory accounts must differ when both identities are resolved (`RepositoryBindings.validate`, L28, checks only a non-blank reviewer identity), so factory PRs are safe; a human PR opened with the reviewer account is not. The review is still posted, so the words carry the verdict. GitHub refused the bot's own Request changes on such a PR too, so no hold exists and nothing is dismissed. |
| D7 | **One API call posts body, verdict and inline comments.** A rejected anchor falls back: body only, then inline comments one by one as today, then the body is updated with the ones that still failed. | GitHub rejects the whole review when one comment's anchor is not in the diff. Anchors come from the diff fetched at the reviewed head, so this is rare, but the fallback keeps today's guarantee that one bad finding never loses the rest. GitHub allows a body update on a review (`PUT /pulls/{n}/reviews/{id}`), not a verdict change. |
| D8 | **The summary thread ref becomes `review:<id>`.** | The dashboard conversation and ADR-019 reconciliation key on the summary ref. A review has a body but no reply thread on GitHub: a reply to it posts a top-level PR comment (what `replyInThread` already does when the root is not a review comment). Reading it gives the body followed by the top-level comments posted after it (§5.2), so a follow-up still sees the question it answers. |
| D9 | **The factory requests the reviewer once per attempt and PR, right after the PR is marked ready and before the bot's review is posted.** A refusal is a receipt, not a wait. | Requesting before the draft is ready would start a review GitHub then re-requests on every push; requesting after the bot reviewed shows "re-requested" noise. A bot that is not a collaborator cannot be requested (422); that is a repository setup fault, and the review still happens because the gateway reacts to `ready_for_review`, not to the request. |
| D10 | **A null assessment on the wire means "post as today".** | Commands already in Kafka during a deploy must still be handled. The legacy path stays until GitLab and Bitbucket get their own slice. |
| D11 | **When the bot may not approve, it dismisses its own standing Request changes** after the Comment review is posted. | GitHub clears a reviewer's Request changes only by a later Approve or Request changes from that reviewer, or by a **dismissal** (`PUT /pulls/{n}/reviews/{id}/dismissals`). Without this, round 1 = Request changes, then the operator turns the setting off, leaves the PR held with no path to release: the outcome D3 exists to prevent. With the setting on and a degraded run, the hold stays (D5). |

## 3. Contract — wire types and ports (`spire-contract`)

**New enum** `dev.codespire.contract.review.ReviewVerdict { APPROVE, REQUEST_CHANGES, COMMENT }`.

**`ActionCommand.PostComments`** gains three trailing components, all nullable (D10):
`ReviewVerdict assessment` (what the findings say), `ReviewVerdict verdict` (what to post) and
`Boolean reviewerMayApprove`. `verdict` is always `reviewerMayApprove ? assessment : COMMENT`; the
orchestrator sets all three so the worker never applies the rule itself (D4). One wither
`withVerdict(assessment, verdict, reviewerMayApprove)` next to the record, and a back-compat
constructor that passes three nulls. Every rebuild site is enumerated in the plan;
`ContractSchemaSnapshotTest`'s golden file (`contract-schema.txt`, L4) changes. The snapshot does not
recurse into nested types (L152–160), which is why the three are flat components and not one record.

**`IntegrationEvent.CommentsPosted`** gains three trailing components: `ReviewVerdict postedVerdict`
(nullable), `String reviewRef` (nullable; the forge's review id as a string) and `String reviewUrl`
(nullable; the forge's own link to the review — *amended 2026-10-15 during execution:* the core never
composes a forge's URL, `CoreIsProviderNeutralTest` forbids the provider name it would need). Same
wither and constructor rules; golden file L41. `summaryThreadRef` keeps its meaning and holds
`review:<id>` on GitHub (D8).

**New record** `dev.codespire.contract.scm.ReviewSubmission(ReviewVerdict verdict, String bodyMd,
List<InlineComment> comments)` with `record InlineComment(InlineAnchor anchor, String bodyMd)`.

**New result record** `dev.codespire.contract.scm.PostedReview(String reviewRef, ReviewVerdict verdict,
Map<String, String> inlineThreads, String reviewUrl)` (*as built:* the inline refs are keyed by
`InlineAnchor.anchorKey()`, which is what the worker's idempotency map needs, and the link is the
forge's `html_url`). `verdict` is what was actually posted: COMMENT when GitHub refused the asked
verdict (D6).

**`CommentSink`** gains four defaults; a forge that overrides `postReview` overrides all four:

```java
/** The review this bot already posted for this head, if any: the recovery read before a post. */
default Optional<PostedReview> findReview(RepoRef repo, long prId, String headCommit, String bodyPrefix) {
    return Optional.empty();
}
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
/** Dismisses this bot's own standing "changes requested" review, if one stands (D11).
 *  Returns the dismissed review's ref. */
default Optional<String> dismissOwnChangesRequested(RepoRef repo, long prId, String message) {
    return Optional.empty();
}
```

A thrown `ReviewAnchorRejected` (new, in the port, carrying the forge message) tells the worker to use
the D7 fallback; a thrown `ReviewVerdictRefused` tells it to post again as COMMENT (D6). Both are
`RuntimeException`s raised only by the GitHub adapter from a 422 (§5.2).

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
assessment = visible.degraded()                      ? COMMENT
           : projection.openBlockers(reviewId) > 0  ? REQUEST_CHANGES
           : APPROVE
mayApprove = repository found
          && repositories.reviewerMayApprove(repositoryId)
          && reviewer registration has a bot identity        // ServingAccounts state "ok", not "no-identity"
verdict    = mayApprove ? assessment : COMMENT
```

- `ReviewProjection.openBlockers(reviewId)` is a new public method that runs the existing private
  `openCounts(row)` (L2127–2154) for that one row and returns `blockers`. Same code, same meaning as
  `ReviewDetail.openBlockers()` and the list row (L2088, L2308).
- `repositoryId` comes from `projection.repositoryIdOf(reviewId)` (L87–97, `Optional<UUID>`). An empty
  id, or a repository row that is missing, gives `mayApprove = false`: the bot never changes merge state
  on a repository it cannot look up.
- The reviewer identity check reads the REVIEWER registration through `RepositoryAccounts.resolve
  (repositoryId, REVIEWER)` and the same blank test `ServingAccounts` uses (`isBlank(botAccountId)`,
  `ProviderResource` L201). A blank identity means `WorkGateChannels` could not tell the bot's Approve
  from a human's (§1), so the bot must not approve.
- The decision is written to the timeline as `result · verdict:<APPROVE|REQUEST_CHANGES|COMMENT>` with
  the reason (`degraded`, `open_blockers=N`, `setting_off`, `repository_unknown`,
  `reviewer_identity_unknown`) so the operator can read why from the review's event list.

### 4.2 The setting

Migration `V88__reviewer_may_approve.sql`, opened with a why-comment like V80–V87:

```sql
-- The reviewer may post Approve / Request changes on GitHub (ADR-047). Off means every review is a
-- Comment and the bot never changes a PR's merge state. The posted verdict and the forge's review id
-- let the dashboard show "Posted as ..." with a link.
ALTER TABLE repository ADD COLUMN reviewer_may_approve BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE review_status ADD COLUMN posted_verdict TEXT
    CHECK (posted_verdict IN ('APPROVE', 'REQUEST_CHANGES', 'COMMENT'));
ALTER TABLE review_status ADD COLUMN posted_review_ref TEXT;
```

- `RepositoryInput` gains `Boolean reviewerMayApprove` (null means true, like `enabled`);
  `RepositoryView` gains `boolean reviewerMayApprove`. `RepositoryInput` is rebuilt positionally in
  `RepositoryRegistry.normalize` (L122–123) and its columns are listed by hand in `insert` (L105–114),
  `update` (L87–98) and `view` (L136–140): all four change, and the round-trip test asserts the stored
  value after `update`, not only after `create`. `PUT /api/repositories/{id}?revision=` carries it.
  `RepositoryRegistry.reviewerMayApprove(UUID)` returns `Optional<Boolean>` for the saga (empty when
  the row is missing).
- UI: `RepositoryForm.tsx` gets a second `field-check` checkbox, "Reviewer may approve", under
  "Enabled" (L80). `api.ts` `Repository` and `RepositoryInput` gain `reviewerMayApprove`. The
  repository detail page shows "Reviewer may approve: on/off".

### 4.3 The projection

`ResultSaga.onCommentsPosted` already calls `projection.recordPosted(reviewId, commit, summaryThreadRef)`
(L258), and `recordPosted` is guarded by `WHERE commit_sha = ?` (`ReviewProjection` L495–515) so a
superseded run cannot stamp a newer head. It additionally calls
`projection.recordPostedVerdict(reviewId, commit, postedVerdict, reviewRef)` when `postedVerdict` is
non-null, with the **same commit guard**. `ReviewSummary` and `ReviewDetail` (two independent Java
records, L2065–2071 and L2309–2315) gain `postedVerdict` (string slug: `approved` / `changes_requested`
/ `commented`, or null) and `reviewUrl` (the link the forge reported, stored in
`review_status.posted_review_url`; null when nothing native was posted — *amended 2026-10-15:* not
composed from `htmlUrl`, see §3). A Java test asserts both keys serialize on both records; the TS `ReviewSummary` gains both
fields in the same change (CLAUDE.md gotcha: a field added to one side arrives `undefined`).

### 4.4 What the screens show

- **Review detail:** one line under the status, "Posted as **Approved**" / "**Changes requested**" /
  "**Comment**", linked to `reviewUrl`. Absent when `postedVerdict` is null (legacy or other forge).
- **Work item detail, review step:** the proof text gains the same words and link. Reason copy in
  `workReasons.ts` is unchanged: `review_blockers_open` already says what holds the item.
- **Repositories:** the checkbox (4.2).

## 5. Worker and GitHub adapter — post

### 5.1 `ReviewWorker.postComments`

Unchanged up to `actOnVerdicts`. Then, when `command.assessment() != null`, the worker tries the
native path first:

1. Resolve every finding's anchor against the diff as today (`Anchors.resolveLine`). Unanchored
   findings fold into the body as today.
2. Build the body: `renderSummary` as today, plus one line right after the header when the posted
   verdict is COMMENT and the assessment is not: `**Verdict:** approve` or `**Verdict:** changes
   requested`. The D6 retry adds the same line from the asked verdict. The header line
   `### Code Spire review (`<commit>`)` is kept: it is one of the six product-name literals, the
   dashboard's conversation opener, and the prefix `findReview` matches on.
3. Claim the idempotency key **`REVIEW`** (new) and every anchor key before any call. The claim does
   not say whether it was fresh or reclaimed (`CommentIdempotencyStore.claim` L53–61 returns `Post`
   for both), so a `Post` on `REVIEW` first calls `sink.findReview(repo, pr, head, header)`:
   - found → the review exists from an attempt that died before it was marked; skip to step 6 with it.
   - empty → `sink.postReview(ReviewSubmission(verdict, body, comments))`.
4. Outcomes of `postReview`:
   - `Optional.empty()` → the forge has no native review: run today's path unchanged (inline posts,
     then `updateOrPost` of the summary). `postedVerdict` and `reviewRef` stay null in the event.
   - `ReviewVerdictRefused` → post again with COMMENT and the verdict line (D6). Once.
   - `ReviewAnchorRejected` → post the review with the body only (same verdict, no comments). Then
     continue at step 6 with `inlineFallback = true`.
   - Any other failure → `ReviewFailed(... "post-comments" ...)` as today. A review GitHub created
     before the failure is found by step 3 on redelivery, so nothing is posted twice.
5. **Mark `REVIEW` posted (`review:<id>`) at once**, before any read-back or fallback. Every step after
   this is a read, a per-anchor claimed post, or an idempotent `PUT`, so a crash anywhere later
   re-enters at step 3, finds the mark, and finishes the rest.
6. Inline refs: `findReview` / `postReview` return the comment refs read back from the review (§5.2).
   Mark each matched anchor key with its comment id. With `inlineFallback`, post each unmatched finding
   with `postInline` under the existing per-anchor claim, backoff and 500 ms throttle, then
   `updateReviewBody` with the "could not be posted inline" list (D7). A finding with no comment to
   match and no fallback is recorded as outcome `failed` and named in the same body update.
7. When `reviewerMayApprove == false`: `sink.dismissOwnChangesRequested(repo, pr, "Superseded by
   review <id>: the reviewer may not change merge state on this repository")` (D11). It reads the bot's
   latest Approve / Request changes review first, so a repeat is a no-op. A failure here is logged at
   WARN with the review ref and does not fail the run: the Comment review is already posted, and the
   next run tries again.
8. Emit `CommentsPosted` with `summaryThreadRef = "review:<id>"`, `inline`, `outcomes`,
   `postedVerdict`, `reviewRef`. The redelivery reconstruction (`ReviewWorker` L584–592) rebuilds
   `inline` from every completed claim that is not `SUMMARY`, `LLM`, `LLM:reconcile`, `reply:` or
   `resolve:`; **`REVIEW` joins that exclusion list**, or `review:<id>` would become a finding thread
   (`threads.markFindingThread`, `ResultSaga` L234).

Orchestrator-side `priorSummaryRef` keeps flowing; on GitHub it now holds `review:<id>` and is not
used for an in-place update any more (D2). The `updateOrPost` method stays for the legacy path.

### 5.2 `GitHubCommentSink`

- `postReview` → `POST /repos/{full}/pulls/{n}/reviews` with
  `{commit_id, event: APPROVE|REQUEST_CHANGES|COMMENT, body, comments: [{path, line, side,
  start_line?, start_side?, body}]}`. Field mapping per comment is the one `postInline` uses today
  (L79–101), without `commit_id` (it is top-level). Returns `PostedReview("<review id>", verdict,
  inline refs)`.
- **Inline refs are read back** from the pull request's comment list, `GET /pulls/{n}/comments`,
  walked with `per_page=100` until a short page and kept where `pull_request_review_id` is this
  review. (Amended after the live proof of 2026-10-16: `GET /pulls/{n}/reviews/{id}/comments`
  answers in the legacy shape — `path` and `position` only, `line`/`side`/`start_line` null — so
  nothing matched and PR 53's inline comment was reported as not posted.) A finding's `anchorKey()`
  is `path:line:side` with `line` the
  **start** line (`InlineAnchor` L21–24), but GitHub's `line` is the **end** of a range and `start_line`
  the start. Matching rule: same `path`, same `side`, and `start_line` when present else `line` equals
  the anchor's line. An unmatched finding is reported to the worker as unmatched (step 6).
- `findReview` → `GET /pulls/{n}/reviews` (paged, `per_page=100`, the walk
  `GitHubPullRequestApprovalSource` already does); the bot's review (`user.login` = the bot username)
  whose `commit_id` equals the head and whose body starts with the header; its state gives the
  `verdict`; its comments are read back as above.
- **422 classification.** The message text of anchor errors is not stable across the API's history,
  so the rule is by shape, not by field name: a 422 whose body contains `own pull request` →
  `ReviewVerdictRefused`; any other 422 on a review that **carried comments** → `ReviewAnchorRejected`
  (the body-only retry is safe, and a 422 on it then surfaces the real cause); a 422 on a body-only
  review → the plain `GitHubApiException` (its 500-char body snippet, `GitHubClient` L222–240, is the
  failure detail).
- `updateReviewBody` → `PUT /pulls/{n}/reviews/{id}` with `{body}`. `GitHubClient` gains `putJson`
  (it has `postJson`, `patchJson`, `postGraphQl`, L52–97).
- `dismissOwnChangesRequested` → the same walk as `findReview`, latest review by the bot with state
  `APPROVED`, `CHANGES_REQUESTED` or `DISMISSED`; when it is `CHANGES_REQUESTED`,
  `PUT /pulls/{n}/reviews/{id}/dismissals {message}`. GitHub requires write access on the repository
  for a dismissal (API knowledge; UNVERIFIED until the live case).
- `replyInThread` with a `review:` ref → top-level issue comment (the existing 404 fallback, L110–123,
  taken directly without the failing call). `fetchThread` with a `review:` ref → `GET
  /pulls/{n}/reviews/{id}` for the body as the first `ThreadMessage`, then the issue comments created
  after the review's `submitted_at`, in the same walk `issueThreadTranscript` does today (L304–327):
  `FollowUpWorker.shouldAnswer` (L341–351) counts the humans on that transcript and must still see the
  question. `updateComment` with a `review:` ref → `UnsupportedOperationException` (never reached on
  the native path).

### 5.3 Gateway

No change. `GitHubIngress.pullRequest` ignores `review_requested` (default arm, L182). A
`pull_request_review` by the bot arrives as activity: `GitHubIngress.activity` (L143–146) maps a
submitted `APPROVED` / `CHANGES_REQUESTED` / `DISMISSED` review to kind `approval`, which
`WorkGateChannels.approval` drops outside the land gate (L86) and refuses at the gate by the machine
check (§1); a `COMMENTED` review is kind `comment` and reaches `control.suspend`, which records
`recorded_machine` when the actor is the recorded reviewer (`WorkItemControl` L62–63). Neither starts
a review. Both facts get a test each (§8), split by review state, so a later change cannot make the
bot's own review start a second review. A reviewer registration with no identity makes the bot's
Comment review look human and suspends the item as `human_takeover`; that is true today for the bot's
summary comment and is recorded in §10, not changed here.

## 6. Factory — ask for the review (`WorkReview`)

After `ensureMarkedReady` returns true, in the same pass:

```
ensureReviewerRequested(item, execution):
  delivery = "reviewer-request:" + attemptId + ":" + prNumber
  if WorkItemControl.received(c, itemId, "factory", delivery): return      // durable once, survives restart
  row = review_status for (repositoryId, prNumber)
  if row exists && row.commit_sha == execution.head && row.status not in (received, reviewing): return
        // the bot already posted for this head (or the review ended): a request now is "re-requested" noise
  reviewer = repositoryAccounts.resolve(repositoryId, REVIEWER).map(ScmProvider::botUsername)
             // RepositoryAccounts, newly injected: MachineAccounts.resolve answers FACTORY only (L37-40)
  blank reviewer → receipt "reviewer_login_unknown"; return
  factorySink = clients.pullRequestSink(FACTORY provider)
  factorySink.requestReviewers(repo, pr, [reviewer])
  ok                        → receipt "reviewer_requested"
  DeliveryUnavailable/fault → receipt "reviewer_request_refused:<reason>", WARN log — never a wait
```

A **receipt** is a `WorkItemControl.receipt(c, itemId, "factory", delivery, outcome)` row, the same
record `WorkGateChannels` writes for SCM deliveries. It does not touch the item's `reason`, so the
test can assert "reason unchanged, receipt present". `WorkReview.waiting` (L132–142) is not used
here. The row check is on **row absence or a different head**, not on `observe`'s reason string:
`observe` answers `review_result_pending` both for no row and for a degraded completed row (L98, L110).

GitHub: `POST /repos/{full}/pulls/{n}/requested_reviewers {reviewers: [login]}`. Requesting an account
already requested is a no-op on GitHub. Known cases where the request is skipped: a PR a human marked
ready before the factory pass has a row at this head already past `reviewing` only after the bot
posted, so the request still goes out while the review runs; a retried build (M4) has a row at the
old head, so the request goes out. If the bot's review lands between the row check and the request
(the pass runs every 5 s; a review takes an LLM call), GitHub shows "re-requested" once: recorded in
§10 as noise, not a defect.

## 7. Documentation

- `docs/DECISIONS.md`: **ADR-047 — The reviewer posts a forge-native review where the forge has one**
  (D1–D11, the fallback ladder, why the orchestrator decides, why a Comment review needs a dismissal).
- `docs/SCM-MAPPING.md`: the posting section says GitHub posts a review with inline comments; GitLab
  and Bitbucket post a summary comment; the "External work answers" table notes that the bot's own
  review is machine activity and cannot answer a gate, and names the real guard (§1).
- `docs/UNVERIFIED.md`: new entries for the self-authored refusal, the rejected anchor, the dismissal
  (permission need and live effect), the crash window between a post and its mark, and for
  GitLab/Bitbucket native reviews (absent).
- `CLAUDE.md` status: the line "The review is posted as a comment, not as a GitHub review" goes away;
  the limits list says "GitLab and Bitbucket reviews are posted as comments".
- `docs/HISTORY.md`: the milestone entry after acceptance.
- `docs/factory/GITHUB-REVIEWS-ACCEPTANCE.md`: the live proof (§1).

## 8. Tests (written first, each watched failing on the defect line in CI)

**spire-contract**
- `ContractSchemaSnapshotTest` snapshot: `PostComments.assessment`, `.verdict`, `.reviewerMayApprove`;
  `CommentsPosted.postedVerdict`, `.reviewRef`.
- Back-compat constructors deserialize an old payload with the new fields null.

**spire-scm-github (WireMock, `GitHubApiTest` style)**
- `postsAnApprovingReviewWithInlineCommentsInOneCall` — body has `event: APPROVE`, `commit_id`, and
  the comments array with `path/line/side`; multi-line anchors carry `start_line/start_side`.
- `postsRequestChanges`, `postsComment`.
- `aSelfAuthoredRefusalRaisesVerdictRefused` — 422 "Can not approve your own pull request".
- `aRejectedAnchorRaisesAnchorRejected` — 422 with an unrelated message on a review that carried
  comments; `aBodyOnly422IsAPlainFailure`.
- `readsBackTheInlineCommentIdsAcrossPages` — two pages of `reviews/{id}/comments`; a ranged comment
  matches by `start_line`, not `line`.
- `findsTheBotsReviewForTheHead` — the bot's review at this head with the header is found; another
  user's review and an older head are not.
- `updatesAReviewBody` — PUT.
- `dismissesAStandingChangesRequested` — PUT dismissals with the message; `noDismissalWhenTheLatestIsApproved`.
- `aReplyToAReviewRefPostsAnIssueComment`, `fetchesAReviewRefAsBodyThenLaterComments` — a comment
  before `submitted_at` is excluded, one after is included.
- `requestsAReviewer` (`GitHubPullRequestSinkTest`) and `aNonCollaboratorRequestIsDeliveryUnavailable`.

**spire-review-worker (`ReviewWorkerTest`, `RecordingSink`)**
- `aVerdictPostsOneNativeReviewAndNoSummaryComment`.
- `aNullAssessmentPostsAsBefore` (legacy path unchanged, asserts summary + inline calls).
- `aForgeWithoutNativeReviewsPostsAsBefore` (`postReview` returns empty).
- `aRefusedVerdictIsPostedAgainAsAComment` — exactly two `postReview` calls, second COMMENT, body has
  the verdict line.
- `aRejectedAnchorFallsBackToBodyThenInlineThenBodyUpdate` — order asserted.
- `aRedeliveryAfterTheReviewWasPostedDoesNotPostTwice` — `REVIEW` marked; zero `postReview` calls.
- `aCrashAfterThePostDoesNotPostASecondReview` — `REVIEW` claimed, not marked, `findReview` returns the
  review; zero `postReview` calls, the mark is written, `CommentsPosted` carries its ref.
- `theSettingOffVerdictLineIsInTheBody` — assessment APPROVE, verdict COMMENT renders `**Verdict:** approve`.
- `aDegradedRunWithTheSettingOnHasNoVerdictLine` — assessment COMMENT, verdict COMMENT, no line.
- `theSettingOffDismissesAStandingHold` — `reviewerMayApprove=false` calls `dismissOwnChangesRequested`
  after the post; `theSettingOnDoesNot`.
- `aReviewRefIsNotAFindingThreadOnRedelivery` — the reconstruction excludes `REVIEW`.

**spire-orchestrator**
- `ResultSagaVerdictTest` (new file; the four existing `ResultSaga*Test` fakes stay). Every fake
  overrides `projection.openBlockers`, `projection.repositoryIdOf`, `repositories.reviewerMayApprove`
  and `repositoryAccounts.resolve` with a comment saying why (CLAUDE.md: an un-overridden fake opens a
  real database): `degradedGivesComment`, `anOpenBlockerFromAPriorRoundGivesRequestChanges` (the
  blocker is only in `reconciliation_json`, still open), `aHandFiledBlockerGivesRequestChanges`,
  `noBlockersGiveApprove`, `theSettingOffGivesCommentWithAssessmentApprove`,
  `anUnknownRepositoryGivesComment`, `aReviewerWithoutIdentityGivesComment`.
- `ReviewProjectionTest`: `openBlockersMatchesDetailOpenBlockers` on a row whose only BLOCKERs are one
  in `reconciliation_json` (still open) and one hand-filed, none in `findings_json`, so a read of
  `findings_json` alone fails; `recordPostedVerdictIsReadBackBySummaryAndDetail`;
  `aSupersededRunsVerdictIsNotRecorded` (commit mismatch leaves the columns null);
  `postedVerdictAndReviewUrlSerializeOnBothRecords`.
- `RepositoryRegistryTest`: default true, round trip through `update`, revision check.
- `WorkReviewIT`: `aReadyPullRequestRequestsTheReviewerOnce` (two passes, one request, one receipt),
  `aRefusedRequestIsAReceiptNotAWait` (reason unchanged, receipt `reviewer_request_refused:…`),
  `noRequestOnceTheBotPostedForThisHead`, `aRetriedBuildRequestsAgainAtTheNewHead`.
- `WorkGateChannelsIT`: `theBotsOwnApproveCannotResolveTheLandGate`.
- Gateway/ingress: `reviewRequestedIsIgnored`; `WorkItemControl`:
  `theBotsOwnCommentReviewIsRecordedMachine`, `theBotsOwnApprovedReviewIsNotAReviewTrigger`.

**spire-ui**
- `ReviewDetail` renders "Posted as Approved" with the link; nothing when null.
- `RepositoryForm` sends `reviewerMayApprove` and defaults it true.
- (The `api.ts` union is covered by the Java serialization test above; a TS type test cannot fail
  when the Java side omits the field.)

**Mutation checks** (CLAUDE.md gotcha): for each new guard, break the production line and confirm
exactly one test fails: the `openBlockers > 0` branch, the setting-off branch, the identity branch, the
`REVIEW` claim, the `findReview` call before a post, the immediate mark after the post, the `REVIEW`
exclusion in the reconstruction, the refusal retry count, the dismissal condition, the commit guard on
`recordPostedVerdict`, the head-and-status check before requesting a reviewer.

## 9. The decisions, in the order they were made

1. Request changes = at least one open BLOCKER (same as the land gate). *Not* BLOCKER-or-MAJOR.
2. Every run posts a new review; the summary comment goes away on GitHub. *Not* a comment plus a
   verdict-only review beside it.
3. Setting off = Comment for every outcome, verdict in words. *Not* "Approve becomes Comment, Request
   changes still blocks".
4. The orchestrator decides the verdict (D4). *Not* the worker.
5. The design sections 1–5 of the conversation (contract, orchestrator, worker/GitHub, factory,
   errors/docs/tests) were approved together.
6. An adversarial review of the written spec against the code (2026-10-15) found 19 defects: four
   would have made the build wrong (a Comment review clears no hold → D11; the `REVIEW` claim does not
   find a half-posted review → `findReview` and mark-at-once; the worker never received the assessment
   → three wire fields; the land-gate guard named was not the one that fires → the identity rule), and
   fifteen gaps an implementer would have had to guess. All are applied above. **D11 and the identity
   rule are the two changes to agreed behaviour and need the operator's yes.**

## 10. Risks and what stays unverified after this slice

- **The self-authored and rejected-anchor fallbacks, the dismissal, and the crash window** are tested
  against WireMock and fakes only. Live proof needs a PR opened by the reviewer account, a review whose
  anchor GitHub rejects, a hold released by turning the setting off, and a worker killed between the
  post and the mark; none is in the exit criterion. All go to `UNVERIFIED.md`.
- **Dismissal permission.** GitHub requires write access to dismiss a review (API knowledge). A
  reviewer account with read access posts reviews but cannot release its own hold; the WARN log names
  it, and the operator turns the setting back on or gives the account write access.
- **Branch protection.** Whether a bot Approve counts toward "required approvals" depends on the
  repository's rules (CODEOWNERS, "dismiss stale", "require review from someone other than the last
  pusher"). This design does not read or change protection rules.
- **Re-request on push.** GitHub re-requests reviewers it had requested when the branch is pushed if
  the repository has that setting. The factory does not push after delivery, so this is not expected;
  if seen, it is harmless noise. The same noise appears once if the bot's review lands between the
  factory's row check and its request (§6).
- **A reviewer that is not a collaborator** cannot be requested; the receipt names it. The review
  still happens.
- **A reviewer registration without an identity** gets Comment reviews only (§4.1), and its Comment
  review suspends the item as `human_takeover` (§5.3), as its summary comment does today. The fix is
  to register the identity; this slice does not change the suspend rule.

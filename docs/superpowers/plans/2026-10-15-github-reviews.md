# Real GitHub reviews — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** on GitHub the reviewer posts one pull request review (Approve / Request changes / Comment) with
the summary as its body and every inline finding inside it; the factory asks for the bot as reviewer on its
PRs; a per-repository "Reviewer may approve" setting (default on) keeps the bot from ever changing merge
state.

**Architecture:**
- **Contract.** `PostComments` carries the orchestrator's decision (assessment, verdict, may-approve).
  `CommentsPosted` carries what was posted (verdict, review id). `CommentSink` gains four defaults for a
  forge-native review; `PullRequestSink` gains `requestReviewers`.
- **Orchestrator.** `ResultSaga` decides from the projection's open-blocker count, the repository setting
  and the reviewer identity. V88 adds the setting and the two posted columns. The projection exposes
  `openBlockers` and records the posted verdict under the existing commit guard. `WorkReview` asks for the
  reviewer once per attempt and PR, with a receipt.
- **Worker and GitHub adapter.** A new `NativeReviewPost` collaborator runs the native path: claim, find,
  post, mark at once, read back, fall back, update body, dismiss. `GitHubReviewApi` holds the GitHub calls;
  `GitHubCommentSink` delegates to it and understands `review:<id>` thread refs.
- **UI.** "Posted as …" on the review detail, the setting on the repository form and detail.

**Tech stack:** Java 25, Quarkus 3.38.3, SmallRye Kafka, PostgreSQL/Flyway, WireMock, React + vitest.

**Spec:** [`docs/superpowers/specs/2026-10-15-github-reviews-design.md`](../specs/2026-10-15-github-reviews-design.md)
(approved 2026-10-15, commit `0f7bcbd5`). Read it before any task; section numbers below (§n) refer to it.

## Global Constraints

- **No local builds.** Never run `gradlew`, `npm test`, `npx vitest|tsc|eslint` on the host
  (`no-local-builds.md`). Every "verify" step is: commit, `git push`, then `gh pr checks <PR> --watch`. On
  red, `gh run view <run-id> --log-failed`. A RED step is proved by a red CI check whose failure names the
  test; a GREEN step by the same check green.
- **Branch and PR.** Implementation runs on `feat/github-reviews` (already holds the spec commits). One
  draft PR, opened in Task 0.
- **Commits.** Imperative subject of 72 characters or fewer, body for non-trivial changes, `Part of #115`,
  no closing keyword before an issue number, no AI or vendor names, no `Co-Authored-By` trailer.
- **Test data.** Identifiers in tests are `TEST-` prefixed; hosts are `*.example.test`; no market figures.
- **Java style.** 4-space indent. Explicit types over `var` in new code; the dense one-line style of
  `work/` is kept when editing those files. Wire records: a new component is added once, through a wither
  or the full constructor, and every shorter constructor passes the default explicitly. `spire-contract`
  imports no framework (Jackson annotations only).
- **Exact strings.**
  - `ReviewVerdict { APPROVE, REQUEST_CHANGES, COMMENT }`.
  - Idempotency key `REVIEW`; thread ref prefix `review:`.
  - Verdict line `**Verdict:** approve` / `**Verdict:** changes requested`, placed right after the header
    line `### Code Spire review (`<commit>`)` and its blank line.
  - Dismissal message `Superseded by review <id>: the reviewer may not change merge state on this repository`.
  - Timeline event type `ReviewVerdictDecided`; reasons `degraded`, `open_blockers=N`, `no_open_blockers`,
    `setting_off`, `repository_unknown`, `reviewer_identity_unknown`.
  - Receipt channel `factory`, delivery `reviewer-request:<attemptId>:<pr>`, outcomes `reviewer_requested`,
    `reviewer_login_unknown`, `reviewer_request_refused:<reason>`.
  - `DeliveryUnavailable` reasons `request_reviewers_unsupported`, `reviewer_not_collaborator`.
  - Posted verdict slugs `approved`, `changes_requested`, `commented`; review URL
    `htmlUrl + "#pullrequestreview-" + ref`.
- **Migration.** Orchestrator `V88__reviewer_may_approve.sql`. If V88 is taken on master when you start, take
  the next free number and keep the name.
- **Refinement of §3 (ruling, recorded here).** `PostedReview.inline` is `Map<String, String>` from
  `InlineAnchor.anchorKey()` to the thread ref, not `List<CommentRef>`: the worker keys its idempotency map
  by anchor key and needs nothing else. `findReview` takes the `ReviewSubmission` (its first line is the
  prefix to match, its comments are what to read back).

**Mutation checks (no local runs).** Each task lists its guard lines. For each one:
1. Commit a mutant that breaks that line on a scratch branch `mut/github-reviews-t<N>-<k>` off the task's
   commit.
2. Push it and open a draft PR against `feat/github-reviews` titled `TEST mutant t<N>-<k> (do not merge)`.
3. Read `gh pr checks`. Exactly one test should fail, and it must be the named test.
4. Close the PR and delete the branch.
5. Record the result in `.claude/reviews/global/github-reviews-mutations.md`: the line, the mutant, the
   failing test and the run URL.

If CI budget is a concern, batch the mutants of one task into one branch, one commit per mutant, and read
each commit's run.

## Review Focus

Inputs the spec implies but no task's tests drive end to end. Each line names its pinning test and the
task that owns it.

1. **A finding whose anchor is on the OLD side** (a removed line). GitHub takes `side: LEFT` and `line` =
   the old line; the read-back must match on `LEFT`. Pinned by `anOldSideAnchorIsMatchedOnTheLeftSide`
   (Task 2).
2. **Two findings at one anchor key.** The submission carries both comments; the read-back maps the key once
   and both findings report that thread. Pinned by `twoFindingsAtOneAnchorShareOneThread` (Task 4).
3. **The bot login cannot be resolved** (the `/user` lookup fails and no brokered login). `findReview` must
   answer empty, not throw, and the worker posts. Pinned by `findReviewIsEmptyWhenTheBotLoginIsUnknown`
   (Task 2).
4. **A `CommentsPosted` from a superseded run** carrying a verdict. The posted verdict must not land on the
   newer head. Pinned by `aSupersededRunsVerdictIsNotRecorded` (Task 6).
5. **A legacy summary ref (a plain comment id) on a GitHub review** after the deploy, with a human reply to
   it. `fetchThread` must keep the issue-comment walk for a non-`review:` ref. Pinned by the existing
   `fetchThreadFallsBackToIssueCommentsWhenNoRootMatchesAReviewComment`, re-read in Task 3 (no change).

---

### Task 0: Branch, draft PR and review state file

**Files:**
- Create: `.claude/reviews/global/github-reviews.md`

- [ ] **Step 1: Confirm the branch and base**

Run: `git -C E:/Projects/Stukans/code-spire-worktrees/feat-software-factory status --short --branch && git log --oneline -3`
Expected: `## feat/github-reviews`, clean tree, top commit `0f7bcbd5 Apply the spec review findings…`.

- [ ] **Step 2: Create the review state file**

```markdown
# GitHub reviews — branch review state (PR #<n>)

Base `2757caa3` (master), branch `feat/github-reviews`. Spec `docs/superpowers/specs/2026-10-15-github-reviews-design.md`,
plan `docs/superpowers/plans/2026-10-15-github-reviews.md`.

## Rounds

(none yet)
```

- [ ] **Step 3: Push and open the draft PR**

```bash
git add .claude/reviews/global/github-reviews.md
git commit -m "Open the GitHub reviews branch review state

Part of #115"
git push -u origin feat/github-reviews
gh pr create --draft --base master --title "Post forge-native GitHub reviews" --body "The reviewer posts a GitHub pull request review (Approve / Request changes / Comment) with the inline findings inside it; the factory requests the bot as reviewer; a per-repository \"Reviewer may approve\" setting.

Spec: docs/superpowers/specs/2026-10-15-github-reviews-design.md
Plan: docs/superpowers/plans/2026-10-15-github-reviews.md

Part of #115"
```

Expected: a PR number. Write it into the state file's title line and amend nothing; commit that edit in
Task 1's first commit.

---

### Task 1: Contract — verdict enum, wire fields, ports

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/review/ReviewVerdict.java`
- Create: `spire-contract/src/main/java/dev/codespire/contract/scm/ReviewSubmission.java`
- Create: `spire-contract/src/main/java/dev/codespire/contract/scm/PostedReview.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/ActionCommand.java:150-190`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/IntegrationEvent.java:289-318`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/port/CommentSink.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/port/PullRequestSink.java`
- Modify: `spire-contract/src/test/resources/contract-schema.txt` (lines `CommentsPosted(` and `PostComments(`)
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/PostCommentsWireCompatibilityTest.java`

**Interfaces:**
- Produces: `ReviewVerdict`; `PostComments.assessment()`, `.verdict()`, `.reviewerMayApprove()` (all nullable),
  `PostComments withVerdict(ReviewVerdict assessment, ReviewVerdict verdict, boolean reviewerMayApprove)`;
  `CommentsPosted.postedVerdict()`, `.reviewRef()`, `CommentsPosted withPostedReview(ReviewVerdict, String)`;
  `ReviewSubmission(ReviewVerdict verdict, String bodyMd, List<InlineComment> comments)` with
  `record InlineComment(InlineAnchor anchor, String bodyMd)`, `withVerdict`, `withBody`, `bodyOnly()`;
  `PostedReview(String reviewRef, ReviewVerdict verdict, Map<String, String> inlineThreads)`;
  `CommentSink.findReview / postReview / updateReviewBody / dismissOwnChangesRequested`,
  `CommentSink.ReviewAnchorRejected`, `CommentSink.ReviewVerdictRefused`;
  `PullRequestSink.requestReviewers(RepoRef, PullRequestRef, List<String>)`.

- [ ] **Step 1: Edit the golden file first (the failing test)**

In `spire-contract/src/test/resources/contract-schema.txt` change the two lines:

```
CommentsPosted(reviewId: java.lang.String, prId: long, commit: java.lang.String, summaryThreadRef: java.lang.String, inline: java.util.List<dev.codespire.contract.event.IntegrationEvent$CommentsPosted$PostedInline>, threadOutcomes: java.util.List<dev.codespire.contract.event.IntegrationEvent$CommentsPosted$ThreadOutcome>, postedVerdict: dev.codespire.contract.review.ReviewVerdict, reviewRef: java.lang.String)
```

```
PostComments(reviewId: java.lang.String, repo: dev.codespire.contract.scm.RepoRef, prId: long, commit: java.lang.String, findings: dev.codespire.contract.review.ReviewResult, scmCredential: java.lang.String, verdicts: java.util.List<dev.codespire.contract.review.FindingVerdict>, priorSummaryRef: java.lang.String, suppressedCount: int, assessment: dev.codespire.contract.review.ReviewVerdict, verdict: dev.codespire.contract.review.ReviewVerdict, reviewerMayApprove: java.lang.Boolean)
```

- [ ] **Step 2: Write the wire-compatibility test**

`spire-review-worker/src/test/java/dev/codespire/worker/pipeline/PostCommentsWireCompatibilityTest.java`:

```java
package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** D10: a command or event recorded before the verdict fields existed still deserializes. */
class PostCommentsWireCompatibilityTest {

    private static final RepoRef REPO = new RepoRef("TEST-ws", "TEST-repo");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anOldPostCommentsPayloadDeserializesWithNullVerdictFields() throws Exception {
        PostComments current = new PostComments("review::TEST-ws/TEST-repo#1", REPO, 1, "TEST-commit",
                new ReviewResult(List.of(), "TEST", ModelUsage.of("TEST-MODEL", 1, 1)), null);
        ObjectNode old = MAPPER.valueToTree(current);
        old.remove("assessment");
        old.remove("verdict");
        old.remove("reviewerMayApprove");
        assertFalse(old.has("verdict"), "the payload under test predates the field");

        PostComments back = assertInstanceOf(PostComments.class, MAPPER.treeToValue(old, ActionCommand.class));
        assertNull(back.assessment());
        assertNull(back.verdict());
        assertNull(back.reviewerMayApprove());
    }

    @Test
    void withVerdictCarriesAllThreeAndKeepsTheRest() {
        PostComments current = new PostComments("review::TEST-ws/TEST-repo#1", REPO, 1, "TEST-commit",
                new ReviewResult(List.of(), "TEST", ModelUsage.of("TEST-MODEL", 1, 1)), null)
                .withSuppressedCount(2)
                .withVerdict(ReviewVerdict.APPROVE, ReviewVerdict.COMMENT, false);
        assertEquals(ReviewVerdict.APPROVE, current.assessment());
        assertEquals(ReviewVerdict.COMMENT, current.verdict());
        assertEquals(Boolean.FALSE, current.reviewerMayApprove());
        assertEquals(2, current.suppressedCount(), "a wither must enumerate every component");
    }

    @Test
    void anOldCommentsPostedPayloadDeserializesWithNullPostedFields() throws Exception {
        CommentsPosted current = new CommentsPosted("review::TEST-ws/TEST-repo#1", 1, "TEST-commit",
                "review:TEST-ref", List.of());
        ObjectNode old = MAPPER.valueToTree(current);
        old.remove("postedVerdict");
        old.remove("reviewRef");

        CommentsPosted back = assertInstanceOf(CommentsPosted.class,
                MAPPER.treeToValue(old, IntegrationEvent.class));
        assertNull(back.postedVerdict());
        assertNull(back.reviewRef());
        assertEquals("review:TEST-ref", back.summaryThreadRef());
    }
}
```

- [ ] **Step 3: Commit and push; read CI**

```bash
git add spire-contract/src/test/resources/contract-schema.txt spire-review-worker/src/test/java/dev/codespire/worker/pipeline/PostCommentsWireCompatibilityTest.java .claude/reviews/global/github-reviews.md
git commit -m "Add failing tests for the review verdict wire fields

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: `fast tests` red on `ContractSchemaSnapshotTest.wireShapeMatchesTheRecordedSnapshot`; the
review-worker test does not compile (`assessment()` missing) and its module check is red.

- [ ] **Step 4: Add the enum and the two value records**

`ReviewVerdict.java`:

```java
package dev.codespire.contract.review;

/** What a review says about a pull request, as a forge-native review states it (ADR-047). */
public enum ReviewVerdict {
    APPROVE,
    REQUEST_CHANGES,
    COMMENT
}
```

`ReviewSubmission.java`:

```java
package dev.codespire.contract.scm;

import dev.codespire.contract.review.ReviewVerdict;

import java.util.List;
import java.util.Objects;

/** One forge-native review to post: verdict, body, and the inline comments inside it (ADR-047). */
public record ReviewSubmission(ReviewVerdict verdict, String bodyMd, List<InlineComment> comments) {

    public ReviewSubmission {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(bodyMd, "bodyMd");
        comments = comments == null ? List.of() : List.copyOf(comments);
    }

    /** One inline comment of the review; the anchor is resolved against the reviewed diff. */
    public record InlineComment(InlineAnchor anchor, String bodyMd) {
    }

    public ReviewSubmission withVerdict(ReviewVerdict replacement) {
        return new ReviewSubmission(replacement, bodyMd, comments);
    }

    public ReviewSubmission withBody(String replacement) {
        return new ReviewSubmission(verdict, replacement, comments);
    }

    /** The same review with no inline comments — the D7 fallback. */
    public ReviewSubmission bodyOnly() {
        return new ReviewSubmission(verdict, bodyMd, List.of());
    }

    /** The header line, which {@code CommentSink.findReview} matches a posted body on. */
    public String firstLine() {
        int end = bodyMd.indexOf('\n');
        return end < 0 ? bodyMd : bodyMd.substring(0, end);
    }
}
```

`PostedReview.java`:

```java
package dev.codespire.contract.scm;

import dev.codespire.contract.review.ReviewVerdict;

import java.util.Map;

/**
 * A forge-native review as the forge holds it: its id, the verdict it actually carries (COMMENT when the
 * forge refused the asked one, D6), and each inline comment's thread ref keyed by
 * {@link InlineAnchor#anchorKey()} — the key the worker's idempotency map uses.
 */
public record PostedReview(String reviewRef, ReviewVerdict verdict, Map<String, String> inlineThreads) {

    public PostedReview {
        inlineThreads = inlineThreads == null ? Map.of() : Map.copyOf(inlineThreads);
    }
}
```

- [ ] **Step 5: Add the wire components with withers and back-compat constructors**

In `ActionCommand.java` replace the `PostComments` record with:

```java
    /**
     * Findings inline — same ReviewResult as ReviewGenerated (ADR-011). On a follow-up review the
     * verdicts drive thread replies/resolves (ADR-019) and priorSummaryRef is the summary comment
     * to update in place; both empty/null on a first review.
     *
     * <p>{@code assessment} is what the findings say (ADR-047 D4), {@code verdict} what the worker posts,
     * {@code reviewerMayApprove} the repository setting the orchestrator applied: {@code verdict} is
     * always {@code reviewerMayApprove ? assessment : COMMENT}. All three null means "post as before"
     * (D10) — a command recorded before the fields existed, or a forge with no native review.
     */
    record PostComments(String reviewId, RepoRef repo, long prId, String commit,
                        ReviewResult findings, String scmCredential,
                        List<FindingVerdict> verdicts, String priorSummaryRef,
                        int suppressedCount, ReviewVerdict assessment, ReviewVerdict verdict,
                        Boolean reviewerMayApprove) implements ActionCommand {

        public PostComments {
            verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        }

        public PostComments(String reviewId, RepoRef repo, long prId, String commit,
                            ReviewResult findings, String scmCredential) {
            this(reviewId, repo, prId, commit, findings, scmCredential, List.of(), null, 0, null, null, null);
        }

        public PostComments(String reviewId, RepoRef repo, long prId, String commit,
                            ReviewResult findings, String scmCredential,
                            List<FindingVerdict> verdicts, String priorSummaryRef) {
            this(reviewId, repo, prId, commit, findings, scmCredential, verdicts, priorSummaryRef, 0, null, null, null);
        }

        /** Call sites and in-flight records from before the verdict fields existed (D10). */
        public PostComments(String reviewId, RepoRef repo, long prId, String commit,
                            ReviewResult findings, String scmCredential,
                            List<FindingVerdict> verdicts, String priorSummaryRef, int suppressedCount) {
            this(reviewId, repo, prId, commit, findings, scmCredential, verdicts, priorSummaryRef,
                    suppressedCount, null, null, null);
        }

        /** (keep the existing javadoc of withSuppressedCount here, unchanged) */
        public PostComments withSuppressedCount(int replacement) {
            return new PostComments(reviewId, repo, prId, commit, findings, scmCredential, verdicts,
                    priorSummaryRef, replacement, assessment, verdict, reviewerMayApprove);
        }

        /** The orchestrator's decision (ADR-047 D4), enumerated once, next to the record. */
        public PostComments withVerdict(ReviewVerdict assessment, ReviewVerdict verdict, boolean reviewerMayApprove) {
            return new PostComments(reviewId, repo, prId, commit, findings, scmCredential, verdicts,
                    priorSummaryRef, suppressedCount, assessment, verdict, reviewerMayApprove);
        }
    }
```

Add `import dev.codespire.contract.review.ReviewVerdict;` to `ActionCommand.java`.

In `IntegrationEvent.java` replace the `CommentsPosted` record head and constructors:

```java
    /**
     * {@code postedVerdict} and {@code reviewRef} are set only when a forge-native review was posted
     * (ADR-047): the verdict the forge holds and its review id. Null on the legacy path and on every
     * record from before the fields existed.
     */
    record CommentsPosted(String reviewId, long prId, String commit, String summaryThreadRef,
                          List<PostedInline> inline, List<ThreadOutcome> threadOutcomes,
                          ReviewVerdict postedVerdict, String reviewRef) implements IntegrationEvent {

        public CommentsPosted {
            inline = inline == null ? null : List.copyOf(inline);
            threadOutcomes = threadOutcomes == null ? List.of() : List.copyOf(threadOutcomes);
        }

        /* PostedInline and ThreadOutcome stay exactly as they are. */

        public CommentsPosted(String reviewId, long prId, String commit, String summaryThreadRef,
                              List<PostedInline> inline) {
            this(reviewId, prId, commit, summaryThreadRef, inline, List.of());
        }

        /** Call sites and records from before the posted-review fields existed. */
        public CommentsPosted(String reviewId, long prId, String commit, String summaryThreadRef,
                              List<PostedInline> inline, List<ThreadOutcome> threadOutcomes) {
            this(reviewId, prId, commit, summaryThreadRef, inline, threadOutcomes, null, null);
        }

        /** What the forge now holds, enumerated once, next to the record. */
        public CommentsPosted withPostedReview(ReviewVerdict postedVerdict, String reviewRef) {
            return new CommentsPosted(reviewId, prId, commit, summaryThreadRef, inline, threadOutcomes,
                    postedVerdict, reviewRef);
        }
    }
```

Add `import dev.codespire.contract.review.ReviewVerdict;` to `IntegrationEvent.java`.

- [ ] **Step 6: Add the port methods**

Append to `CommentSink` (before the closing brace), with these imports added:
`dev.codespire.contract.scm.PostedReview`, `dev.codespire.contract.scm.ReviewSubmission`, `java.util.Optional`.

```java
    /**
     * The review this bot already posted for {@code headCommit} whose body starts with
     * {@code review.firstLine()}, if any — the recovery read before a post (ADR-047 §5.1 step 3).
     * Its {@code inlineThreads} are read back for {@code review.comments()}.
     */
    default Optional<PostedReview> findReview(RepoRef repo, long prId, String headCommit, ReviewSubmission review) {
        return Optional.empty();
    }

    /**
     * Posts one forge-native review. The default is "this forge has none": the caller then uses
     * {@link #postSummary} and {@link #postInline} as before.
     *
     * @throws ReviewVerdictRefused when the forge refuses the verdict on this pull request (D6)
     * @throws ReviewAnchorRejected when the forge rejects the review because of an inline anchor (D7)
     */
    default Optional<PostedReview> postReview(RepoRef repo, long prId, String headCommit, ReviewSubmission review) {
        return Optional.empty();
    }

    /** Rewrites a posted review's body. Only a forge that returned a {@link PostedReview} supports it. */
    default void updateReviewBody(RepoRef repo, long prId, String reviewRef, String bodyMd) {
        throw new UnsupportedOperationException(type() + " cannot update a review body");
    }

    /**
     * Dismisses this bot's own standing "changes requested" review, if one stands (D11), and answers
     * its ref. Idempotent: a repeat finds no standing hold and answers empty.
     */
    default Optional<String> dismissOwnChangesRequested(RepoRef repo, long prId, String message) {
        return Optional.empty();
    }

    /** The forge refused the asked verdict on this pull request (GitHub: one's own pull request). */
    class ReviewVerdictRefused extends RuntimeException {
        public ReviewVerdictRefused(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The forge rejected the whole review because of an inline comment's anchor. */
    class ReviewAnchorRejected extends RuntimeException {
        public ReviewAnchorRejected(String message, Throwable cause) {
            super(message, cause);
        }
    }
```

Append to `PullRequestSink` (import `java.util.List`):

```java
    /**
     * Asks the forge to list these accounts as requested reviewers (ADR-047 D9). Requesting an account
     * already requested is a no-op on every forge that supports it.
     *
     * @throws DeliveryUnavailable {@code request_reviewers_unsupported} when this adapter cannot do it;
     *     {@code reviewer_not_collaborator} when the forge refuses the login
     */
    default void requestReviewers(RepoRef repo, PullRequestRef pullRequest, List<String> logins) {
        throw new DeliveryUnavailable("request_reviewers_unsupported");
    }
```

- [ ] **Step 7: Commit, push, read CI**

```bash
git add spire-contract spire-review-worker/src/test
git commit -m "Carry the review verdict on the wire and add the native review ports

PostComments carries the orchestrator's assessment, the verdict to post
and the repository setting; CommentsPosted carries what was posted.
CommentSink gains find/post/update/dismiss defaults for a forge-native
review and PullRequestSink gains requestReviewers (ADR-047).

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: all checks green, including `fast tests` (snapshot) and `spire-review-worker`.

**Mutation checks:** (a) drop `assessment` from `withSuppressedCount` →
`withVerdictCarriesAllThreeAndKeepsTheRest` fails; (b) make the 9-arg constructor pass `COMMENT` for
`verdict` → `anOldPostCommentsPayloadDeserializesWithNullVerdictFields` fails.

---

### Task 2: GitHub adapter — post, find, read back, update, dismiss

**Files:**
- Create: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubReviewApi.java`
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubClient.java:84-90` (add `putJson`)
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubCommentSink.java:49-64` (field, ctor) and append overrides
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubReviewSinkTest.java`

**Interfaces:**
- Consumes: Task 1 ports and records.
- Produces: `GitHubClient.putJson(String path, Object body)`; `GitHubCommentSink` overrides
  `findReview`, `postReview`, `updateReviewBody`, `dismissOwnChangesRequested`;
  `GitHubReviewApi` (package-private): `post`, `find`, `updateBody`, `dismissChangesRequested`,
  `readBack`, `static matches(JsonNode, InlineAnchor)`, `static verdictOf(String state)`.

- [ ] **Step 1: Write the failing WireMock tests**

`GitHubReviewSinkTest.java`:

```java
package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PostedReview;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ReviewSubmission;
import dev.codespire.contract.scm.ReviewSubmission.InlineComment;
import dev.codespire.contract.scm.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A forge-native review on GitHub (ADR-047 §5.2) against a WireMock GitHub. */
class GitHubReviewSinkTest {

    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private static final String REVIEWS = "/repos/TEST-owner/TEST-repo/pulls/42/reviews";
    private static final String HEAD = "b".repeat(40);
    private static final String HEADER = "### Code Spire review (`" + HEAD + "`)";
    private static final String BODY = HEADER + "\n\nTEST summary\n";

    private WireMockServer server;
    private GitHubCommentSink sink;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        sink = new GitHubCommentSink(new GitHubClient(
                new GitHubConfig(server.baseUrl(), "TEST-token-do-not-print", "unused"), new ObjectMapper()), "TEST-bot");
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private static InlineComment single(String path, int newLine) {
        return new InlineComment(new InlineAnchor(path, path, null, newLine, Side.NEW), "TEST " + path);
    }

    private static InlineComment ranged(String path, int start, int end) {
        return new InlineComment(new InlineAnchor(path, path, null, start, Side.NEW, end), "TEST range");
    }

    private static InlineComment oldSide(String path, int oldLine) {
        return new InlineComment(new InlineAnchor(path, path, oldLine, null, Side.OLD), "TEST removed");
    }

    private void stubCreated(int id) {
        server.stubFor(post(urlEqualTo(REVIEWS)).willReturn(okJson("{\"id\":" + id + ",\"state\":\"APPROVED\"}")));
    }

    private void stubComments(int id, String json) {
        server.stubFor(get(urlEqualTo(REVIEWS + "/" + id + "/comments?per_page=100&page=1")).willReturn(okJson(json)));
    }

    @Test
    void postsAnApprovingReviewWithInlineCommentsInOneCall() {
        stubCreated(501);
        stubComments(501, """
                [ {"id":9001,"path":"src/A.java","line":12,"side":"RIGHT","start_line":null},
                  {"id":9002,"path":"src/B.java","line":8,"side":"RIGHT","start_line":5} ]
                """);

        PostedReview posted = sink.postReview(REPO, 42, HEAD, new ReviewSubmission(ReviewVerdict.APPROVE, BODY,
                List.of(single("src/A.java", 12), ranged("src/B.java", 5, 8)))).orElseThrow();

        server.verify(1, postRequestedFor(urlEqualTo(REVIEWS))
                .withRequestBody(matchingJsonPath("$.event", com.github.tomakehurst.wiremock.client.WireMock.equalTo("APPROVE")))
                .withRequestBody(matchingJsonPath("$.commit_id", com.github.tomakehurst.wiremock.client.WireMock.equalTo(HEAD)))
                .withRequestBody(matchingJsonPath("$.comments[0].path", com.github.tomakehurst.wiremock.client.WireMock.equalTo("src/A.java")))
                .withRequestBody(matchingJsonPath("$.comments[0].line", com.github.tomakehurst.wiremock.client.WireMock.equalTo("12")))
                .withRequestBody(matchingJsonPath("$.comments[0].side", com.github.tomakehurst.wiremock.client.WireMock.equalTo("RIGHT")))
                .withRequestBody(matchingJsonPath("$.comments[1].start_line", com.github.tomakehurst.wiremock.client.WireMock.equalTo("5")))
                .withRequestBody(matchingJsonPath("$.comments[1].line", com.github.tomakehurst.wiremock.client.WireMock.equalTo("8"))));
        assertEquals("501", posted.reviewRef());
        assertEquals(ReviewVerdict.APPROVE, posted.verdict());
        assertEquals(Map.of("src/A.java:12:NEW", "9001", "src/B.java:5:NEW", "9002"), posted.inlineThreads(),
                "a ranged comment is matched on start_line, not line");
    }

    @Test
    void postsRequestChangesAndComment() {
        stubCreated(502);
        sink.postReview(REPO, 42, HEAD, new ReviewSubmission(ReviewVerdict.REQUEST_CHANGES, BODY, List.of()));
        sink.postReview(REPO, 42, HEAD, new ReviewSubmission(ReviewVerdict.COMMENT, BODY, List.of()));
        server.verify(1, postRequestedFor(urlEqualTo(REVIEWS)).withRequestBody(matchingJsonPath("$.event",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("REQUEST_CHANGES"))));
        server.verify(1, postRequestedFor(urlEqualTo(REVIEWS)).withRequestBody(matchingJsonPath("$.event",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("COMMENT"))));
    }

    @Test
    void anOldSideAnchorIsMatchedOnTheLeftSide() {
        stubCreated(503);
        stubComments(503, "[ {\"id\":9003,\"path\":\"src/A.java\",\"line\":4,\"side\":\"LEFT\",\"start_line\":null} ]");
        PostedReview posted = sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.COMMENT, BODY, List.of(oldSide("src/A.java", 4)))).orElseThrow();
        server.verify(1, postRequestedFor(urlEqualTo(REVIEWS)).withRequestBody(matchingJsonPath("$.comments[0].side",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("LEFT"))));
        assertEquals(Map.of("src/A.java:4:OLD", "9003"), posted.inlineThreads());
    }

    @Test
    void aSelfAuthoredRefusalRaisesVerdictRefused() {
        server.stubFor(post(urlEqualTo(REVIEWS)).willReturn(aResponse().withStatus(422)
                .withBody("{\"message\":\"Can not approve your own pull request\"}")));
        assertThrows(CommentSink.ReviewVerdictRefused.class, () -> sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of(single("src/A.java", 12)))));
    }

    @Test
    void aRejectedAnchorRaisesAnchorRejected() {
        server.stubFor(post(urlEqualTo(REVIEWS)).willReturn(aResponse().withStatus(422)
                .withBody("{\"message\":\"Unprocessable Entity\",\"errors\":[\"pull_request_review_thread.line must be part of the diff\"]}")));
        assertThrows(CommentSink.ReviewAnchorRejected.class, () -> sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of(single("src/A.java", 12)))));
    }

    @Test
    void aBodyOnly422IsAPlainFailure() {
        server.stubFor(post(urlEqualTo(REVIEWS)).willReturn(aResponse().withStatus(422)
                .withBody("{\"message\":\"Unprocessable Entity\"}")));
        assertThrows(GitHubApiException.class, () -> sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of())));
    }

    @Test
    void aReviewIdMissingFromA2xxIsAFailure() {
        server.stubFor(post(urlEqualTo(REVIEWS)).willReturn(okJson("{}")));
        assertThrows(GitHubApiException.class, () -> sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.COMMENT, BODY, List.of())));
    }

    @Test
    void readsBackTheInlineCommentIdsAcrossPages() {
        stubCreated(504);
        StringBuilder page1 = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            page1.append(i > 0 ? "," : "").append("{\"id\":").append(1000 + i)
                    .append(",\"path\":\"other/File.java\",\"line\":").append(i + 1).append(",\"side\":\"RIGHT\"}");
        }
        page1.append("]");
        stubComments(504, page1.toString());
        server.stubFor(get(urlEqualTo(REVIEWS + "/504/comments?per_page=100&page=2"))
                .willReturn(okJson("[ {\"id\":9004,\"path\":\"src/A.java\",\"line\":12,\"side\":\"RIGHT\"} ]")));
        PostedReview posted = sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of(single("src/A.java", 12)))).orElseThrow();
        assertEquals(Map.of("src/A.java:12:NEW", "9004"), posted.inlineThreads());
    }

    private void stubReviews(String json) {
        server.stubFor(get(urlEqualTo(REVIEWS + "?per_page=100&page=1")).willReturn(okJson(json)));
    }

    @Test
    void findsTheBotsReviewForTheHead() {
        stubReviews("""
                [ {"id":61,"state":"APPROVED","commit_id":"%s","body":"%s\\n\\nx","user":{"login":"TEST-human"}},
                  {"id":62,"state":"CHANGES_REQUESTED","commit_id":"%s","body":"%s\\n\\nold","user":{"login":"TEST-bot"}},
                  {"id":63,"state":"CHANGES_REQUESTED","commit_id":"%s","body":"%s\\n\\nnew","user":{"login":"TEST-bot"}} ]
                """.formatted(HEAD, HEADER, "a".repeat(40), "### Code Spire review (`" + "a".repeat(40) + "`)", HEAD, HEADER));
        stubComments(63, "[ {\"id\":9005,\"path\":\"src/A.java\",\"line\":12,\"side\":\"RIGHT\"} ]");
        PostedReview found = sink.findReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of(single("src/A.java", 12)))).orElseThrow();
        assertEquals("63", found.reviewRef(), "another user's review and the bot's review of an older head are not it");
        assertEquals(ReviewVerdict.REQUEST_CHANGES, found.verdict(), "the verdict is what the forge holds");
        assertEquals(Map.of("src/A.java:12:NEW", "9005"), found.inlineThreads());
    }

    @Test
    void findReviewIsEmptyWhenTheBotHasNotPostedForThisHead() {
        stubReviews("[ {\"id\":61,\"state\":\"APPROVED\",\"commit_id\":\"%s\",\"body\":\"%s\",\"user\":{\"login\":\"TEST-bot\"}} ]"
                .formatted("a".repeat(40), HEADER));
        assertTrue(sink.findReview(REPO, 42, HEAD, new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of())).isEmpty());
    }

    @Test
    void findReviewIsEmptyWhenTheBotLoginIsUnknown() {
        GitHubCommentSink anonymous = new GitHubCommentSink(new GitHubClient(
                new GitHubConfig(server.baseUrl(), "TEST-token-do-not-print", "unused"), new ObjectMapper()));
        server.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(503)));
        assertTrue(anonymous.findReview(REPO, 42, HEAD, new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of())).isEmpty(),
                "an unknown login cannot identify the bot's review; the caller posts");
    }

    @Test
    void updatesAReviewBody() {
        server.stubFor(put(urlEqualTo(REVIEWS + "/501")).willReturn(okJson("{\"id\":501}")));
        sink.updateReviewBody(REPO, 42, "501", "TEST new body");
        server.verify(1, putRequestedFor(urlEqualTo(REVIEWS + "/501")).withRequestBody(equalToJson("{\"body\":\"TEST new body\"}")));
    }

    @Test
    void dismissesAStandingChangesRequested() {
        stubReviews("""
                [ {"id":70,"state":"APPROVED","user":{"login":"TEST-bot"}},
                  {"id":77,"state":"CHANGES_REQUESTED","user":{"login":"TEST-bot"}},
                  {"id":78,"state":"COMMENTED","user":{"login":"TEST-bot"}},
                  {"id":79,"state":"CHANGES_REQUESTED","user":{"login":"TEST-human"}} ]
                """);
        server.stubFor(put(urlEqualTo(REVIEWS + "/77/dismissals")).willReturn(okJson("{\"id\":77,\"state\":\"DISMISSED\"}")));
        Optional<String> dismissed = sink.dismissOwnChangesRequested(REPO, 42, "TEST reason");
        assertEquals(Optional.of("77"), dismissed, "a later COMMENTED review does not replace the hold; a human's hold is not ours");
        server.verify(1, putRequestedFor(urlEqualTo(REVIEWS + "/77/dismissals")).withRequestBody(equalToJson("{\"message\":\"TEST reason\"}")));
    }

    @Test
    void noDismissalWhenTheLatestIsApprovedOrDismissed() {
        stubReviews("""
                [ {"id":77,"state":"CHANGES_REQUESTED","user":{"login":"TEST-bot"}},
                  {"id":80,"state":"DISMISSED","user":{"login":"TEST-bot"}} ]
                """);
        assertTrue(sink.dismissOwnChangesRequested(REPO, 42, "TEST reason").isEmpty());
        server.verify(0, putRequestedFor(urlEqualTo(REVIEWS + "/77/dismissals")));
    }
}
```

- [ ] **Step 2: Commit, push, read CI**

```bash
git add spire-scm-github/src/test
git commit -m "Add failing tests for GitHub native review calls

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: `spire-scm-github` compiles (the port defaults exist) and the tests fail: `postReview` answers
empty so `orElseThrow` throws `NoSuchElementException`; the refusal tests fail on "expected
ReviewVerdictRefused".

- [ ] **Step 3: Add `putJson` to `GitHubClient`**

After `patchJson`:

```java
    public JsonNode putJson(String path, Object body) {
        try {
            return parse(send("PUT", path, JSON_MEDIA, mapper.writeValueAsString(body)));
        } catch (JsonProcessingException e) {
            throw new GitHubApiException(0, "PUT", path, "request serialization failed: " + e.getMessage());
        }
    }
```

- [ ] **Step 4: Write `GitHubReviewApi`**

```java
package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PostedReview;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ReviewSubmission;
import dev.codespire.contract.scm.ReviewSubmission.InlineComment;
import dev.codespire.contract.scm.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The pull request review calls (ADR-047 §5.2): one POST carries verdict, body and inline comments.
 *
 * <p>Package-private: {@link GitHubCommentSink} is the port implementation and delegates here, so the
 * review calls do not grow that class past what a reader can hold.
 */
final class GitHubReviewApi {

    private static final int UNPROCESSABLE = 422;
    /** GitHub's wording for a verdict on one's own pull request, lower-cased at the comparison. */
    private static final String OWN_PULL_REQUEST = "own pull request";
    private static final int PAGE = 100;
    private static final int MAX_PAGES = 20;
    private static final Set<String> STATE_REVIEWS = Set.of("APPROVED", "CHANGES_REQUESTED", "DISMISSED");

    private final GitHubClient client;

    GitHubReviewApi(GitHubClient client) {
        this.client = client;
    }

    PostedReview post(RepoRef repo, long prId, String headCommit, ReviewSubmission review) {
        Map<String, Object> body = new HashMap<>();
        body.put("commit_id", headCommit);
        body.put("event", review.verdict().name());
        body.put("body", review.bodyMd());
        List<Map<String, Object>> comments = new ArrayList<>();
        for (InlineComment comment : review.comments()) {
            comments.add(comment(comment));
        }
        body.put("comments", comments);
        String path = reviewsPath(repo, prId);
        JsonNode created;
        try {
            created = client.postJson(path, body);
        } catch (GitHubApiException e) {
            throw classify(e, review);
        }
        String id = created.path("id").asText("");
        if (id.isBlank()) {
            throw new GitHubApiException(200, "POST", path, "2xx response carried no review id");
        }
        return new PostedReview(id, review.verdict(), readBack(repo, prId, id, review.comments()));
    }

    /** The per-comment mapping {@code GitHubCommentSink.postInline} uses, without {@code commit_id} (top-level). */
    static Map<String, Object> comment(InlineComment comment) {
        InlineAnchor anchor = comment.anchor();
        boolean old = anchor.side() == Side.OLD;
        Map<String, Object> body = new HashMap<>();
        body.put("body", comment.bodyMd());
        body.put("path", anchor.path());
        body.put("side", old ? "LEFT" : "RIGHT");
        if (!old && anchor.endNewLine() != null && anchor.endNewLine() > anchor.newLine()) {
            body.put("start_line", anchor.newLine());
            body.put("start_side", "RIGHT");
            body.put("line", anchor.endNewLine());
        } else {
            body.put("line", old ? anchor.oldLine() : anchor.newLine());
        }
        return body;
    }

    /**
     * Which failure a 422 is (spec §5.2). The anchor error wording is not stable across the API's history,
     * so the rule is by shape: the self-authored wording, else any 422 on a review that carried comments.
     */
    private static RuntimeException classify(GitHubApiException e, ReviewSubmission review) {
        if (e.status() != UNPROCESSABLE) {
            return e;
        }
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (detail.contains(OWN_PULL_REQUEST)) {
            return new CommentSink.ReviewVerdictRefused(e.getMessage(), e);
        }
        if (!review.comments().isEmpty()) {
            return new CommentSink.ReviewAnchorRejected(e.getMessage(), e);
        }
        return e;
    }

    /** Each submitted anchor's comment id, read back from the review, keyed by {@link InlineAnchor#anchorKey()}. */
    Map<String, String> readBack(RepoRef repo, long prId, String reviewId, List<InlineComment> comments) {
        Map<String, String> byKey = new LinkedHashMap<>();
        if (comments.isEmpty()) {
            return byKey;
        }
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode rows = client.getJson(reviewsPath(repo, prId) + "/" + reviewId + "/comments?per_page=" + PAGE + "&page=" + page);
            for (JsonNode row : rows) {
                for (InlineComment comment : comments) {
                    if (matches(row, comment.anchor())) {
                        byKey.putIfAbsent(comment.anchor().anchorKey(), row.path("id").asText());
                    }
                }
            }
            if (rows.size() < PAGE) {
                break;
            }
        }
        return byKey;
    }

    /**
     * GitHub's {@code line} is the END of a range and {@code start_line} its start; the anchor key is the
     * START line ({@link InlineAnchor#anchorKey()}). So: same path, same side, and {@code start_line}
     * when present else {@code line} equals the anchor's line.
     */
    static boolean matches(JsonNode row, InlineAnchor anchor) {
        boolean old = anchor.side() == Side.OLD;
        Integer line = old ? anchor.oldLine() : anchor.newLine();
        int start = row.hasNonNull("start_line") ? row.path("start_line").asInt() : row.path("line").asInt(-1);
        return line != null && line == start
                && anchor.path().equals(row.path("path").asText(""))
                && (old ? "LEFT" : "RIGHT").equals(row.path("side").asText(""));
    }

    /** The bot's latest review of {@code headCommit} whose body starts with the submission's first line. */
    Optional<PostedReview> find(RepoRef repo, long prId, String headCommit, ReviewSubmission review, String botLogin) {
        if (botLogin == null || botLogin.isBlank()) {
            return Optional.empty();
        }
        String prefix = review.firstLine();
        JsonNode found = null;
        for (JsonNode row : walkReviews(repo, prId)) {
            if (botLogin.equals(row.path("user").path("login").asText(""))
                    && headCommit.equals(row.path("commit_id").asText(""))
                    && row.path("body").asText("").startsWith(prefix)) {
                found = row;
            }
        }
        if (found == null) {
            return Optional.empty();
        }
        String id = found.path("id").asText();
        return Optional.of(new PostedReview(id, verdictOf(found.path("state").asText("")),
                readBack(repo, prId, id, review.comments())));
    }

    static ReviewVerdict verdictOf(String state) {
        return switch (state) {
            case "APPROVED" -> ReviewVerdict.APPROVE;
            case "CHANGES_REQUESTED" -> ReviewVerdict.REQUEST_CHANGES;
            default -> ReviewVerdict.COMMENT;
        };
    }

    void updateBody(RepoRef repo, long prId, String reviewId, String bodyMd) {
        client.putJson(reviewsPath(repo, prId) + "/" + reviewId, Map.of("body", bodyMd));
    }

    /**
     * D11. A reviewer's state on a pull request is its latest APPROVED / CHANGES_REQUESTED / DISMISSED
     * review (a COMMENTED one replaces nothing): when that is CHANGES_REQUESTED, dismiss it.
     */
    Optional<String> dismissChangesRequested(RepoRef repo, long prId, String botLogin, String message) {
        if (botLogin == null || botLogin.isBlank()) {
            return Optional.empty();
        }
        JsonNode latest = null;
        for (JsonNode row : walkReviews(repo, prId)) {
            if (botLogin.equals(row.path("user").path("login").asText(""))
                    && STATE_REVIEWS.contains(row.path("state").asText(""))) {
                latest = row;
            }
        }
        if (latest == null || !"CHANGES_REQUESTED".equals(latest.path("state").asText(""))) {
            return Optional.empty();
        }
        String id = latest.path("id").asText();
        client.putJson(reviewsPath(repo, prId) + "/" + id + "/dismissals", Map.of("message", message));
        return Optional.of(id);
    }

    private List<JsonNode> walkReviews(RepoRef repo, long prId) {
        List<JsonNode> rows = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode pageRows = client.getJson(reviewsPath(repo, prId) + "?per_page=" + PAGE + "&page=" + page);
            pageRows.forEach(rows::add);
            if (pageRows.size() < PAGE) {
                break;
            }
        }
        return rows;
    }

    private static String reviewsPath(RepoRef repo, long prId) {
        return "/repos/" + repo.full() + "/pulls/" + prId + "/reviews";
    }
}
```

- [ ] **Step 5: Delegate from `GitHubCommentSink`**

Add the field and build it in both constructors (the two-arg one):

```java
    private final GitHubReviewApi reviews;
    // in the (client, botLogin) constructor:
        this.reviews = new GitHubReviewApi(client);
```

Append the overrides (imports: `PostedReview`, `ReviewSubmission`, `java.util.Optional`):

```java
    @Override
    public Optional<PostedReview> findReview(RepoRef repo, long prId, String headCommit, ReviewSubmission review) {
        return reviews.find(repo, prId, headCommit, review, botLogin());
    }

    @Override
    public Optional<PostedReview> postReview(RepoRef repo, long prId, String headCommit, ReviewSubmission review) {
        return Optional.of(reviews.post(repo, prId, headCommit, review));
    }

    @Override
    public void updateReviewBody(RepoRef repo, long prId, String reviewRef, String bodyMd) {
        reviews.updateBody(repo, prId, reviewRef, bodyMd);
    }

    @Override
    public Optional<String> dismissOwnChangesRequested(RepoRef repo, long prId, String message) {
        return reviews.dismissChangesRequested(repo, prId, botLogin(), message);
    }
```

Update the class javadoc's first sentence: "The PR summary is an ISSUE comment on the legacy path; the
native path posts a review (ADR-047), see {@link GitHubReviewApi}."

- [ ] **Step 6: Commit, push, read CI**

```bash
git add spire-scm-github
git commit -m "Post, find, update and dismiss a GitHub pull request review

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: green.

**Mutation checks:** (a) `matches` uses `line` when `start_line` present → `postsAnApprovingReviewWithInlineCommentsInOneCall`
fails; (b) `classify` drops the `comments.isEmpty()` test → `aBodyOnly422IsAPlainFailure` fails;
(c) `dismissChangesRequested` ignores `STATE_REVIEWS` → `dismissesAStandingChangesRequested` fails (78 wins);
(d) `find` ignores `commit_id` → `findReviewIsEmptyWhenTheBotHasNotPostedForThisHead` fails.

---

### Task 3: GitHub adapter — `review:` thread refs and `requestReviewers`

**Files:**
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubCommentSink.java:109-132, 248-256`
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubPullRequestSink.java` (append)
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubReviewThreadTest.java`
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubPullRequestSinkTest.java` (append)

**Interfaces:**
- Produces: `GitHubCommentSink.REVIEW_REF_PREFIX = "review:"`; `GitHubPullRequestSink.requestReviewers`.

- [ ] **Step 1: Write the failing thread tests (mock client, like `GitHubThreadFetchTest`)**

```java
package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A {@code review:<id>} thread ref (ADR-047 D8): the summary is a review, not an issue comment. */
class GitHubReviewThreadTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RepoRef repo = new RepoRef("TEST-owner", "TEST-repo");

    @Test
    void aReplyToAReviewRefPostsAnIssueComment() throws Exception {
        GitHubClient client = mock(GitHubClient.class);
        when(client.postJson("/repos/TEST-owner/TEST-repo/issues/5/comments", Map.of("body", "TEST answer")))
                .thenReturn(mapper.readTree("{\"id\":777}"));
        CommentRef posted = new GitHubCommentSink(client, "TEST-bot").replyInThread(repo, 5, new ThreadRef("review:501"), "TEST answer");
        assertEquals("777", posted.commentId());
        verify(client, never()).postJson(contains("/replies"), any());
    }

    @Test
    void fetchesAReviewRefAsBodyThenLaterComments() throws Exception {
        GitHubClient client = mock(GitHubClient.class);
        when(client.getJson("/repos/TEST-owner/TEST-repo/pulls/5/reviews/501")).thenReturn(mapper.readTree("""
                {"id":501,"body":"### Code Spire review (`abc`)\\n\\nTEST summary","submitted_at":"2026-10-15T10:00:00Z","user":{"login":"TEST-bot"}}
                """));
        when(client.getJson("/repos/TEST-owner/TEST-repo/issues/5/comments?per_page=100&page=1")).thenReturn(mapper.readTree("""
                [ {"id":1,"body":"before the review","created_at":"2026-10-15T09:59:00Z","user":{"login":"TEST-human"}},
                  {"id":2,"body":"why this?","created_at":"2026-10-15T10:01:00Z","user":{"login":"TEST-human"}} ]
                """));
        ThreadTranscript t = new GitHubCommentSink(client, "TEST-bot").fetchThread(repo, 5, new ThreadRef("review:501"));
        assertNull(t.path());
        assertEquals(2, t.messages().size(), "the body, then only the comments after submitted_at");
        assertTrue(t.messages().get(0).fromBot());
        assertTrue(t.messages().get(0).text().startsWith("### Code Spire review"));
        assertEquals("why this?", t.messages().get(1).text());
        assertFalse(t.messages().get(1).fromBot());
    }

    @Test
    void updatingAReviewRefAsACommentIsRefused() {
        GitHubClient client = mock(GitHubClient.class);
        assertThrows(UnsupportedOperationException.class,
                () -> new GitHubCommentSink(client, "TEST-bot").updateComment(repo, 5, new ThreadRef("review:501"), "x"));
    }
}
```

Append to `GitHubPullRequestSinkTest`:

```java
    private static final String REQUESTED = PULLS + "/7/requested_reviewers";
    private static final PullRequestRef PR_7 = new PullRequestRef(7, "https://forge.example.test/TEST-pull/7", false);

    @Test
    void requestsAReviewer() {
        wireMock.stubFor(post(urlEqualTo(REQUESTED)).willReturn(aResponse().withStatus(201).withBody("{}")));
        sink.requestReviewers(REPO, PR_7, java.util.List.of("TEST-reviewer"));
        wireMock.verify(1, postRequestedFor(urlEqualTo(REQUESTED)).withRequestBody(equalToJson("{\"reviewers\":[\"TEST-reviewer\"]}")));
    }

    @Test
    void aNonCollaboratorRequestIsDeliveryUnavailable() {
        wireMock.stubFor(post(urlEqualTo(REQUESTED)).willReturn(aResponse().withStatus(422)
                .withBody("{\"message\":\"Reviews may only be requested from collaborators.\"}")));
        var refusal = assertThrows(PullRequestSink.DeliveryUnavailable.class,
                () -> sink.requestReviewers(REPO, PR_7, java.util.List.of("TEST-reviewer")));
        assertEquals("reviewer_not_collaborator", refusal.getMessage());
    }

    @Test
    void aServerFaultRequestingAReviewerPropagates() {
        wireMock.stubFor(post(urlEqualTo(REQUESTED)).willReturn(aResponse().withStatus(503)));
        assertThrows(GitHubApiException.class, () -> sink.requestReviewers(REPO, PR_7, java.util.List.of("TEST-reviewer")));
    }
```

- [ ] **Step 2: Commit, push, read CI**

```bash
git add spire-scm-github/src/test
git commit -m "Add failing tests for review thread refs and reviewer requests

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: `spire-scm-github` red: the reply test fails (the sink calls `/replies` first and the mock
returns null → NPE), the fetch test fails (the mock has no stub for the review-comments walk → NPE), the
request tests fail on `request_reviewers_unsupported`.

- [ ] **Step 3: Implement the thread-ref behaviour**

In `GitHubCommentSink`:

```java
    /** A summary posted as a review (ADR-047 D8): its thread ref is this prefix plus the review id. */
    public static final String REVIEW_REF_PREFIX = "review:";

    private static boolean isReviewRef(ThreadRef thread) {
        return thread.value().startsWith(REVIEW_REF_PREFIX);
    }
```

`replyInThread`: before the `try`, add

```java
        if (isReviewRef(thread)) {
            // A review has no reply thread on GitHub: the reply is the next top-level PR comment, which
            // is what the 404 fallback below does for a plain root — taken directly, without the failing call.
            String issuePath = issueCommentsPath(repo, prId);
            JsonNode created = client.postJson(issuePath, Map.of("body", bodyMd));
            return new CommentRef(requireCommentId(created, issuePath), thread, CommentKind.REPLY);
        }
```

`updateComment`: first line

```java
        if (isReviewRef(thread)) {
            throw new UnsupportedOperationException("a GitHub review body is rewritten through updateReviewBody, not as a comment");
        }
```

`fetchThread`: first lines

```java
        if (isReviewRef(thread)) {
            return reviewTranscript(repo, prId, thread, botLogin());
        }
```

and the new method:

```java
    /**
     * The review body first, then every top-level PR comment created after it was submitted — the same
     * walk {@link #issueThreadTranscript} does, keyed by time instead of by root id, so a follow-up still
     * sees the question it answers (spec §5.2).
     */
    private ThreadTranscript reviewTranscript(RepoRef repo, long prId, ThreadRef thread, String botLogin) {
        String reviewId = thread.value().substring(REVIEW_REF_PREFIX.length());
        JsonNode review = client.getJson(prPath(repo, prId) + "/reviews/" + reviewId);
        java.time.Instant submitted = java.time.Instant.parse(review.path("submitted_at").asText("1970-01-01T00:00:00Z"));
        List<ThreadMessage> messages = new ArrayList<>();
        messages.add(toMessage(review, botLogin));
        for (int page = 1; page <= MAX_THREAD_PAGES; page++) {
            JsonNode pageComments = client.getJson(issueCommentsPath(repo, prId) + "?per_page=100&page=" + page);
            int count = 0;
            for (JsonNode c : pageComments) {
                count++;
                if (java.time.Instant.parse(c.path("created_at").asText("1970-01-01T00:00:00Z")).isAfter(submitted)) {
                    messages.add(toMessage(c, botLogin));
                }
            }
            if (count < 100) {
                break;
            }
            warnIfTranscriptTruncated(thread.value(), page);
        }
        return new ThreadTranscript(thread, null, 0, null, messages);
    }
```

- [ ] **Step 4: Implement `requestReviewers`**

Append to `GitHubPullRequestSink` (import `java.util.List`):

```java
    /** ADR-047 D9. GitHub answers 422 for a login that is not a collaborator of the repository. */
    @Override
    public void requestReviewers(RepoRef repo, PullRequestRef pullRequest, List<String> logins) {
        String path = pullsPath(repo) + "/" + pullRequest.number() + "/requested_reviewers";
        try {
            client.postJson(path, Map.of("reviewers", logins));
        } catch (GitHubApiException e) {
            if (e.status() == UNPROCESSABLE) {
                throw new PullRequestSink.DeliveryUnavailable("reviewer_not_collaborator");
            }
            throw e;
        }
    }
```

- [ ] **Step 5: Commit, push, read CI**

```bash
git add spire-scm-github
git commit -m "Treat a review as the summary thread and request reviewers on GitHub

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: green. Re-read `GitHubThreadFetchTest.fetchThreadFallsBackToIssueCommentsWhenNoRootMatchesAReviewComment`
in the run: it must still pass (Review Focus 5).

**Mutation checks:** (a) `reviewTranscript` drops the `isAfter` test → `fetchesAReviewRefAsBodyThenLaterComments`
fails; (b) `requestReviewers` rethrows the 422 → `aNonCollaboratorRequestIsDeliveryUnavailable` fails.

---

### Task 4: Worker — the native review path

**Files:**
- Create: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/NativeReviewPost.java`
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ReviewWorker.java:548-601`
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/ReviewWorkerTest.java` (RecordingSink and new tests)

**Interfaces:**
- Consumes: Task 1 records and ports; `CommentIdempotencyStore.claim/markPosted/postedFor`.
- Produces: `NativeReviewPost.REVIEW_KEY = "REVIEW"`, `NativeReviewPost.REF_PREFIX = "review:"`,
  `NativeReviewPost.withVerdictLine(String body, ReviewVerdict assessment)`,
  `record Outcome(String reviewRef, ReviewVerdict postedVerdict, List<CommentsPosted.PostedInline> inline)`,
  `Optional<Outcome> run()`.

- [ ] **Step 1: Extend `RecordingSink` in `ReviewWorkerTest`**

Add fields and overrides inside `RecordingSink` (imports: `PostedReview`, `ReviewSubmission`, `ReviewVerdict`,
`java.util.ArrayDeque`, `java.util.Deque`, `java.util.Optional`):

```java
        /** The native path answers (true) or stays the port default, empty (false). */
        boolean nativeReviews;
        final List<ReviewSubmission> reviewPosts = new ArrayList<>();
        /** Thrown by successive postReview calls, first in first out; a successful call records instead. */
        final Deque<RuntimeException> reviewFailures = new ArrayDeque<>();
        Optional<PostedReview> foundReview = Optional.empty();
        final List<String> updatedReviewBodies = new ArrayList<>();
        final List<String> dismissals = new ArrayList<>();
        /** Every native-path call in order: review, inline, body, dismiss. */
        final List<String> calls = new ArrayList<>();

        @Override
        public Optional<PostedReview> findReview(RepoRef repo, long prId, String headCommit, ReviewSubmission review) {
            return nativeReviews ? foundReview : Optional.empty();
        }

        @Override
        public Optional<PostedReview> postReview(RepoRef repo, long prId, String headCommit, ReviewSubmission review) {
            if (!nativeReviews) {
                return Optional.empty();
            }
            if (!reviewFailures.isEmpty()) {
                throw reviewFailures.pop();
            }
            calls.add("review");
            reviewPosts.add(review);
            String id = "rv-" + ids++;
            Map<String, String> threads = new HashMap<>();
            for (ReviewSubmission.InlineComment comment : review.comments()) {
                threads.putIfAbsent(comment.anchor().anchorKey(), "c-" + ids++);
            }
            return Optional.of(new PostedReview(id, review.verdict(), threads));
        }

        @Override
        public void updateReviewBody(RepoRef repo, long prId, String reviewRef, String bodyMd) {
            calls.add("body");
            updatedReviewBodies.add(bodyMd);
        }

        @Override
        public Optional<String> dismissOwnChangesRequested(RepoRef repo, long prId, String message) {
            calls.add("dismiss");
            dismissals.add(message);
            return Optional.of("rv-old");
        }
```

In the existing `postInline` override add `calls.add("inline");` right before `String id = "c-" + ids++;`.

- [ ] **Step 2: Write the failing worker tests**

Add to `ReviewWorkerTest` (uses the existing `postCommand(List<Finding>)`, `finding(path, line)`,
`lastCommentsPosted()` helpers):

```java
    private PostComments nativeCommand(List<Finding> findings, ReviewVerdict assessment, boolean mayApprove) {
        return postCommand(findings).withVerdict(assessment, mayApprove ? assessment : ReviewVerdict.COMMENT, mayApprove);
    }

    private static final String KEY_A2 = "src/A.java:2:NEW";

    @Test
    void aVerdictPostsOneNativeReviewAndNoSummaryComment() {
        sink.nativeReviews = true;
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));

        assertEquals(1, sink.reviewPosts.size());
        assertEquals(ReviewVerdict.APPROVE, sink.reviewPosts.getFirst().verdict());
        assertEquals(1, sink.reviewPosts.getFirst().comments().size(), "the inline finding rides inside the review");
        assertTrue(sink.summaryPosts.isEmpty(), "no summary issue comment on the native path");
        assertTrue(sink.inlinePosts.isEmpty(), "no separate inline post on the native path");
        CommentsPosted posted = lastCommentsPosted();
        assertEquals("review:rv-100", posted.summaryThreadRef());
        assertEquals("rv-100", posted.reviewRef());
        assertEquals(ReviewVerdict.APPROVE, posted.postedVerdict());
        assertEquals(1, posted.inline().size());
        assertEquals("src/A.java", posted.inline().getFirst().path());
        assertEquals("review:rv-100", idempotency.postedFor(REVIEW_ID, COMMIT).get("REVIEW"));
    }

    @Test
    void aNullAssessmentPostsAsBefore() {
        sink.nativeReviews = true;
        worker.postComments(postCommand(List.of(finding("src/A.java", 2))));
        assertTrue(sink.reviewPosts.isEmpty());
        assertEquals(1, sink.summaryPosts.size());
        assertEquals(1, sink.inlinePosts.size());
        assertNull(lastCommentsPosted().postedVerdict());
    }

    @Test
    void aForgeWithoutNativeReviewsPostsAsBefore() {
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));
        assertEquals(1, sink.summaryPosts.size());
        assertEquals(1, sink.inlinePosts.size());
        assertNull(lastCommentsPosted().postedVerdict());
        assertNull(lastCommentsPosted().reviewRef());
    }

    @Test
    void aRefusedVerdictIsPostedAgainAsAComment() {
        sink.nativeReviews = true;
        sink.reviewFailures.add(new CommentSink.ReviewVerdictRefused("TEST own pull request", null));
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));

        assertEquals(1, sink.reviewPosts.size(), "exactly one successful post after the refusal");
        assertEquals(ReviewVerdict.COMMENT, sink.reviewPosts.getFirst().verdict());
        assertTrue(sink.reviewPosts.getFirst().bodyMd().contains("**Verdict:** approve"));
        assertEquals(ReviewVerdict.COMMENT, lastCommentsPosted().postedVerdict());
    }

    @Test
    void aSecondRefusalIsAFailure() {
        sink.nativeReviews = true;
        sink.reviewFailures.add(new CommentSink.ReviewVerdictRefused("TEST own pull request", null));
        sink.reviewFailures.add(new CommentSink.ReviewVerdictRefused("TEST own pull request", null));
        worker.postComments(nativeCommand(List.of(), ReviewVerdict.APPROVE, true));
        assertTrue(sink.reviewPosts.isEmpty());
        assertInstanceOf(ReviewFailed.class, emitted.getLast());
    }

    @Test
    void aRejectedAnchorFallsBackToBodyThenInlineThenBodyUpdate() {
        sink.nativeReviews = true;
        sink.reviewFailures.add(new CommentSink.ReviewAnchorRejected("TEST line must be part of the diff", null));
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.REQUEST_CHANGES, true));

        assertEquals(List.of("review", "inline", "body"), sink.calls);
        assertTrue(sink.reviewPosts.getFirst().comments().isEmpty(), "the retry carries the body only");
        assertEquals(ReviewVerdict.REQUEST_CHANGES, sink.reviewPosts.getFirst().verdict(), "same verdict");
        assertEquals(1, sink.inlinePosts.size());
        CommentsPosted posted = lastCommentsPosted();
        assertEquals(1, posted.inline().size());
        assertEquals("c-101", posted.inline().getFirst().threadRef(), "the inline fallback's thread is the finding's");
    }

    @Test
    void aRedeliveryAfterTheReviewWasPostedDoesNotPostTwice() {
        sink.nativeReviews = true;
        idempotency.markPosted(REVIEW_ID, COMMIT, "REVIEW", "review:rv-9");
        idempotency.markPosted(REVIEW_ID, COMMIT, KEY_A2, "c-9");
        sink.foundReview = Optional.of(new PostedReview("rv-9", ReviewVerdict.APPROVE, Map.of(KEY_A2, "c-9")));
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));

        assertTrue(sink.reviewPosts.isEmpty(), "the REVIEW claim is honoured");
        CommentsPosted posted = lastCommentsPosted();
        assertEquals("rv-9", posted.reviewRef());
        assertEquals(ReviewVerdict.APPROVE, posted.postedVerdict());
        assertEquals(List.of("c-9"), posted.inline().stream().map(CommentsPosted.PostedInline::threadRef).toList());
    }

    @Test
    void aCrashAfterThePostDoesNotPostASecondReview() {
        sink.nativeReviews = true;
        idempotency.claim(REVIEW_ID, COMMIT, "REVIEW"); // claimed by an attempt that died before marking
        sink.foundReview = Optional.of(new PostedReview("rv-9", ReviewVerdict.APPROVE, Map.of(KEY_A2, "c-9")));
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));

        assertTrue(sink.reviewPosts.isEmpty(), "the review GitHub already holds is reused");
        assertEquals("review:rv-9", idempotency.postedFor(REVIEW_ID, COMMIT).get("REVIEW"), "the mark is written now");
        assertEquals("c-9", idempotency.postedFor(REVIEW_ID, COMMIT).get(KEY_A2));
        assertEquals("rv-9", lastCommentsPosted().reviewRef());
    }

    @Test
    void theSettingOffVerdictLineIsInTheBody() {
        sink.nativeReviews = true;
        worker.postComments(nativeCommand(List.of(), ReviewVerdict.APPROVE, false));
        ReviewSubmission review = sink.reviewPosts.getFirst();
        assertEquals(ReviewVerdict.COMMENT, review.verdict());
        assertTrue(review.bodyMd().startsWith("### Code Spire review (`" + COMMIT + "`)\n\n**Verdict:** approve\n\n"), review.bodyMd());
    }

    @Test
    void theSettingOffRequestChangesLineIsInTheBody() {
        sink.nativeReviews = true;
        worker.postComments(nativeCommand(List.of(), ReviewVerdict.REQUEST_CHANGES, false));
        assertTrue(sink.reviewPosts.getFirst().bodyMd().contains("**Verdict:** changes requested"));
    }

    @Test
    void aDegradedRunWithTheSettingOnHasNoVerdictLine() {
        sink.nativeReviews = true;
        worker.postComments(nativeCommand(List.of(), ReviewVerdict.COMMENT, true));
        assertFalse(sink.reviewPosts.getFirst().bodyMd().contains("**Verdict:**"));
        assertTrue(sink.dismissals.isEmpty(), "a degraded run with the setting on keeps the hold (D5)");
    }

    @Test
    void theSettingOffDismissesAStandingHoldAfterThePost() {
        sink.nativeReviews = true;
        worker.postComments(nativeCommand(List.of(), ReviewVerdict.APPROVE, false));
        assertEquals(List.of("review", "dismiss"), sink.calls);
        assertEquals("Superseded by review rv-100: the reviewer may not change merge state on this repository",
                sink.dismissals.getFirst());
    }

    @Test
    void theSettingOnDoesNotDismiss() {
        sink.nativeReviews = true;
        worker.postComments(nativeCommand(List.of(), ReviewVerdict.APPROVE, true));
        assertTrue(sink.dismissals.isEmpty());
    }

    @Test
    void aReviewRefIsNotAFindingThreadOnRedelivery() {
        sink.nativeReviews = true;
        idempotency.markPosted(REVIEW_ID, COMMIT, "REVIEW", "review:rv-9");
        idempotency.markPosted(REVIEW_ID, COMMIT, KEY_A2, "c-9");
        sink.foundReview = Optional.of(new PostedReview("rv-9", ReviewVerdict.APPROVE, Map.of()));
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));
        CommentsPosted posted = lastCommentsPosted();
        assertEquals(1, posted.inline().size());
        assertFalse(posted.inline().stream().anyMatch(i -> i.threadRef().startsWith("review:")),
                "the REVIEW claim must never be rebuilt as an inline finding thread");
    }

    @Test
    void twoFindingsAtOneAnchorShareOneThread() {
        sink.nativeReviews = true;
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2), finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));
        assertEquals(2, sink.reviewPosts.getFirst().comments().size(), "both comments are posted");
        assertEquals(1, lastCommentsPosted().inline().size(), "one anchor key, one owned thread");
    }
```

- [ ] **Step 3: Commit, push, read CI**

```bash
git add spire-review-worker/src/test
git commit -m "Add failing tests for the native review posting path

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: `spire-review-worker` red; `aVerdictPostsOneNativeReviewAndNoSummaryComment` fails on
`assertEquals(1, sink.reviewPosts.size())` (0 — the worker never calls `postReview`).

- [ ] **Step 4: Write `NativeReviewPost`**

```java
package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PostedReview;
import dev.codespire.contract.scm.ReviewSubmission;
import dev.codespire.contract.scm.ReviewSubmission.InlineComment;
import dev.codespire.diff.Anchors;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The forge-native review path of PostComments (ADR-047, spec §5.1): claim, find, post, mark at once,
 * read back, fall back, update the body, dismiss a hold. Package-private; {@link ReviewWorker} owns it
 * and supplies the rendering and the legacy inline poster it already has.
 */
final class NativeReviewPost {

    static final String REVIEW_KEY = "REVIEW";
    static final String REF_PREFIX = "review:";
    private static final Logger LOG = Logger.getLogger(NativeReviewPost.class);
    private static final int MAX_POST_ATTEMPTS = 3;

    /** Posts one inline comment the legacy way: the worker's backoff and throttle. */
    interface InlinePoster {
        CommentRef post(InlineAnchor anchor, String bodyMd);
    }

    /** Renders the summary body for the given unanchored and failed lists. */
    interface BodyRenderer {
        String render(List<Finding> unanchored, List<Finding> failed);
    }

    record Outcome(String reviewRef, ReviewVerdict postedVerdict, List<CommentsPosted.PostedInline> inline) {
    }

    private record Anchored(InlineAnchor anchor, List<Finding> findings) {
    }

    private record Attempt(PostedReview review, boolean bodyOnly) {
    }

    private final CommentIdempotencyStore idempotency;
    private final CommentSink sink;
    private final PostComments command;
    private final Diff diff;
    private final BodyRenderer bodies;
    private final InlinePoster inlinePoster;
    private final Function<Finding, String> inlineBodies;

    NativeReviewPost(CommentIdempotencyStore idempotency, CommentSink sink, PostComments command, Diff diff,
                     BodyRenderer bodies, InlinePoster inlinePoster, Function<Finding, String> inlineBodies) {
        this.idempotency = idempotency;
        this.sink = sink;
        this.command = command;
        this.diff = diff;
        this.bodies = bodies;
        this.inlinePoster = inlinePoster;
        this.inlineBodies = inlineBodies;
    }

    /** @return empty when the forge has no native review: the caller runs the legacy path. */
    Optional<Outcome> run() {
        List<Finding> unanchored = new ArrayList<>();
        List<Finding> failed = new ArrayList<>();
        Map<String, Anchored> anchored = resolveAnchors(unanchored);
        Map<String, String> alreadyPosted = claimAnchors(anchored.keySet());
        List<InlineComment> comments = commentsFor(anchored, alreadyPosted);

        boolean inlineFallback = false;
        PostedReview review;
        if (idempotency.claim(command.reviewId(), command.commit(), REVIEW_KEY)
                instanceof CommentIdempotencyStore.Claim.AlreadyPosted marked) {
            String ref = marked.postedRef().substring(REF_PREFIX.length());
            review = sink.findReview(command.repo(), command.prId(), command.commit(), submission(command.verdict(), unanchored, failed, comments))
                    .orElse(new PostedReview(ref, null, Map.of()));
        } else {
            // The claim does not say whether it was fresh or reclaimed, so look first: an attempt that
            // died between the POST and the mark left a review GitHub still holds (spec §5.1 step 3).
            Optional<PostedReview> found = sink.findReview(command.repo(), command.prId(), command.commit(),
                    submission(command.verdict(), unanchored, failed, comments));
            if (found.isPresent()) {
                review = found.get();
            } else {
                Attempt attempt = postWithFallbacks(unanchored, failed, comments);
                if (attempt == null) {
                    return Optional.empty();
                }
                review = attempt.review();
                inlineFallback = attempt.bodyOnly();
            }
            // At once, before any read-back or fallback: everything after this is a read, a per-anchor
            // claimed post, or an idempotent PUT, so a crash later re-enters here and finds the mark.
            idempotency.markPosted(command.reviewId(), command.commit(), REVIEW_KEY, REF_PREFIX + review.reviewRef());
        }

        List<CommentsPosted.PostedInline> inline = settleInline(anchored, alreadyPosted, review, inlineFallback, failed);
        if (inlineFallback || !failed.isEmpty()) {
            sink.updateReviewBody(command.repo(), command.prId(), review.reviewRef(),
                    body(unanchored, failed, review.verdict() == null ? command.verdict() : review.verdict()));
        }
        if (Boolean.FALSE.equals(command.reviewerMayApprove())) {
            dismissQuietly(review.reviewRef());
        }
        return Optional.of(new Outcome(review.reviewRef(), review.verdict(), inline));
    }

    private Map<String, Anchored> resolveAnchors(List<Finding> unanchored) {
        Map<String, Anchored> anchored = new LinkedHashMap<>();
        for (Finding finding : command.findings().findings()) {
            Optional<InlineAnchor> anchor = Anchors.resolveLine(
                    diff.files(), finding.path(), finding.range().startLine(), finding.range().endLine());
            if (anchor.isEmpty()) {
                unanchored.add(finding);
                continue;
            }
            anchored.computeIfAbsent(anchor.get().anchorKey(), key -> new Anchored(anchor.get(), new ArrayList<>()))
                    .findings().add(finding);
        }
        return anchored;
    }

    /** Claims every anchor key before any call; answers the keys a previous attempt already posted. */
    private Map<String, String> claimAnchors(Iterable<String> keys) {
        Map<String, String> alreadyPosted = new LinkedHashMap<>();
        for (String key : keys) {
            if (idempotency.claim(command.reviewId(), command.commit(), key)
                    instanceof CommentIdempotencyStore.Claim.AlreadyPosted already) {
                alreadyPosted.put(key, already.postedRef());
            }
        }
        return alreadyPosted;
    }

    private List<InlineComment> commentsFor(Map<String, Anchored> anchored, Map<String, String> alreadyPosted) {
        List<InlineComment> comments = new ArrayList<>();
        anchored.forEach((key, entry) -> {
            if (!alreadyPosted.containsKey(key)) {
                for (Finding finding : entry.findings()) {
                    comments.add(new InlineComment(entry.anchor(), inlineBodies.apply(finding)));
                }
            }
        });
        return comments;
    }

    private ReviewSubmission submission(ReviewVerdict posting, List<Finding> unanchored, List<Finding> failed,
                                        List<InlineComment> comments) {
        return new ReviewSubmission(posting, body(unanchored, failed, posting), comments);
    }

    /** The verdict in words when the posted verdict is COMMENT and the assessment is not (D3, D6). */
    private String body(List<Finding> unanchored, List<Finding> failed, ReviewVerdict posting) {
        String rendered = bodies.render(unanchored, failed);
        return posting == ReviewVerdict.COMMENT && command.assessment() != ReviewVerdict.COMMENT
                ? withVerdictLine(rendered, command.assessment()) : rendered;
    }

    static String withVerdictLine(String body, ReviewVerdict assessment) {
        String line = "**Verdict:** " + (assessment == ReviewVerdict.APPROVE ? "approve" : "changes requested") + "\n\n";
        int cut = body.indexOf("\n\n");
        return cut < 0 ? body + "\n\n" + line : body.substring(0, cut + 2) + line + body.substring(cut + 2);
    }

    /**
     * Full review; on a refusal once more as COMMENT with the verdict line (D6); on a rejected anchor
     * once more with the body only (D7). At most three posts.
     *
     * @return null when the forge has no native review
     */
    private Attempt postWithFallbacks(List<Finding> unanchored, List<Finding> failed, List<InlineComment> comments) {
        ReviewVerdict posting = command.verdict();
        List<InlineComment> current = comments;
        boolean refused = false;
        boolean bodyOnly = false;
        for (int attempt = 1; attempt <= MAX_POST_ATTEMPTS; attempt++) {
            try {
                Optional<PostedReview> posted = sink.postReview(command.repo(), command.prId(), command.commit(),
                        submission(posting, unanchored, failed, current));
                if (posted.isEmpty()) {
                    return null;
                }
                return new Attempt(posted.get(), bodyOnly);
            } catch (CommentSink.ReviewVerdictRefused e) {
                if (refused) {
                    throw e;
                }
                LOG.infof("review %s: the forge refused %s — posting as COMMENT with the verdict in words", command.reviewId(), posting);
                refused = true;
                posting = ReviewVerdict.COMMENT;
            } catch (CommentSink.ReviewAnchorRejected e) {
                if (bodyOnly) {
                    throw e;
                }
                LOG.infof("review %s: the forge rejected an anchor — posting the body, then the findings one by one", command.reviewId());
                bodyOnly = true;
                current = List.of();
            }
        }
        throw new IllegalStateException("review post attempts exhausted for " + command.reviewId());
    }

    private List<CommentsPosted.PostedInline> settleInline(Map<String, Anchored> anchored, Map<String, String> alreadyPosted,
                                                            PostedReview review, boolean inlineFallback, List<Finding> failed) {
        List<CommentsPosted.PostedInline> inline = new ArrayList<>();
        anchored.forEach((key, entry) -> {
            Finding first = entry.findings().getFirst();
            String thread = alreadyPosted.get(key);
            if (thread == null) {
                thread = review.inlineThreads().get(key);
            }
            if (thread == null && inlineFallback) {
                thread = postInlineQuietly(entry.anchor(), first);
            }
            if (thread == null) {
                failed.addAll(entry.findings());
                return;
            }
            if (!alreadyPosted.containsKey(key)) {
                idempotency.markPosted(command.reviewId(), command.commit(), key, thread);
            }
            inline.add(new CommentsPosted.PostedInline(thread, first.path(), first.range().startLine()));
        });
        return inline;
    }

    /** Per-finding isolation, as the legacy path: one failure never aborts the batch; the claim stays reclaimable. */
    private String postInlineQuietly(InlineAnchor anchor, Finding finding) {
        try {
            return inlinePoster.post(anchor, inlineBodies.apply(finding)).thread().value();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Inline post failed for %s at %s", command.reviewId(), anchor.anchorKey());
            return null;
        }
    }

    /** D11. A failure here must not fail the run: the Comment review is posted, and the next run tries again. */
    private void dismissQuietly(String reviewRef) {
        try {
            sink.dismissOwnChangesRequested(command.repo(), command.prId(),
                    "Superseded by review " + reviewRef + ": the reviewer may not change merge state on this repository")
                    .ifPresent(dismissed -> LOG.infof("review %s: dismissed the standing changes-requested review %s", command.reviewId(), dismissed));
        } catch (RuntimeException e) {
            LOG.warnf(e, "review %s: could not dismiss the standing changes-requested review after posting %s", command.reviewId(), reviewRef);
        }
    }
}
```

Note the `Anchors` import: it is the same class `ReviewWorker.postOneInline` uses; copy its import line.

- [ ] **Step 5: Wire it into `ReviewWorker.postComments`**

Replace the block from `List<CommentsPosted.PostedInline> posted = new ArrayList<>();` to the
`results.emit(...)` at the end of the `try` with:

```java
            BackoffBudget backoffBudget = new BackoffBudget(rateLimitBudgetSeconds);
            if (command.assessment() != null) {
                Optional<NativeReviewPost.Outcome> nativeOutcome = new NativeReviewPost(idempotency, commentSink, command, diff,
                        (unanchored, failed) -> renderSummary(review, unanchored, failed, command.commit(), command.suppressedCount())
                                + renderReconciliationSection(command.verdicts()),
                        (anchor, body) -> {
                            CommentRef ref = postInlineWithBackoff(commentSink, command, diff, anchor, body, backoffBudget);
                            pace();
                            return ref;
                        },
                        this::renderInline).run();
                if (nativeOutcome.isPresent()) {
                    NativeReviewPost.Outcome outcome = nativeOutcome.get();
                    List<CommentsPosted.PostedInline> posted = new ArrayList<>(outcome.inline());
                    recoverPreviouslyPosted(previouslyPosted, posted);
                    results.emit(new CommentsPosted(command.reviewId(), command.prId(), command.commit(),
                            NativeReviewPost.REF_PREFIX + outcome.reviewRef(), posted, outcomes)
                            .withPostedReview(outcome.postedVerdict(), outcome.reviewRef()));
                    return;
                }
            }

            List<CommentsPosted.PostedInline> posted = new ArrayList<>();
            List<Finding> unanchored = new ArrayList<>();
            List<Finding> failed = new ArrayList<>();
            for (Finding finding : review.findings()) {
                postOneInline(commentSink, command, diff, finding, posted, unanchored, failed, backoffBudget);
            }

            String summaryThreadRef = postSummary(commentSink, command, review, unanchored, failed);
            if (summaryThreadRef == null) {
                return; // summary failure already emitted ReviewFailed
            }
            recoverPreviouslyPosted(previouslyPosted, posted);
            results.emit(new CommentsPosted(command.reviewId(), command.prId(), command.commit(),
                    summaryThreadRef, posted, outcomes));
```

and add the extracted helper (the old loop, with `REVIEW` excluded):

```java
    /**
     * Recover inline comment ids from a previous partially-completed attempt. Skip the non-inline claims
     * (SUMMARY, REVIEW, LLM[:reconcile], reply:, resolve:) — none of those are posted inline comments, so
     * they must never leak into the inline list.
     */
    private static void recoverPreviouslyPosted(Map<String, String> previouslyPosted, List<CommentsPosted.PostedInline> posted) {
        previouslyPosted.forEach((key, ref) -> {
            if (!SUMMARY_KEY.equals(key) && !NativeReviewPost.REVIEW_KEY.equals(key) && !LLM_KEY.equals(key)
                    && !RECONCILE_KEY.equals(key) && !key.startsWith("reply:") && !key.startsWith("resolve:")
                    && posted.stream().noneMatch(p -> p.threadRef().equals(ref))) {
                posted.add(new CommentsPosted.PostedInline(ref, key, 0));
            }
        });
    }
```

Add `import java.util.Optional;` if missing (it is already used in `postOneInline`).

- [ ] **Step 6: Commit, push, read CI**

```bash
git add spire-review-worker
git commit -m "Post a forge-native review from the worker when the command carries a verdict

The native path claims every key first, looks for a review an earlier
attempt left on the forge, posts once, marks the claim at once, reads the
inline ids back, falls back on a refused verdict or a rejected anchor,
and dismisses the bot's own hold when the repository setting is off.

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: green, including every pre-existing `ReviewWorkerTest` case.

**Mutation checks:** (a) remove the `findReview` call on a `Post` claim → `aCrashAfterThePostDoesNotPostASecondReview`
fails; (b) move `markPosted(REVIEW…)` after `settleInline` → same test still passes, so instead mutate
`settleInline` to throw before the mark and assert the mark exists: not expressible as one line — record
this guard as covered by the crash test's "mark is written now" assertion and by review; (c) drop
`NativeReviewPost.REVIEW_KEY` from `recoverPreviouslyPosted` → `aReviewRefIsNotAFindingThreadOnRedelivery`
fails; (d) `refused = true` removed → `aSecondRefusalIsAFailure` fails (loop posts three times); (e) the
`Boolean.FALSE.equals` dismissal condition inverted → `theSettingOnDoesNotDismiss` fails; (f) `body()` always
adds the line → `aDegradedRunWithTheSettingOnHasNoVerdictLine` fails.

---

### Task 5: Orchestrator — the "Reviewer may approve" setting

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V88__reviewer_may_approve.sql`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryInput.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryView.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryRegistry.java:87-140`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/repository/RepositoryResourceTest.java`

**Interfaces:**
- Produces: `RepositoryInput(..., UUID factoryAccountId, Boolean reviewerMayApprove)` plus the 7-arg
  constructor passing null; `RepositoryView(..., Account factory, boolean reviewerMayApprove)` plus the
  9-arg constructor passing true; `Optional<Boolean> RepositoryRegistry.reviewerMayApprove(UUID id)`.

- [ ] **Step 1: Write the failing test**

Append to `RepositoryResourceTest`:

```java
    @Test void reviewerMayApproveDefaultsOnAndRoundTripsThroughUpdate() {
        String id = given().contentType("application/json").body(repository(null, null)).post("/api/repositories")
                .then().statusCode(201).body("reviewerMayApprove", equalTo(true)).extract().path("id");
        given().contentType("application/json")
                .body(new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, false))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200).body("reviewerMayApprove", equalTo(false));
        given().get("/api/repositories/" + id).then().statusCode(200).body("reviewerMayApprove", equalTo(false));
        assertEquals(java.util.Optional.of(false), repositories.reviewerMayApprove(UUID.fromString(id)));
        assertTrue(repositories.reviewerMayApprove(UUID.randomUUID()).isEmpty(), "a missing row is empty, never a default");
    }
```

- [ ] **Step 2: Commit, push, read CI**

Expected: `spire-orchestrator` does not compile (8-arg constructor, `reviewerMayApprove`): red.

- [ ] **Step 3: Migration**

```sql
-- Real GitHub reviews (ADR-047, docs/superpowers/specs/2026-10-15-github-reviews-design.md).
--
-- reviewer_may_approve: the reviewer may post Approve / Request changes on this repository. Off means every
-- review is a Comment and the bot never changes a pull request's merge state (D3, D11). On for every
-- repository that exists today, which is the behaviour the operator asked for.
ALTER TABLE repository ADD COLUMN reviewer_may_approve BOOLEAN NOT NULL DEFAULT TRUE;

-- What the forge holds for the last posted run: the verdict and the forge's review id, so the dashboard can
-- say "Posted as Approved" and link to it. Written under the same commit guard as last_posted_commit.
ALTER TABLE review_status ADD COLUMN posted_verdict TEXT
    CHECK (posted_verdict IN ('APPROVE', 'REQUEST_CHANGES', 'COMMENT'));
ALTER TABLE review_status ADD COLUMN posted_review_ref TEXT;
```

- [ ] **Step 4: Records**

`RepositoryInput.java`:

```java
public record RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                              Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId,
                              Boolean reviewerMayApprove) {
    /** Call sites from before the setting existed; null means true, like {@code enabled}. */
    public RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                           Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId) {
        this(scmType, forgeOrigin, workspace, slug, enabled, reviewerAccountId, factoryAccountId, null);
    }
}
```

`RepositoryView.java`:

```java
public record RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace,
                             String slug, boolean enabled, long revision, Account reviewer, Account factory,
                             boolean reviewerMayApprove) {
    public record Account(UUID id, String name, String role, String handle, String state) { }

    /** Test fixtures from before the setting existed. */
    public RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace, String slug,
                          boolean enabled, long revision, Account reviewer, Account factory) {
        this(id, scmType, forgeOrigin, workspace, slug, enabled, revision, reviewer, factory, true);
    }
}
```

- [ ] **Step 5: Registry — all four sites and the reader**

`update` SQL: `UPDATE repository SET enabled=?, reviewer_may_approve=?, revision=revision+1 WHERE id=? AND revision=? AND scm_type=? AND forge_origin=? AND workspace=? AND slug=?`
with `statement.setBoolean(2, input.reviewerMayApprove());` and every later index shifted by one
(`setObject(3, id)`, `setLong(4, expectedRevision)`, `setString(5..8, ...)`).

`insert`: `INSERT INTO repository (id,scm_type,forge_origin,workspace,slug,enabled,reviewer_may_approve) VALUES (?,?,?,?,?,?,?)`
with `statement.setBoolean(7, input.reviewerMayApprove());`.

`normalize` last line:

```java
        return new RepositoryInput(input.scmType(), ForgeOrigin.of(input.forgeOrigin()), workspace, slug,
                input.enabled() == null || input.enabled(), input.reviewerAccountId(), input.factoryAccountId(),
                input.reviewerMayApprove() == null || input.reviewerMayApprove());
```

`view`: add `rows.getBoolean("reviewer_may_approve")` as the last argument.

New method:

```java
    /** The setting for the saga (ADR-047 §4.1); empty when the row is missing, never a default. */
    public Optional<Boolean> reviewerMayApprove(UUID id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT reviewer_may_approve FROM repository WHERE id=?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(rows.getBoolean(1)) : Optional.empty(); }
        } catch (SQLException failure) { throw database(failure); }
    }
```

- [ ] **Step 6: Commit, push, read CI**

```bash
git add spire-orchestrator
git commit -m "Add the per-repository reviewer-may-approve setting

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: green.

**Mutation checks:** (a) `update` stops setting the column → the round-trip assertion after PUT fails;
(b) `normalize` defaults to `false` → the `equalTo(true)` after POST fails.

---

### Task 6: Projection — open blockers, posted verdict, summary and detail fields

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java:504-515, 2059-2090, 2127, 2268-2281, 2300-2316`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewSummary.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewDetail.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewProjectionTest.java`

**Interfaces:**
- Produces: `int ReviewProjection.openBlockers(String reviewId)`;
  `void recordPostedVerdict(String reviewId, String commit, ReviewVerdict verdict, String reviewRef)`;
  `ReviewSummary.postedVerdict()`, `.reviewUrl()`; `ReviewDetail.postedVerdict()`, `.reviewUrl()` (both
  `String`, trailing components; the slug and the URL).

- [ ] **Step 1: Write the failing tests**

Append to `ReviewProjectionTest` (it has `projection`, `dataSource`, `threads`, `REPO`; add
`@Inject com.fasterxml.jackson.databind.ObjectMapper mapper;` and imports for `FindingVerdict`,
`PriorFinding`, `ReviewVerdict`):

```java
    private String completedReview(long pr, List<Finding> findings) {
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "TEST title", "TEST-author", "TEST-acc", "feature", "main",
                "cafe123", "https://x/pr/" + pr, "github", "reviewing", ReviewProjection.STAGE_DIFF);
        ReviewResult result = new ReviewResult(findings, "TEST summary", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOutcome(id, result, ReviewProjection.STAGE_DONE);
        return id;
    }

    @Test
    void openBlockersMatchesDetailOpenBlockers() {
        // No BLOCKER in this run's findings_json: the only open blocker is a prior-round one still open in
        // reconciliation_json. A count that reads findings_json alone answers 0 and fails here.
        long pr = 4201L;
        String id = completedReview(pr, List.of(
                new Finding("src/A.java", new LineRange(3, 3), Severity.MINOR, "TEST minor", null)));
        PriorFinding priorBlocker = PriorFinding.of("src/B.java", 8, Severity.BLOCKER, "TEST prior blocker", "t-prior");
        projection.recordReconciliation(id,
                List.of(new FindingVerdict("t-prior", "src/B.java", 8, FindingVerdict.Status.STILL_OPEN, "TEST still open")),
                List.of(priorBlocker));

        int fromDetail = projection.loadDetail("acme", "web", pr).orElseThrow().openBlockers();
        assertEquals(1, fromDetail, "the still-open prior blocker is open");
        assertEquals(fromDetail, projection.openBlockers(id), "one rule for GitHub and the land gate (D1)");
        assertEquals(0, projection.openBlockers("review::acme/web#0"), "a missing row has no blockers");
    }

    @Test
    void recordPostedVerdictIsReadBackBySummaryAndDetail() {
        long pr = 4202L;
        String id = completedReview(pr, List.of());
        projection.recordPostedVerdict(id, "cafe123", ReviewVerdict.REQUEST_CHANGES, "501");

        ReviewDetail detail = projection.loadDetail("acme", "web", pr).orElseThrow();
        assertEquals("changes_requested", detail.postedVerdict());
        assertEquals("https://x/pr/" + pr + "#pullrequestreview-501", detail.reviewUrl());
        ReviewSummary summary = projection.listSummaries(false).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        assertEquals("changes_requested", summary.postedVerdict());
        assertEquals(detail.reviewUrl(), summary.reviewUrl());
    }

    @Test
    void aSupersededRunsVerdictIsNotRecorded() {
        long pr = 4203L;
        String id = completedReview(pr, List.of());
        projection.recordPostedVerdict(id, "0ld0ld", ReviewVerdict.APPROVE, "777");
        assertNull(projection.loadDetail("acme", "web", pr).orElseThrow().postedVerdict(),
                "a CommentsPosted for an older commit cannot stamp the current head (same guard as recordPosted)");
    }

    @Test
    void postedVerdictAndReviewUrlSerializeOnBothRecords() throws Exception {
        long pr = 4204L;
        String id = completedReview(pr, List.of());
        projection.recordPostedVerdict(id, "cafe123", ReviewVerdict.APPROVE, "9");
        var detail = mapper.readTree(mapper.writeValueAsString(projection.loadDetail("acme", "web", pr).orElseThrow()));
        var summary = mapper.readTree(mapper.writeValueAsString(
                projection.listSummaries(false).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow()));
        for (var record : List.of(detail, summary)) {
            assertEquals("approved", record.path("postedVerdict").asText(), record.toString());
            assertTrue(record.path("reviewUrl").asText().endsWith("#pullrequestreview-9"), record.toString());
        }
    }
```

If `PriorFinding.of(path, line, severity, message, threadRef)` does not exist with that shape, use the
canonical constructor `new PriorFinding("src/B.java", 8, Severity.BLOCKER, "TEST prior blocker", "t-prior", null)`.

- [ ] **Step 2: Commit, push, read CI**

Expected: `spire-orchestrator` red (compile: `openBlockers(String)`, `recordPostedVerdict`, `postedVerdict()`).

- [ ] **Step 3: Implement**

`ReviewRow`: add trailing `String postedVerdict, String postedReviewRef`; `readRow` reads
`rs.getString("posted_verdict"), rs.getString("posted_review_ref")`.

`ReviewSummary`: add trailing

```java
        /** How the last posted run's verdict was posted on the forge (ADR-047): {@code approved},
         *  {@code changes_requested}, {@code commented}, or null on the legacy path and other forges. */
        String postedVerdict,
        /** The forge's link to that review, or null. */
        String reviewUrl
```

`ReviewDetail`: the same two trailing components, with the comment "Carried here as well as on the summary
for the reason {@code degraded} gives: two independent records over one wire."

`toSummary` and `toDetail`: append `verdictSlug(r.postedVerdict), reviewUrl(r)`:

```java
    static String verdictSlug(String postedVerdict) {
        if (postedVerdict == null) {
            return null;
        }
        return switch (postedVerdict) {
            case "APPROVE" -> "approved";
            case "REQUEST_CHANGES" -> "changes_requested";
            default -> "commented";
        };
    }

    /** GitHub links a review as a fragment of the pull request page. Other forges have no link yet. */
    static String reviewUrl(ReviewRow row) {
        if (row.postedReviewRef() == null || row.htmlUrl() == null || !"github".equals(row.providerType())) {
            return null;
        }
        return row.htmlUrl() + "#pullrequestreview-" + row.postedReviewRef();
    }
```

New public methods, next to `recordPosted`:

```java
    /** Same commit guard as {@link #recordPosted}: a superseded run's event cannot stamp the newer head. */
    public void recordPostedVerdict(String reviewId, String commit, ReviewVerdict verdict, String reviewRef) {
        update("""
                UPDATE review_status SET posted_verdict = ?, posted_review_ref = ?, updated_at = now()
                 WHERE review_id = ? AND commit_sha = ?
                """, ps -> {
            ps.setString(1, verdict.name());
            ps.setString(2, reviewRef);
            ps.setString(3, reviewId);
            ps.setString(4, commit);
        });
    }

    /** The open BLOCKER count the land gate waits on, for the verdict decision (ADR-047 D1, D4). */
    public int openBlockers(String reviewId) {
        try (Connection c = dataSource.getConnection()) {
            ReviewRow row = loadRow(c, reviewId);
            return row == null ? 0 : openCounts(row).openBlockers();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count open blockers", e);
        }
    }
```

- [ ] **Step 4: Commit, push, read CI**

```bash
git add spire-orchestrator
git commit -m "Expose open blockers and record the posted verdict on a review

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: green.

**Mutation checks:** (a) `recordPostedVerdict` drops `AND commit_sha = ?` → `aSupersededRunsVerdictIsNotRecorded`
fails; (b) `openBlockers` returns `blockerCount(row)` → `openBlockersMatchesDetailOpenBlockers` fails;
(c) `toDetail` passes `null` for `postedVerdict` → `postedVerdictAndReviewUrlSerializeOnBothRecords` fails.

---

### Task 7: Orchestrator — the verdict decision

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ReviewVerdictDecision.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java:70-72, 254-258, 370-399`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryAccounts.java` (append)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ReviewVerdictDecisionTest.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ResultSagaVerdictTest.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/repository/RepositoryAccountsTest.java` (append)

**Interfaces:**
- Consumes: Task 5 `RepositoryRegistry.reviewerMayApprove(UUID)`, Task 6 `ReviewProjection.openBlockers`,
  Task 1 `PostComments.withVerdict`, `CommentsPosted.postedVerdict/reviewRef`.
- Produces: `ReviewVerdictDecision.decide(boolean degraded, int openBlockers, Optional<Boolean> setting, boolean reviewerIdentityKnown)`
  → `record Decision(ReviewVerdict assessment, ReviewVerdict verdict, boolean mayApprove, String reason)` with
  `String detail()`; `boolean RepositoryAccounts.reviewerIdentityKnown(UUID repositoryId)`.

- [ ] **Step 1: Write the failing tests**

`ReviewVerdictDecisionTest.java`:

```java
package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.ReviewVerdict;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The verdict rule (ADR-047 §4.1): findings decide the assessment, the repository decides what is posted. */
class ReviewVerdictDecisionTest {

    private static final Optional<Boolean> ON = Optional.of(true);

    @Test
    void degradedGivesComment() {
        var decision = ReviewVerdictDecision.decide(true, 3, ON, true);
        assertEquals(ReviewVerdict.COMMENT, decision.assessment());
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertTrue(decision.mayApprove(), "the setting is on; the hold stays (D5)");
        assertEquals("degraded", decision.reason());
    }

    @Test
    void anOpenBlockerGivesRequestChanges() {
        var decision = ReviewVerdictDecision.decide(false, 1, ON, true);
        assertEquals(ReviewVerdict.REQUEST_CHANGES, decision.verdict());
        assertEquals("open_blockers=1", decision.reason());
    }

    @Test
    void noBlockersGiveApprove() {
        var decision = ReviewVerdictDecision.decide(false, 0, ON, true);
        assertEquals(ReviewVerdict.APPROVE, decision.verdict());
        assertEquals("no_open_blockers", decision.reason());
    }

    @Test
    void theSettingOffGivesCommentWithAssessmentApprove() {
        var decision = ReviewVerdictDecision.decide(false, 0, Optional.of(false), true);
        assertEquals(ReviewVerdict.APPROVE, decision.assessment());
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertFalse(decision.mayApprove());
        assertEquals("setting_off", decision.reason());
        assertEquals("verdict:COMMENT · assessment:APPROVE · setting_off", decision.detail());
    }

    @Test
    void anUnknownRepositoryGivesComment() {
        var decision = ReviewVerdictDecision.decide(false, 2, Optional.empty(), true);
        assertEquals(ReviewVerdict.REQUEST_CHANGES, decision.assessment());
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertEquals("repository_unknown", decision.reason());
    }

    @Test
    void aReviewerWithoutIdentityGivesComment() {
        var decision = ReviewVerdictDecision.decide(false, 0, ON, false);
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertFalse(decision.mayApprove());
        assertEquals("reviewer_identity_unknown", decision.reason());
    }
}
```

`ResultSagaVerdictTest.java` — copy `sagaForReviewGenerated`, `SILENT_FINDINGS` and `NO_PREFERENCES`
from `ResultSagaRetryTest` (they are private there; repeat them here verbatim, with `FIRST_RUN = 1`), then
add the overrides and tests:

```java
/** The saga carries the decision on the command, and records what was posted (ADR-047 §4). */
class ResultSagaVerdictTest {

    private static final RepoRef REPO = new RepoRef("acme", "web");
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, 412L);
    private static final String COMMIT = "cafe123";
    private static final int FIRST_RUN = 1;

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<String> timelineTypes = new ArrayList<>();
    private final List<String> recordedVerdicts = new ArrayList<>();
    private int openBlockers;
    private Optional<Boolean> setting = Optional.of(true);
    private boolean identityKnown = true;

    /* sagaForReviewGenerated(...) as in ResultSagaRetryTest, with these additions inside the projection fake: */
    //      @Override public int openBlockers(String reviewId) { return openBlockers; }   // real one opens a DB
    //      @Override public void appendEvent(String reviewId, String lane, String type, String detail) { timelineTypes.add(type + " " + detail); }
    //      @Override public void recordPostedVerdict(String reviewId, String commit, ReviewVerdict verdict, String reviewRef) { recordedVerdicts.add(verdict + ":" + reviewRef + ":" + commit); }
    //      @Override public void updateStage(String reviewId, int stage) { }
    //      @Override public void recordPosted(String reviewId, String commit, String summaryCommentId) { }
    /* and after it: */
    //      saga.repositories = new dev.codespire.orchestrator.repository.RepositoryRegistry() {
    //          @Override public Optional<Boolean> reviewerMayApprove(UUID id) { return setting; }   // real one opens a DB
    //      };
    //      saga.repositoryAccounts = new dev.codespire.orchestrator.repository.RepositoryAccounts() {
    //          @Override public boolean reviewerIdentityKnown(UUID repositoryId) { return identityKnown; }   // real one opens a DB
    //      };
    //      saga.threads = new ReviewThreadView() {
    //          @Override public void markFindingThread(String reviewId, ThreadRef thread, String path, int line) { }
    //          @Override public void markSummaryThread(String reviewId, ThreadRef thread) { }
    //      };

    @Test
    void theCommandCarriesTheDecision() {
        openBlockers = 1;
        var saga = sagaForReviewGenerated(new PriorRun(COMMIT, "sum-prior-1", List.of()), Optional.of("packed-cred"));
        saga.on(new ReviewGenerated(REVIEW_ID, 412L, COMMIT, new ReviewResult(List.of(), "s", ModelUsage.of(null, 0, 0))));
        var post = assertInstanceOf(ActionCommand.PostComments.class, emitted.getLast());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.assessment());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.verdict());
        assertEquals(Boolean.TRUE, post.reviewerMayApprove());
        assertTrue(timelineTypes.contains("ReviewVerdictDecided verdict:REQUEST_CHANGES · assessment:REQUEST_CHANGES · open_blockers=1"), timelineTypes.toString());
    }

    @Test
    void theSettingOffPostsACommentAndSaysSo() {
        setting = Optional.of(false);
        var saga = sagaForReviewGenerated(new PriorRun(COMMIT, "sum-prior-1", List.of()), Optional.of("packed-cred"));
        saga.on(new ReviewGenerated(REVIEW_ID, 412L, COMMIT, new ReviewResult(List.of(), "s", ModelUsage.of(null, 0, 0))));
        var post = assertInstanceOf(ActionCommand.PostComments.class, emitted.getLast());
        assertEquals(ReviewVerdict.APPROVE, post.assessment());
        assertEquals(ReviewVerdict.COMMENT, post.verdict());
        assertEquals(Boolean.FALSE, post.reviewerMayApprove());
    }

    @Test
    void aReviewerWithoutIdentityNeverGetsAnApprove() {
        identityKnown = false;
        var saga = sagaForReviewGenerated(new PriorRun(COMMIT, "sum-prior-1", List.of()), Optional.of("packed-cred"));
        saga.on(new ReviewGenerated(REVIEW_ID, 412L, COMMIT, new ReviewResult(List.of(), "s", ModelUsage.of(null, 0, 0))));
        assertEquals(ReviewVerdict.COMMENT, assertInstanceOf(ActionCommand.PostComments.class, emitted.getLast()).verdict());
    }

    @Test
    void commentsPostedWithAVerdictRecordsIt() {
        var saga = sagaForReviewGenerated(new PriorRun(COMMIT, "sum-prior-1", List.of()), Optional.of("packed-cred"));
        saga.on(new CommentsPosted(REVIEW_ID, 412L, COMMIT, "review:501", List.of(), List.of())
                .withPostedReview(ReviewVerdict.APPROVE, "501"));
        assertEquals(List.of("APPROVE:501:" + COMMIT), recordedVerdicts);
    }

    @Test
    void commentsPostedWithoutAVerdictRecordsNothing() {
        var saga = sagaForReviewGenerated(new PriorRun(COMMIT, "sum-prior-1", List.of()), Optional.of("packed-cred"));
        saga.on(new CommentsPosted(REVIEW_ID, 412L, COMMIT, "sum-1", List.of(), List.of()));
        assertTrue(recordedVerdicts.isEmpty());
    }
}
```

Append to `RepositoryAccountsTest`:

```java
    @Test void reviewerIdentityIsKnownOnlyWhenTheRegistrationHasOne() throws Exception {
        UUID reviewer = account("REVIEWER");
        var repo = repositories.create(repository(reviewer, null));
        assertTrue(accounts.reviewerIdentityKnown(repo.id()));
        execute("UPDATE scm_provider SET bot_account_id='' WHERE id=?", reviewer);
        assertFalse(accounts.reviewerIdentityKnown(repo.id()));
        assertFalse(accounts.reviewerIdentityKnown(UUID.randomUUID()), "no registration, no identity");
    }
```

- [ ] **Step 2: Commit, push, read CI**

Expected: `spire-orchestrator` red (compile: `ReviewVerdictDecision`, `reviewerIdentityKnown`, the saga fields).

- [ ] **Step 3: Implement the decision**

```java
package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.ReviewVerdict;

import java.util.Optional;

/**
 * The verdict rule (ADR-047 §4.1). Pure, so the rule is testable without the projection: what the
 * findings say is the assessment; whether the repository lets the bot change merge state decides what
 * is posted.
 */
public final class ReviewVerdictDecision {

    public static final String REASON_DEGRADED = "degraded";
    public static final String REASON_NO_BLOCKERS = "no_open_blockers";
    public static final String REASON_SETTING_OFF = "setting_off";
    public static final String REASON_REPOSITORY_UNKNOWN = "repository_unknown";
    public static final String REASON_IDENTITY_UNKNOWN = "reviewer_identity_unknown";

    public record Decision(ReviewVerdict assessment, ReviewVerdict verdict, boolean mayApprove, String reason) {
        /** The timeline line: what is posted, what the findings said, and why they differ when they do. */
        public String detail() {
            return "verdict:" + verdict + " · assessment:" + assessment + " · " + reason;
        }
    }

    private ReviewVerdictDecision() {
    }

    /**
     * @param setting the repository's "reviewer may approve"; empty when the repository is unknown
     * @param reviewerIdentityKnown whether the reviewer registration has a bot identity — without one the
     *     land gate could not tell the bot's own Approve from a human's (spec §1)
     */
    public static Decision decide(boolean degraded, int openBlockers, Optional<Boolean> setting, boolean reviewerIdentityKnown) {
        ReviewVerdict assessment = degraded ? ReviewVerdict.COMMENT
                : openBlockers > 0 ? ReviewVerdict.REQUEST_CHANGES : ReviewVerdict.APPROVE;
        String reason = degraded ? REASON_DEGRADED : openBlockers > 0 ? "open_blockers=" + openBlockers : REASON_NO_BLOCKERS;
        boolean mayApprove = true;
        if (setting.isEmpty()) {
            mayApprove = false;
            reason = REASON_REPOSITORY_UNKNOWN;
        } else if (!setting.get()) {
            mayApprove = false;
            reason = REASON_SETTING_OFF;
        } else if (!reviewerIdentityKnown) {
            mayApprove = false;
            reason = REASON_IDENTITY_UNKNOWN;
        }
        return new Decision(assessment, mayApprove ? assessment : ReviewVerdict.COMMENT, mayApprove, reason);
    }
}
```

- [ ] **Step 4: `RepositoryAccounts.reviewerIdentityKnown`**

```java
    /** Whether the REVIEWER registration has a bot identity — the value the land gate's machine check relies on. */
    public boolean reviewerIdentityKnown(UUID repositoryId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT p.bot_account_id FROM repository_account a JOIN scm_provider p ON p.id = a.account_id
                WHERE a.repository_id = ? AND a.role = 'REVIEWER'
                """)) {
            statement.setObject(1, repositoryId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getString(1) != null && !rows.getString(1).isBlank();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot read the reviewer identity", failure);
        }
    }
```

- [ ] **Step 5: Wire the saga**

Fields (next to `projection`):

```java
    @Inject
    dev.codespire.orchestrator.repository.RepositoryRegistry repositories;

    @Inject
    dev.codespire.orchestrator.repository.RepositoryAccounts repositoryAccounts;
```

In `onReviewGenerated`, replace the final `emitWithCredential` with:

```java
        ReviewVerdictDecision.Decision decision = decideVerdict(e.reviewId(), visible);
        projection.appendEvent(e.reviewId(), "result", "ReviewVerdictDecided", decision.detail());
        emitWithCredential(e.reviewId(), "PostComments", cred -> new ActionCommand.PostComments(
                e.reviewId(), repo, e.prId(), e.commit(), visible, cred,
                e.verdicts(), priorSummaryRef, filtered.suppressedCount())
                .withVerdict(decision.assessment(), decision.verdict(), decision.mayApprove()));
```

and add:

```java
    /**
     * ADR-047 D4: decided here because the projection's open-blocker count is what the land gate reads,
     * and it is already right at this point (recordOpenFindings ran above).
     */
    private ReviewVerdictDecision.Decision decideVerdict(String reviewId, ReviewResult visible) {
        java.util.Optional<java.util.UUID> repositoryId = projection.repositoryIdOf(reviewId);
        java.util.Optional<Boolean> setting = repositoryId.flatMap(repositories::reviewerMayApprove);
        boolean identityKnown = repositoryId.map(repositoryAccounts::reviewerIdentityKnown).orElse(false);
        return ReviewVerdictDecision.decide(visible.degraded(), projection.openBlockers(reviewId), setting, identityKnown);
    }
```

In the `CommentsPosted` case, after `projection.recordPosted(...)`:

```java
                if (e.postedVerdict() != null) {
                    projection.recordPostedVerdict(e.reviewId(), e.commit(), e.postedVerdict(), e.reviewRef());
                }
```

Then check every existing `ResultSaga*Test` fake that drives `ReviewGenerated` or `CommentsPosted`:
`ResultSagaRetryTest.sagaForReviewGenerated` and `ResultSagaPricingTest` now reach `openBlockers`,
`repositories`, `repositoryAccounts`: add the same three overrides there (`openBlockers` → 0, the two
registry fakes) with the comment "the real one opens a database". `commentsPosted_keysOwnershipByTheThreadNotTheComment`
posts a null verdict, so it needs nothing.

- [ ] **Step 6: Commit, push, read CI**

```bash
git add spire-orchestrator
git commit -m "Decide the review verdict in the orchestrator and record what was posted

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: green.

**Mutation checks:** (a) `decide` ignores `reviewerIdentityKnown` → `aReviewerWithoutIdentityGivesComment`
fails; (b) `decide` treats an empty setting as on → `anUnknownRepositoryGivesComment` fails; (c) the saga
calls `recordPostedVerdict` unconditionally → `commentsPostedWithoutAVerdictRecordsNothing` fails (NPE on
`verdict.name()` or a recorded "null").

---

### Task 8: Factory — ask for the reviewer

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkReview.java:26-27, 48-61` (append methods)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkReviewIT.java`

**Interfaces:**
- Consumes: Task 3 `PullRequestSink.requestReviewers`; `RepositoryAccounts.resolve(UUID, ProviderRole)`;
  `WorkItemControl.received/receipt` (static, package-private).
- Produces: `WorkReview.REQUEST_CHANNEL = "factory"`.

**Ruling (recorded here):** the spec's §6 says "after `ensureMarkedReady` returns true". A delivery opened
ready (not a draft) never calls `ensureMarkedReady`; the request runs for it too, on the same pass, because
case 1 of the exit criterion is such a PR. Cost if wrong: one extra API call per item.

- [ ] **Step 1: Write the failing tests**

Append to `WorkReviewIT`:

```java
    String requested() { return "/repos/"+scope+"/pulls/901/requested_reviewers"; }
    void reviewerRequestAccepted() { forge.stubFor(post(urlEqualTo(requested())).willReturn(aResponse().withStatus(201).withBody("{}"))); }

    /** D9: once per attempt and pull request, with a durable receipt, so a restart does not ask twice. */
    @Test void aReadyPullRequestRequestsTheReviewerOnce() throws Exception {
        reviewerRequestAccepted();
        String id=delivered();observer.advance(id);observer.advance(id);
        forge.verify(1,postRequestedFor(urlEqualTo(requested())).withRequestBody(equalToJson("{\"reviewers\":[\"TEST-reviewer\"]}")));
        assertEquals(1,count("SELECT count(*) FROM work_activity_receipt WHERE work_item_id=? AND channel='factory' AND outcome='reviewer_requested'",id));
    }
    @Test void aRefusedRequestIsAReceiptNotAWait() throws Exception {
        forge.stubFor(post(urlEqualTo(requested())).willReturn(aResponse().withStatus(422).withBody("{\"message\":\"Reviews may only be requested from collaborators.\"}")));
        String id=delivered();waiting(id,"review_result_pending");
        assertEquals(1,count("SELECT count(*) FROM work_activity_receipt WHERE work_item_id=? AND outcome='reviewer_request_refused:reviewer_not_collaborator'",id));
        forge.verify(1,postRequestedFor(urlEqualTo(requested())));
    }
    @Test void noRequestOnceTheBotPostedForThisHead() throws Exception {
        reviewerRequestAccepted();
        String id=delivered();reviewResult(id,"b".repeat(40),false,false);observer.advance(id);
        forge.verify(0,postRequestedFor(urlEqualTo(requested())));
    }
    @Test void aRowAtAnotherHeadDoesNotSuppressTheRequest() throws Exception {
        reviewerRequestAccepted();
        String id=delivered();reviewResult(id,"b".repeat(40),false,false);
        execute("UPDATE review_status SET commit_sha=repeat('c',40) WHERE review_id=?",review);
        observer.advance(id);forge.verify(1,postRequestedFor(urlEqualTo(requested())));
    }
    @Test void aRunningReviewOfThisHeadStillGetsTheRequest() throws Exception {
        reviewerRequestAccepted();
        String id=delivered();reviewResult(id,"b".repeat(40),false,false);
        execute("UPDATE review_status SET status='reviewing',last_posted_commit=NULL WHERE review_id=?",review);
        observer.advance(id);forge.verify(1,postRequestedFor(urlEqualTo(requested())));
    }
```

- [ ] **Step 2: Commit, push, read CI**

Expected: `spire-orchestrator` red: `aReadyPullRequestRequestsTheReviewerOnce` fails on `forge.verify(1, …)`
(0 requests).

- [ ] **Step 3: Implement**

In `WorkReview` add the injection and constant:

```java
    @Inject dev.codespire.orchestrator.repository.RepositoryAccounts repositoryAccounts;
    static final String REQUEST_CHANNEL="factory";
```

In `advance`, after the draft line:

```java
        if(Boolean.TRUE.equals(execution.pullRequest().draft()) && !ensureMarkedReady(item,execution))return;
        ensureReviewerRequested(item,execution);
```

Append:

```java
    /**
     * D9: the reviewer bot is asked for the review once per attempt and pull request, with a receipt
     * (channel {@code factory}) so a restart does not ask twice; a refusal is a receipt, never a wait.
     * Skipped once the bot has posted for this head: a request then shows as "re-requested" noise.
     */
    private void ensureReviewerRequested(WorkItemEvent item,WorkExecution execution) {
        long pr=execution.pullRequest().number();String delivery="reviewer-request:"+item.progress().attemptId()+":"+pr;
        try(Connection c=dataSource.getConnection()) {
            if(WorkItemControl.received(c,item.workItemId(),REQUEST_CHANNEL,delivery) || reviewPostedForHead(c,item,execution))return;
            WorkItemControl.receipt(c,item.workItemId(),REQUEST_CHANNEL,delivery,requestReviewer(item,execution));
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    /** On row absence or a different head, not on observe()'s reason: that string is the same for no row and a degraded one. */
    private boolean reviewPostedForHead(Connection c,WorkItemEvent item,WorkExecution execution) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("SELECT commit_sha,status FROM review_status WHERE repository_id=? AND pr_id=?")) {
            ps.setObject(1,item.repositoryId());ps.setLong(2,execution.pullRequest().number());
            try(ResultSet rs=ps.executeQuery()) {
                return rs.next() && execution.head().equals(rs.getString("commit_sha")) && !Set.of("received","reviewing").contains(rs.getString("status"));
            }
        }
    }
    private String requestReviewer(WorkItemEvent item,WorkExecution execution) {
        String login=repositoryAccounts.resolve(item.repositoryId(),dev.codespire.orchestrator.provider.ProviderRole.REVIEWER)
                .map(dev.codespire.orchestrator.provider.ScmProvider::botUsername).orElse("");
        if(login==null || login.isBlank())return "reviewer_login_unknown";
        var sink=accounts.resolve(item.repositoryId()).map(clients::pullRequestSink);
        if(sink.isEmpty())return "reviewer_request_refused:factory_account_unavailable";
        try{sink.orElseThrow().requestReviewers(repositoryOf(item),execution.pullRequest(),List.of(login));return "reviewer_requested";}
        catch(dev.codespire.contract.port.PullRequestSink.DeliveryUnavailable refusal) {
            LOG.warnf("work item %s: the forge refused to request reviewer %s on pull request %d (%s)",item.workItemId(),login,execution.pullRequest().number(),refusal.getMessage());
            return "reviewer_request_refused:"+refusal.getMessage();
        }catch(RuntimeException fault) {
            LOG.warnf("work item %s: requesting reviewer %s on pull request %d failed (%s)",item.workItemId(),login,execution.pullRequest().number(),fault.getClass().getSimpleName());
            return "reviewer_request_refused:"+fault.getClass().getSimpleName();
        }
    }
```

- [ ] **Step 4: Commit, push, read CI**

```bash
git add spire-orchestrator
git commit -m "Request the reviewer bot on a delivered pull request once

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: green, including every pre-existing `WorkReviewIT` case (an unstubbed request answers 404 →
a `reviewer_request_refused:GitHubApiException` receipt, never a wait).

**Mutation checks:** (a) drop the `received(...)` check → `aReadyPullRequestRequestsTheReviewerOnce` fails
(2 requests); (b) `reviewPostedForHead` ignores the head → `aRowAtAnotherHeadDoesNotSuppressTheRequest`
fails; (c) `reviewPostedForHead` ignores the status → `aRunningReviewOfThisHeadStillGetsTheRequest` fails.

---

### Task 9: Guards — the bot's own review never triggers or resolves

**Files:**
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubIngressTest.java` (append)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkGateChannelsIT.java` (append)

No production change is expected; these pin facts the design relies on (§5.3, §1). If any test fails,
stop and rule: the fact the spec relies on is false.

- [ ] **Step 1: Ingress tests**

Append to `GitHubIngressTest` (uses its `pr(action, merged, draft)` and `webhook(body, event)` helpers):

```java
    @Test
    void reviewRequestedIsIgnored() {
        assertTrue(ingress.translate(webhook(pr("review_requested", false, false), "pull_request")).isEmpty(),
                "requesting the bot must not start a review; ready_for_review does (ADR-047 D9)");
    }

    @Test
    void aSubmittedReviewIsNotAReviewTrigger() {
        byte[] body = """
                {"action":"submitted","review":{"id":17,"state":"approved","user":{"id":9999,"login":"TEST-bot"}},
                 "pull_request":{"number":42,"head":{"ref":"TEST-work","sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}},
                 "repository":{"full_name":"TEST-owner/TEST-repo"},"sender":{"id":9999}}
                """.getBytes(StandardCharsets.UTF_8);
        assertTrue(ingress.translate(webhook(body, "pull_request_review")).isEmpty(),
                "the bot's own review must never start a second review");
    }
```

- [ ] **Step 2: Gate tests**

Append to `WorkGateChannelsIT` (the land gate records the reviewer actor `900002`; see `landGate()`):

```java
    void reviewerApproval(String state,String head) {
        String review="{\"id\":17,\"state\":\""+state+"\",\"commit_id\":\""+head+"\",\"user\":{\"id\":900002,\"type\":\"User\"}}";
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42/reviews/17")).willReturn(okJson(review)));
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42/reviews?per_page=100&page=1")).willReturn(okJson("["+review+"]")));
    }
    /** ADR-047 §1: the real guard is the recorded machine actor, not review.user.type. */
    @Test void theBotsOwnApproveCannotResolveTheLandGate() throws Exception {
        landGate();reviewerApproval("APPROVED",HEAD);
        channels.activity(repository,event("approval","900002"),"TEST-reviewer-own-approve");
        assertEquals(0,resolved());assertEquals("OPEN",store.load(itemId).gate().state());
    }
    @Test void theBotsOwnCommentReviewIsRecordedMachine() throws Exception {
        landGate();
        channels.activity(repository,event("comment","900002"),"TEST-reviewer-comment-review");
        assertNotEquals("suspended",store.load(itemId).workflowStatus(),"the bot's Comment review is not a human takeover");
        assertEquals(1,count("SELECT count(*) FROM work_activity_receipt WHERE work_item_id=? AND outcome='recorded_machine'",itemId));
    }
```

- [ ] **Step 3: Commit, push, read CI**

```bash
git add spire-scm-github/src/test spire-orchestrator/src/test
git commit -m "Pin that the bot's own review neither triggers nor resolves

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: green on the first run (these pin existing behaviour). Read each of the four in the log to be
sure they ran.

---

### Task 10: Dashboard — posted verdict, review step link, the setting

**Files:**
- Modify: `spire-ui/src/api.ts` (`ReviewSummary`, before `degraded`)
- Modify: `spire-ui/src/components/ReviewDetail.tsx:95-120`
- Modify: `spire-ui/src/components/work-items/WorkItemSteps.tsx:103`
- Modify: `spire-ui/src/components/repositories/repositoriesApi.ts` (`Repository`, `RepositoryInput`)
- Modify: `spire-ui/src/components/repositories/RepositoryForm.tsx:22-26, 80`
- Modify: `spire-ui/src/components/repositories/RepositoryDetail.tsx` (Selected accounts section)
- Test: `spire-ui/src/components/ReviewDetail.posted.test.tsx` (new)
- Test: `spire-ui/src/components/work-items/WorkItemSteps.test.tsx:71`
- Test: `spire-ui/src/components/repositories/RepositoryRegistryPage.test.tsx`, `RepositoryDetail.test.tsx`

**Ruling (recorded here):** the work item review step links to the review page instead of repeating the
"Posted as" words: the work item payload has only `reviewId`, and adding a join to the work item projection
is not worth a wire field for one line. Cost if wrong: one click.

- [ ] **Step 1: Write the failing tests**

`ReviewDetail.posted.test.tsx` — copy the `review` fixture and `renderPage` from `ReviewDetail.layout.test.tsx`
verbatim, then:

```tsx
describe('ReviewDetail posted verdict', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({ items: [], contributingSources: [], missingSources: [] });
  });

  it('says how the review was posted and links to it', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue({
      ...review, postedVerdict: 'changes_requested', reviewUrl: 'https://github.com/acme/web/pull/42#pullrequestreview-501',
    });
    renderPage();
    const line = await screen.findByText(/Posted as/);
    expect(line).toHaveTextContent('Posted as Changes requested');
    expect(screen.getByRole('link', { name: 'open the review' })).toHaveAttribute('href', 'https://github.com/acme/web/pull/42#pullrequestreview-501');
  });

  it('shows nothing for a review posted as a comment thread', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue({ ...review, postedVerdict: null, reviewUrl: null });
    renderPage();
    await screen.findByRole('heading', { name: 'Context', level: 3 });
    expect(screen.queryByText(/Posted as/)).toBeNull();
  });
});
```

`WorkItemSteps.test.tsx` line 71: extend the existing assertion:

```tsx
  expect(step('Review').getByText(/Review recorded for this build/)).toBeInTheDocument();
  expect(step('Review').getByRole('link', { name: 'Open the review' })).toHaveAttribute('href', expect.stringMatching(/^#\/r\/.+\/\d+$/));
```

`RepositoryRegistryPage.test.tsx`: model on the test "offers only same-origin accounts of the chosen role
and saves explicit ids" (line 84): open the same form, then

```tsx
    fireEvent.click(screen.getByLabelText('Reviewer may approve'));
    fireEvent.click(screen.getByRole('button', { name: 'Save repository' }));
    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(expect.objectContaining({ reviewerMayApprove: false }), expect.anything()));
```

as a new test `saves the reviewer-may-approve setting, on by default`, with a first assertion before the
click that the checkbox is checked.

`RepositoryDetail.test.tsx`: in the first test add
`expect(within(accounts).getByText('Reviewer may approve: on')).toBeInTheDocument();` and add
`reviewerMayApprove: true` to the `repository` fixture. Grep `revision: 1` in
`spire-ui/src/components/repositories/*.test.tsx` and add `reviewerMayApprove: true` to every `Repository`
fixture so TypeScript compiles.

- [ ] **Step 2: Commit, push, read CI**

Expected: `dashboard` red (type errors on `postedVerdict` and `reviewerMayApprove`, then the assertions).

- [ ] **Step 3: Implement**

`api.ts`, in `ReviewSummary` before `degraded`:

```ts
  // How the last posted run's verdict reached the forge (ADR-047): a GitHub review's state, or null on
  // the legacy comment path and on forges without native reviews. Optional on the TS side; the Java
  // serialization test is the guard that both records carry it.
  postedVerdict?: PostedVerdict | null;
  // The forge's link to that review, or null.
  reviewUrl?: string | null;
```

and above the interface:

```ts
/** How a review was posted on the forge (ADR-047). */
export type PostedVerdict = 'approved' | 'changes_requested' | 'commented';
```

`ReviewDetail.tsx`: import `PostedVerdict` from `../api`; add

```tsx
const POSTED_LABEL: Record<PostedVerdict, string> = { approved: 'Approved', changes_requested: 'Changes requested', commented: 'Comment' };

function postedAs(r: ReviewDetailData) {
  if (!r.postedVerdict) return null;
  return <p className="posted-as">Posted as <strong>{POSTED_LABEL[r.postedVerdict]}</strong>
    {r.reviewUrl && <> · <a href={r.reviewUrl} target="_blank" rel="noreferrer">open the review</a></>}</p>;
}
```

and render `{postedAs(r)}` right after the closing `</div>` of `meta-line`. Add to the stylesheet next to
`.meta-line`: `.posted-as { margin: 4px 0 0; color: var(--muted); }` (use the existing muted colour token
the file already uses for `.prov-sub`).

`WorkItemSteps.tsx` line 103:

```tsx
  if (phase === 'review') {
    if (!execution.reviewId) return null;
    const review = /^review::(.+)\/([^/#]+)#(\d+)$/.exec(execution.reviewId);
    const href = review ? `#/r/${review[1]}/${review[2]}/${review[3]}` : null;
    return <p className="factory-note">Review recorded for this build.{href && <> <a href={href}>Open the review</a> to see how it was posted.</>}</p>;
  }
```

`repositoriesApi.ts`: add `reviewerMayApprove: boolean;` to both `Repository` and `RepositoryInput`.

`RepositoryForm.tsx`: in the initial state add `reviewerMayApprove: initial?.reviewerMayApprove ?? true,`;
after the Enabled checkbox:

```tsx
      <label className="field-check"><input type="checkbox" checked={fields.reviewerMayApprove} onChange={e => patch({ reviewerMayApprove: e.target.checked })} /><span>Reviewer may approve</span></label>
      <p className="prov-sub">Off: every review is posted as a comment and the bot never changes a pull request's merge state.</p>
```

`RepositoryDetail.tsx`, inside the "Selected accounts" section after `<RepositoryAccountsCell …/>`:

```tsx
      <p className="prov-sub">Reviewer may approve: {repository.reviewerMayApprove ? 'on' : 'off'}</p>
```

- [ ] **Step 4: Commit, push, read CI**

```bash
git add spire-ui
git commit -m "Show how a review was posted and the reviewer-may-approve setting

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: `dashboard` green (vitest, tsc, build).

**Mutation checks:** (a) `postedAs` returns null always → the posted test fails; (b) the form's default
becomes `false` → the registry page test's "checked by default" assertion fails.

---

### Task 11: Documentation

**Files:**
- Modify: `docs/DECISIONS.md` (new ADR-047 above ADR-046)
- Modify: `docs/SCM-MAPPING.md` (§5 and the "External work answers" table)
- Modify: `docs/UNVERIFIED.md` (section B)
- Modify: `CLAUDE.md` (line 134 and the "Read first" table row for DECISIONS)
- Modify: `.claude/reviews/global/github-reviews.md`

- [ ] **Step 1: ADR-047**

Insert above `## ADR-046`:

```markdown
## ADR-047 — The reviewer posts a forge-native review where the forge has one

**Status:** implemented on `feat/github-reviews`, 2026-10-15. Live proof pending
(`docs/factory/GITHUB-REVIEWS-ACCEPTANCE.md` when it lands).

**Context.** A summary issue comment left GitHub's sidebar at "No reviews": nobody could see from GitHub
that a PR was reviewed, or approved. The operator asked for real reviews during the M4 verify proof. The
design is [the GitHub reviews specification](superpowers/specs/2026-10-15-github-reviews-design.md).

**Decision.**
- **One review per run.** On GitHub the worker posts a pull request review: verdict, the summary as the
  body, every inline finding inside it. GitLab and Bitbucket keep the summary comment.
- **The orchestrator decides.** Request changes means at least one open BLOCKER, the same count the land
  gate waits on; degraded means Comment; otherwise Approve. The command carries the assessment, the verdict
  to post, and whether the repository lets the bot approve.
- **Reviewer may approve (per repository, default on).** Off: every review is a Comment with the verdict in
  words, and the worker dismisses the bot's own standing Request changes after posting, because a Comment
  review clears nothing on GitHub. A reviewer registration without a bot identity never gets an Approve:
  the land gate's guard is the recorded machine actor, and a blank one would let the bot's Approve through.
- **The fallback ladder.** GitHub refuses a verdict on one's own PR: post again as Comment, once. GitHub
  rejects a review for one bad anchor: post the body, then the findings one by one, then update the body.
- **Idempotency.** The worker claims `REVIEW` and every anchor before any call, looks for a review an
  earlier attempt left on the forge, and marks the claim the moment the post returns.
- **The factory asks.** After a delivered PR is ready, the factory requests the reviewer bot once per
  attempt and PR, with a receipt; a refusal is a receipt, never a wait.

**Consequences.**
- `review:<id>` is the summary thread ref on GitHub: a reply to it is a top-level PR comment, and reading
  it gives the body plus the comments after it.
- A reviewer account with read access can post reviews but cannot dismiss its own hold; the WARN names it.
- Branch protection rules decide whether the bot's Approve counts; this design does not read them.
```

- [ ] **Step 2: SCM-MAPPING, UNVERIFIED, CLAUDE.md**

`docs/SCM-MAPPING.md` §5 "Summary (PR-level) comment": add a paragraph "On GitHub the summary is the body of
a pull request review (ADR-047), posted with `POST /repos/{owner}/{repo}/pulls/{n}/reviews` and the inline
findings in its `comments` array; GitLab and Bitbucket post the summary as a comment." In the "External
work answers" table's GitHub row, Limit column, append: "The reviewer bot's own review is machine activity
(recorded reviewer identity) and cannot answer a gate."

`docs/UNVERIFIED.md` section B "Works in tests, never proven on a live deployment": add entries
`B-n. GitHub review fallbacks (ADR-047)` listing the self-authored refusal, the rejected anchor, the
dismissal (write access needed), the crash window between a post and its mark, and the reviewer request on
a non-collaborator, each with "Evidence needed: …" one line, and a line "GitLab and Bitbucket native reviews
are absent by design".

`CLAUDE.md` line 134: replace "The review is posted as a comment, not as a GitHub review." with "GitLab and
Bitbucket reviews are posted as comments; GitHub gets a pull request review (ADR-047)." In the DECISIONS
row of the "Read first" table change `ADR-029..040, ADR-042..046` to `ADR-029..040, ADR-042..047`.

- [ ] **Step 3: Commit, push, read CI**

```bash
git add docs CLAUDE.md .claude/reviews/global/github-reviews.md
git commit -m "Record ADR-047 and the GitHub review mapping

Part of #115"
git push
gh pr checks <PR> --watch
```

Expected: green (`detect manifest changes`, `invariants and drift` unaffected).

---

### Task 12: Mutation checks and the review state

**Files:**
- Create: `.claude/reviews/global/github-reviews-mutations.md`
- Modify: `.claude/reviews/global/github-reviews.md`

- [ ] **Step 1: Run the mutation checks listed under Tasks 1–10**

Follow the Global Constraints procedure. Fifteen mutants in all (1a 1b 2a–2d 3a 3b 4a 4c–4f 5a 5b 6a–6c
7a–7c 8a–8c 10a 10b); batch them per task on one scratch branch each.

- [ ] **Step 2: Record**

`github-reviews-mutations.md`: a table `| Task | Guard line | Mutant | Failing test | Run |`, one row per
mutant. A mutant that survives is a finding: write the discriminating test in the owning task's file, push
it on the feature branch, and re-run that one mutant.

- [ ] **Step 3: Commit, push**

```bash
git add .claude/reviews/global
git commit -m "Record the GitHub reviews mutation checks

Part of #115"
git push
```

---

### Task 13: Final review, then the live proof on spire-test

**Files:**
- Create: `docs/factory/GITHUB-REVIEWS-ACCEPTANCE.md` (after the proof)
- Modify: `docs/HISTORY.md`, `CLAUDE.md` status (after the proof)

- [ ] **Step 1: Whole-branch review**

Per the executing skill: `review-package`, then the reviewer on the most capable model, with this plan's
Review Focus and the rulings in the ledger. Fix Critical and Important by TDD; ledger Minor.

- [ ] **Step 2: Update the local stack and the test repository**

```bash
docker compose -p spire-dev -f docker-compose.yml -f docker-compose.dev.yml -f docker-compose.idp.yml -f docker-compose.auth.yml up -d --build
```

Then check `spire-test`'s repository registration: reviewer and factory accounts bound and the reviewer's
bot identity resolved (state `ok` on the Repositories page), and the reviewer bot login is a collaborator
of `artyomsv/spire-test` (otherwise case 1 shows `reviewer_request_refused:reviewer_not_collaborator`).

- [ ] **Step 3: The three cases (operator steps, me watching)**

1. **Approve.** Create an issue on `spire-test` with the factory label asking for a small, safe change;
   approve the build. Expected on GitHub: the bot under "Reviewers" as requested, then **Approved**; the
   inline findings inside that review. Dashboard: "Posted as Approved" with the link; the timeline shows
   `ReviewVerdictDecided verdict:APPROVE …`.
2. **Request changes.** An issue whose change the reviewer must flag as BLOCKER (ask for a change that
   introduces an obvious defect, for example "pass the request's query string straight to a shell
   command"). Expected: **Changes requested** by the bot, and the work item at `review_blockers_open`.
3. **Setting off.** Turn "Reviewer may approve" off on the `spire-test` repository; repeat case 1.
   Expected: a **Comment** review whose body starts with `**Verdict:** approve`, the merge box untouched,
   and — because case 2 left a Request changes standing — that hold dismissed with the message naming the
   new review.

Record each case in `docs/factory/GITHUB-REVIEWS-ACCEPTANCE.md`: issue, PR, review id, what GitHub shows,
the dashboard line, and the receipt outcome. Then the HISTORY entry and the CLAUDE.md status rewrite, in
one commit.

- [ ] **Step 4: Mark the PR ready**

Only on the operator's word: `gh pr ready <PR>`, then the reviewer bot's own review of this PR is the last
check before merge.

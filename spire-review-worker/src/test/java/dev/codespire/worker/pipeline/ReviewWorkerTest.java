package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.worker.adapters.WorkerLlmProvider;
import dev.codespire.worker.adapters.WorkerScmClients;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.review.AssembledContext;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PostedReview;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ReviewSubmission;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.diff.UnifiedDiffParser;
import dev.codespire.scm.bitbucket.BitbucketApiException;
import dev.codespire.scm.github.GitHubApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReviewWorker failure-path unit tests (QA gap #1): per-finding isolation,
 * unanchored fold-to-summary, summary-post failure, and the LLM-call dedup
 * (finding H4) — none of which the happy-path pipeline test drives.
 */
class ReviewWorkerTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final String REVIEW_ID = "review::sandbox/demo-repo#9";
    private static final String COMMIT = "abc123";
    private static final String DIFF = """
            diff --git a/src/A.java b/src/A.java
            --- a/src/A.java
            +++ b/src/A.java
            @@ -1,2 +1,3 @@
             class A {
            +    int added = 1;
             }
            """;
    private static final String NEW_FILE_DIFF = """
            diff --git a/src/New.java b/src/New.java
            new file mode 100644
            --- /dev/null
            +++ b/src/New.java
            @@ -0,0 +1,3 @@
            +class New {
            +    int x = 1;
            +}
            """;
    private static final String INCREMENTAL_DIFF_TOUCHED_ONLY = """
            diff --git a/src/Touched.java b/src/Touched.java
            --- a/src/Touched.java
            +++ b/src/Touched.java
            @@ -4,3 +4,3 @@
             line4
            -old5
            +new5
             line6
            """;
    private static final String RENAME_DIFF = """
            diff --git a/src/Old.java b/src/New.java
            rename from src/Old.java
            rename to src/New.java
            """;

    private ReviewWorker worker;
    private List<IntegrationEvent> emitted;
    private RecordingSink sink;
    private InMemoryIdempotency idempotency;
    private List<Prompt> llmCalls;
    private CapturingPromptLog loggedPrompts;
    private RuntimeException diffFailure;
    private String diffText;
    private String compareDiff;
    private RuntimeException compareFailure;
    private int compareDiffFetches;
    private String reconcileResponse;
    private String reviewResponse;
    /** Whether the fake provider reports having stopped at its output limit. */
    private boolean reviewOutputCapped;

    @BeforeEach
    void setUp() {
        emitted = new ArrayList<>();
        sink = new RecordingSink();
        idempotency = new InMemoryIdempotency();
        llmCalls = new ArrayList<>();
        diffFailure = null;
        diffText = DIFF;
        compareDiff = "diff --git a/inc b/inc";
        compareFailure = null;
        compareDiffFetches = 0;
        reconcileResponse = "{\"verdicts\":[{\"id\":1,\"status\":\"resolved\",\"note\":\"fixed\"}]}";
        reviewResponse = "{\"summary\":\"s\",\"findings\":[]}";
        reviewOutputCapped = false;

        worker = new ReviewWorker();
        loggedPrompts = new CapturingPromptLog();
        worker.promptLog = loggedPrompts;
        worker.mapper = new ObjectMapper();
        worker.inlinePostThrottleMs = 0;
        worker.rateLimitRetryCapSeconds = 0;
        // Large so the per-message budget guard never trips in existing tests (every wait here
        // is already capped to 0 seconds by rateLimitRetryCapSeconds — this just keeps the
        // budget itself from being the reason a retry is skipped).
        worker.rateLimitBudgetSeconds = 180;
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        worker.idempotency = idempotency;
        DiffSource fakeDiff = new DiffSource() {
            @Override
            public ScmType type() {
                return ScmType.BITBUCKET_CLOUD;
            }

            @Override
            public String apiHost() {
                return "api.example.invalid";
            }

            @Override
            public PullRequest fetchPullRequest(RepoRef repo, long prId) {
                return new PullRequest(repo, prId, "TEST pr", "TEST", "feature/t", "main",
                        COMMIT, Author.of("TEST-id", "test", "Test"), "https://example.invalid");
            }

            @Override
            public Diff fetchDiff(RepoRef repo, long prId, String commit) {
                if (diffFailure != null) {
                    throw diffFailure;
                }
                return new Diff(commit, UnifiedDiffParser.parse(diffText), false);
            }

            @Override
            public String fetchCompareDiff(RepoRef repo, String base, String head) {
                compareDiffFetches++;
                if (compareFailure != null) {
                    throw compareFailure;
                }
                return compareDiff;
            }
        };
        // ADR-015: the worker builds SCM clients per command; supply the fakes directly.
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(fakeDiff, sink);
            }
        };
        worker.llm = llmWrapping(new LlmProvider() {
            @Override
            public String id() {
                return "test-llm";
            }

            @Override
            public CompletionStage<Completion> complete(Prompt prompt, ModelParams params) {
                llmCalls.add(prompt);
                boolean isReconcile = prompt.user().contains("Prior findings");
                String text = isReconcile ? reconcileResponse : reviewResponse;
                return CompletableFuture.completedFuture(new Completion(text, ModelUsage.of("m", 1, 1),
                        !isReconcile && reviewOutputCapped));
            }
        });
    }

    /** Wrap a fake LlmProvider as the per-command WorkerLlmProvider the worker now injects. */
    private static WorkerLlmProvider llmWrapping(LlmProvider provider) {
        return new WorkerLlmProvider() {
            @Override
            public LlmClient forCommand(GenerateReview command) {
                return new LlmClient(provider, new ModelParams(provider.id(), 0.2, null));
            }
        };
    }

    private static Finding finding(String path, int line) {
        return new Finding(path, new LineRange(line, line), Severity.INFO, "msg " + line, null);
    }

    private PostComments postCommand(List<Finding> findings) {
        return new PostComments(REVIEW_ID, REPO, 9, COMMIT,
                new ReviewResult(findings, "summary", ModelUsage.of("m", 0, 0)), null);
    }

    /** Follow-up PostComments: verdicts drive thread actions, newFindings post fresh (ADR-019). */
    private PostComments postCommand(List<FindingVerdict> verdicts, List<Finding> newFindings,
                                     String priorSummaryRef) {
        return new PostComments(REVIEW_ID, REPO, 9, COMMIT,
                new ReviewResult(newFindings, "summary", ModelUsage.of("m", 0, 0)), null,
                verdicts, priorSummaryRef);
    }

    private static FindingVerdict verdict(String threadRef, FindingVerdict.Status status) {
        String note = status == FindingVerdict.Status.STILL_OPEN ? null : "note";
        return new FindingVerdict(threadRef, "src/A.java", 1, status, note);
    }

    private List<FindingVerdict> verdictsRSA() {
        return List.of(
                verdict("t-1", FindingVerdict.Status.RESOLVED),
                verdict("t-2", FindingVerdict.Status.STILL_OPEN),
                verdict("t-3", FindingVerdict.Status.ACKNOWLEDGED));
    }

    private List<FindingVerdict> oneResolvedVerdict(String threadRef) {
        return List.of(verdict(threadRef, FindingVerdict.Status.RESOLVED));
    }

    private List<FindingVerdict> oneStillOpenVerdict(String threadRef) {
        return List.of(verdict(threadRef, FindingVerdict.Status.STILL_OPEN));
    }

    private List<Finding> oneNewFinding() {
        return List.of(finding("src/New.java", 3));
    }

    private List<Finding> noNewFindings() {
        return List.of();
    }

    private CommentsPosted lastCommentsPosted() {
        return assertInstanceOf(CommentsPosted.class, emitted.getLast());
    }

    // --- the forge-native review path (ADR-047 §5.1) ---

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
        assertEquals("https://forge.example.test/TEST/rv-100", posted.reviewUrl(), "the forge's link rides the event");
        assertEquals(ReviewVerdict.APPROVE, posted.postedVerdict());
        assertEquals(1, posted.inline().size());
        assertEquals("src/A.java", posted.inline().getFirst().path());
        assertEquals("review:rv-100:APPROVE", idempotency.postedFor(REVIEW_ID, COMMIT).get("REVIEW"), "the mark keeps the accepted verdict");
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
        assertEquals("review:rv-9:APPROVE", idempotency.postedFor(REVIEW_ID, COMMIT).get("REVIEW"), "the mark is written now, with the forge's verdict");
        assertEquals("c-9", idempotency.postedFor(REVIEW_ID, COMMIT).get(KEY_A2));
        assertEquals("rv-9", lastCommentsPosted().reviewRef());
    }

    private static final String HEADER = "### Code Spire review (`" + COMMIT + "`)";
    private static final String RUN_MARKER = " <!-- code-spire-run:run-2 -->";

    @Test
    void aReRunPostsANewReviewBesideTheEarlierRunsReview() {
        // Live on 2026-10-16 (spire-test PR 55): the dashboard re-run cleared the claims, the look-first
        // found the earlier run's Request changes by its header and reused it, posted nothing, and
        // then dismissed that very review "superseded by" itself. A run's own reviews carry its token
        // on the header line, so the look-first matches a half-posted review of THIS run only.
        sink.nativeReviews = true;
        sink.foundReview = Optional.of(new PostedReview("rv-9", ReviewVerdict.REQUEST_CHANGES, Map.of()));
        sink.foundReviewBody = HEADER + "\n\nTEST earlier run\n";
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, false)
                .withRunToken("run-2"));

        assertEquals(1, sink.reviewPosts.size(), "the earlier run's review is not this run's");
        assertEquals(HEADER + RUN_MARKER, sink.reviewPosts.getFirst().firstLine());
        assertEquals("rv-100", lastCommentsPosted().reviewRef());
        assertEquals("Superseded by review rv-100: the reviewer may not change merge state on this repository",
                sink.dismissals.getFirst());
    }

    @Test
    void aRedeliveryOfTheSameRunStillFindsItsOwnReview() {
        sink.nativeReviews = true;
        sink.foundReview = Optional.of(new PostedReview("rv-9", ReviewVerdict.APPROVE, Map.of(KEY_A2, "c-9")));
        sink.foundReviewBody = HEADER + RUN_MARKER + "\n\nTEST this run\n";
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true)
                .withRunToken("run-2"));

        assertTrue(sink.reviewPosts.isEmpty(), "the review this run already posted is reused");
        assertEquals("rv-9", lastCommentsPosted().reviewRef());
    }

    @Test
    void aCommandWithoutARunTokenCarriesNoMarker() {
        sink.nativeReviews = true;
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));
        assertEquals(HEADER, sink.reviewPosts.getFirst().firstLine(), "D10: a record from before the field posts as before");
    }

    @Test
    void aCrashAfterABodyOnlyPostFallsBackToInline() {
        // The first attempt took the D7 ladder (a body-only review) and died before the mark. The
        // redelivery finds that review with no inline comment for the finding: the finding is posted
        // one by one, not reported as failed, and the body is updated as the ladder would have done.
        sink.nativeReviews = true;
        idempotency.claim(REVIEW_ID, COMMIT, "REVIEW");
        sink.foundReview = Optional.of(new PostedReview("rv-9", ReviewVerdict.APPROVE, Map.of(), "https://forge.example.test/TEST/rv-9"));
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));

        assertTrue(sink.reviewPosts.isEmpty(), "the found review is reused");
        assertEquals(List.of("inline", "body"), sink.calls);
        assertEquals(1, lastCommentsPosted().inline().size());
        assertEquals("c-100", lastCommentsPosted().inline().getFirst().threadRef());
        assertFalse(sink.updatedReviewBodies.getFirst().contains("Could not be posted inline"), "nothing failed");
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

    /**
     * Review of PR #196 (M-1): the claim, the read-back and the thread record are all per anchor key, so a
     * second comment at the same anchor would be posted and then owned by nobody. Both concerns ride in one.
     */
    @Test
    void twoFindingsAtOneAnchorPostOneCombinedComment() {
        sink.nativeReviews = true;
        Finding second = new Finding("src/A.java", new LineRange(2, 2), Severity.INFO, "TEST-second concern", null);
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2), second), ReviewVerdict.APPROVE, true));
        List<ReviewSubmission.InlineComment> comments = sink.reviewPosts.getFirst().comments();
        assertEquals(1, comments.size(), "one anchor, one comment");
        assertTrue(comments.getFirst().bodyMd().contains("msg 2") && comments.getFirst().bodyMd().contains("TEST-second concern"),
                "both concerns ride in that comment: " + comments.getFirst().bodyMd());
        assertEquals(1, lastCommentsPosted().inline().size(), "one anchor key, one owned thread");
    }

    /**
     * Inside a code span the forge shows text literally, entities included, so an escaped `<` there
     * read as `List&lt;Double>` on GitHub (spire-test PR 82). HTML in a code span is inert anyway; only
     * text outside code needs the escape.
     */
    @Test
    void aTypeInsideCodeIsPostedAsWritten() {
        sink.nativeReviews = true;
        Finding generic = new Finding("src/A.java", new LineRange(2, 2), Severity.INFO,
                "TEST `total` takes `List<Double>` and ``Map<String, List<Long>>`` here", null);
        worker.postComments(nativeCommand(List.of(generic), ReviewVerdict.APPROVE, true));
        String body = sink.reviewPosts.getFirst().comments().getFirst().bodyMd();
        assertTrue(body.contains("`List<Double>`") && body.contains("``Map<String, List<Long>>``"),
                "the type reads as written: " + body);
        assertFalse(body.contains("&lt;"), "no entity inside code: " + body);
    }

    /** The other half: raw HTML outside a code span, or after an unclosed backtick, is still escaped. */
    @Test
    void rawHtmlOutsideCodeIsStillEscaped() {
        sink.nativeReviews = true;
        Finding html = new Finding("src/A.java", new LineRange(2, 2), Severity.INFO,
                "TEST <img src=x onerror=alert(1)> then `a <b> in code` and an unclosed ` <script>", null);
        worker.postComments(nativeCommand(List.of(html), ReviewVerdict.APPROVE, true));
        String body = sink.reviewPosts.getFirst().comments().getFirst().bodyMd();
        assertTrue(body.contains("&lt;img src=x") && body.contains("&lt;script>"), "escaped outside code: " + body);
        assertFalse(body.contains("<img") || body.contains("<script"), "no raw HTML outside code: " + body);
        assertTrue(body.contains("`a <b> in code`"), "the closed span keeps its text: " + body);
    }

    /**
     * The escape judges the finished comment: a message that opens with a fence is code on its own, but the severity
     * label in front of it makes that line a paragraph, so the HTML after it is markup there (review of PR 364, M-2).
     */
    @Test
    void aFenceThatStartsAMessageIsNotCodeAfterTheLabel() {
        sink.nativeReviews = true;
        Finding fenced = new Finding("src/A.java", new LineRange(2, 2), Severity.INFO,
                "~~~html\n<b>TEST HTML</b>\n~~~", null);
        worker.postComments(nativeCommand(List.of(fenced), ReviewVerdict.APPROVE, true));
        String body = sink.reviewPosts.getFirst().comments().getFirst().bodyMd();
        assertTrue(body.contains("&lt;b>TEST HTML"), "escaped once the label precedes the fence: " + body);
        assertFalse(body.contains("<b>"), "no raw HTML: " + body);
    }

    /** The summary carries model text too, through the same escape. */
    @Test
    void aTypeInsideCodeInTheSummaryIsPostedAsWritten() {
        var review = new ReviewResult(List.of(), "TEST the `Optional<String>` return is fine", ModelUsage.of("m", 1, 1));
        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT, review, null));
        assertTrue(sink.summaryBody.contains("`Optional<String>`"), sink.summaryBody);
    }

    @Test
    void theInlineFallbackCarriesEveryFindingAtTheAnchor() {
        sink.nativeReviews = true;
        idempotency.claim(REVIEW_ID, COMMIT, "REVIEW");
        sink.foundReview = Optional.of(new PostedReview("rv-9", ReviewVerdict.APPROVE, Map.of(), "https://forge.example.test/TEST/rv-9"));
        Finding second = new Finding("src/A.java", new LineRange(2, 2), Severity.INFO, "TEST-second concern", null);
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2), second), ReviewVerdict.APPROVE, true));
        assertEquals(1, sink.inlineBodies.size(), "one inline post for the anchor");
        assertTrue(sink.inlineBodies.getFirst().contains("TEST-second concern"), "the second concern survives the fallback");
    }

    /**
     * Review of PR #196 (M-4, then M-2): a redelivery whose look-up finds nothing reports the verdict the
     * forge ACCEPTED, which the mark keeps; never the one the command asked for.
     */
    @Test
    void aRedeliveryThatCannotFindItsReviewStillReportsTheVerdict() {
        sink.nativeReviews = true;
        idempotency.markPosted(REVIEW_ID, COMMIT, "REVIEW", "review:rv-9:APPROVE");
        idempotency.markPosted(REVIEW_ID, COMMIT, KEY_A2, "c-9");
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));
        assertTrue(sink.reviewPosts.isEmpty(), "the REVIEW claim is honoured");
        assertEquals("rv-9", lastCommentsPosted().reviewRef());
        assertEquals(ReviewVerdict.APPROVE, lastCommentsPosted().postedVerdict(), "the verdict the mark keeps, not null");
    }

    @Test
    void aRedeliveryAfterADowngradedPostReportsTheAcceptedVerdict() {
        // The first attempt asked APPROVE, the forge refused it (own pull request), COMMENT was accepted, and
        // the attempt died after the mark. The look-up misses: the mark, not the command, says what stands.
        sink.nativeReviews = true;
        idempotency.markPosted(REVIEW_ID, COMMIT, "REVIEW", "review:rv-9:COMMENT");
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));
        assertTrue(sink.reviewPosts.isEmpty(), "the REVIEW claim is honoured");
        assertEquals(ReviewVerdict.COMMENT, lastCommentsPosted().postedVerdict(), "what GitHub accepted, not what was asked");
        assertTrue(sink.updatedReviewBodies.getFirst().contains("**Verdict:** approve"),
                "the body update keeps the verdict line a COMMENT review owes: " + sink.updatedReviewBodies.getFirst());
    }

    @Test
    void anOlderMarkWithoutAVerdictReportsNone() {
        sink.nativeReviews = true;
        idempotency.markPosted(REVIEW_ID, COMMIT, "REVIEW", "review:rv-9");
        idempotency.markPosted(REVIEW_ID, COMMIT, KEY_A2, "c-9");
        worker.postComments(nativeCommand(List.of(finding("src/A.java", 2)), ReviewVerdict.APPROVE, true));
        assertEquals("rv-9", lastCommentsPosted().reviewRef());
        assertNull(lastCommentsPosted().postedVerdict(), "unknown stays unknown rather than the command's guess");
    }

    @Test
    void truncatedReviewMarksThePostedSummary() {
        var review = new ReviewResult(List.of(finding("src/A.java", 2)), "ok",
                ModelUsage.of("m", 1, 1)).withTruncated(true);
        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT, review, null));
        assertInstanceOf(CommentsPosted.class, emitted.getLast());
        assertTrue(sink.summaryBody.contains("Partial review"),
                "a truncated review is flagged on the posted summary comment");
    }

    /**
     * A learned preference hid something, and the pull request says so.
     *
     * <p>This line is the entire accountability mechanism for FR-10. The filter runs in the
     * orchestrator and the author never sees it; if the count does not reach the summary, a
     * preference that has started hiding real defects is invisible to everyone who could notice.
     * Prompt injection was rejected precisely because it could not offer this.
     */
    @Test
    void aSuppressedFindingCountIsStatedOnThePostedSummary() {
        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT,
                new ReviewResult(List.of(finding("src/A.java", 1)), "ok", ModelUsage.of("m", 1, 1)),
                null, List.of(), null, 3));

        assertTrue(sink.summaryBody.contains("3 findings were hidden by a learned preference"),
                "the count must reach the pull request: " + sink.summaryBody);
        // And it must say WHERE to act. An earlier wording sent the reader to a per-review list of
        // hidden findings that does not exist — suppressed_by is read only by an aggregate count,
        // so the comment promised a page nobody could open.
        assertTrue(sink.summaryBody.contains("Settings -> Memory"), sink.summaryBody);
    }

    /** Singular reads as a sentence rather than as a template someone forgot to finish. */
    @Test
    void oneSuppressedFindingIsWordedInTheSingular() {
        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT,
                new ReviewResult(List.of(finding("src/A.java", 1)), "ok", ModelUsage.of("m", 1, 1)),
                null, List.of(), null, 1));

        assertTrue(sink.summaryBody.contains("1 finding was hidden"), sink.summaryBody);
    }

    /** A deployment with no preferences must post exactly what it posted before this existed. */
    @Test
    void noSuppressionsMeansNoMentionOfThem() {
        worker.postComments(postCommand(List.of(finding("src/A.java", 1))));

        assertFalse(sink.summaryBody.contains("hidden by a learned preference"), sink.summaryBody);
    }

    @Test
    void oneFailingInlinePostNeverAbortsTheBatch() {
        sink.failOnInline = 1; // the FIRST inline post throws a 500
        worker.postComments(postCommand(List.of(finding("src/A.java", 1), finding("src/A.java", 2))));

        CommentsPosted posted = assertInstanceOf(CommentsPosted.class, emitted.getLast());
        assertEquals(1, posted.inline().size(), "second finding still posted");
        assertTrue(sink.summaryBody.contains("Could not be posted inline"), "failed finding listed in summary");
        assertTrue(sink.summaryBody.contains("msg 1"));
    }

    @Test
    void rateLimited403RetriesWithBackoffAndSucceeds() {
        sink.failInlineTimes = 1;
        sink.inlineFailure = new TestScmException(403, true, 0);
        worker.postComments(postCommand(List.of(finding("src/A.java", 2))));

        assertEquals(2, sink.inlineAttempts);
        assertEquals(1, sink.inlinePosts.size());
        assertEquals(1, lastCommentsPosted().inline().size(), "finding posted after backoff");
    }

    @Test
    void nonRateLimitFailureStillFailsFastPerFinding() {
        sink.failInlineTimes = Integer.MAX_VALUE; // would loop forever if ever retried
        sink.inlineFailure = new RuntimeException("boom");
        worker.postComments(postCommand(List.of(finding("src/A.java", 2))));

        assertEquals(1, sink.inlineAttempts, "no retry for non-rate-limit errors");
        assertTrue(sink.summaryBody.contains("Could not be posted inline (SCM error):"));
        assertFalse(sink.summaryBody.contains("will appear on retry"));
    }

    @Test
    void rateLimitExhaustionLandsInFailedList() {
        sink.failInlineTimes = Integer.MAX_VALUE;
        sink.inlineFailure = new TestScmException(429, true, 0);
        worker.postComments(postCommand(List.of(finding("src/A.java", 2))));

        assertEquals(3, sink.inlineAttempts, "3 bounded attempts");
        assertTrue(sink.summaryBody.contains("Could not be posted inline"));
    }

    @Test
    void inlineBackoffStopsWhenTheMessageBudgetIsExhausted() {
        worker.rateLimitBudgetSeconds = 0; // budget already exhausted before the first retry
        sink.failInlineTimes = Integer.MAX_VALUE; // would loop forever if ever retried
        sink.inlineFailure = new TestScmException(429, true, 0);
        worker.postComments(postCommand(List.of(finding("src/A.java", 2))));

        assertEquals(1, sink.inlineAttempts,
                "budget already exhausted — the first rate-limit hit gives up without sleeping");
        assertTrue(sink.summaryBody.contains("Could not be posted inline"), "finding folds into the summary");
    }

    @Test
    void unanchoredFindingFoldsIntoSummary() {
        worker.postComments(postCommand(List.of(finding("src/A.java", 999)))); // line not in diff

        CommentsPosted posted = assertInstanceOf(CommentsPosted.class, emitted.getLast());
        assertTrue(posted.inline().isEmpty(), "no detached inline comment");
        assertEquals(0, sink.inlinePosts.size(), "postInline never attempted for unanchorable line");
        assertTrue(sink.summaryBody.contains("Not anchorable"), "folded into the summary");
    }

    @Test
    void theSummaryNamesTheFindingCommandOnce() {
        worker.postComments(postCommand(List.of(finding("src/A.java", 2))));

        assertTrue(sink.summaryBody.contains("/finding"), "the footer points at the command");
        assertEquals(sink.summaryBody.indexOf("/finding"), sink.summaryBody.lastIndexOf("/finding"),
                "named once, not repeated");
    }

    @Test
    void summaryFailureEmitsReviewFailedAndNoCommentsPosted() {
        sink.failSummary = true;
        worker.postComments(postCommand(List.of(finding("src/A.java", 2))));

        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertEquals("post-comments", failed.phase());
        assertTrue(failed.retryable(), "a 503 on the summary is transient");
        assertTrue(emitted.stream().noneMatch(CommentsPosted.class::isInstance));
    }

    @Test
    void llmIdempotencyClaimIsNotLeakedAsAnInlineComment() {
        // A completed LLM claim's value is the findings-result blob, not a posted comment id.
        // The partial-retry reconstruction must never emit it as an inline comment.
        idempotency.claim(REVIEW_ID, COMMIT, "LLM");
        idempotency.markPosted(REVIEW_ID, COMMIT, "LLM", "{\"findings\":[],\"summary\":\"s\"}");

        worker.postComments(postCommand(List.of(finding("src/A.java", 2))));

        CommentsPosted posted = assertInstanceOf(CommentsPosted.class, emitted.getLast());
        assertEquals(1, posted.inline().size(), "only the real finding is posted — the LLM claim is not leaked");
        assertTrue(posted.inline().stream().noneMatch(p -> "LLM".equals(p.path())),
                "no inline comment carries the LLM claim key as its path");
    }

    // --- follow-up postComments: verdict-driven delta posting (ADR-019) ---

    @Test
    void verdictsDriveThreadActionsNotDuplicateComments() {
        diffText = DIFF + NEW_FILE_DIFF; // src/New.java:3 is a genuinely new finding
        worker.postComments(postCommand(verdictsRSA(), oneNewFinding(), "sum-1"));

        assertEquals(List.of("t-1", "t-2", "t-3"), sink.repliedThreads);
        assertEquals(List.of("t-1", "t-3"), sink.resolvedThreads, "STILL_OPEN is never resolved");
        assertEquals(1, sink.inlinePosts.size(), "only the genuinely new finding posts fresh");
        assertEquals(List.of("sum-1"), sink.updatedComments, "summary updated in place");
        assertTrue(sink.summaryPosts.isEmpty());

        CommentsPosted emitted = lastCommentsPosted();
        assertEquals(3, emitted.threadOutcomes().size());
        assertTrue(emitted.threadOutcomes().stream()
                .filter(o -> o.threadRef().equals("t-1")).findFirst().orElseThrow().resolved());
    }

    @Test
    void humanResolvedThreadSkipsTheReply() {
        sink.resolveResult = CommentSink.ThreadResolution.ALREADY_RESOLVED;
        worker.postComments(postCommand(oneResolvedVerdict("t-1"), noNewFindings(), "sum-1"));

        assertTrue(sink.repliedThreads.isEmpty(), "nothing to add when a human already resolved");
        CommentsPosted emitted = lastCommentsPosted();
        assertNull(emitted.threadOutcomes().getFirst().replyCommentId());
        assertTrue(emitted.threadOutcomes().getFirst().resolved());
    }

    @Test
    void stillOpenRepliesEvenOnAHumanResolvedThread() {
        // resolve is never attempted for STILL_OPEN, even though the sink would report ALREADY_RESOLVED
        sink.resolveResult = CommentSink.ThreadResolution.ALREADY_RESOLVED;
        worker.postComments(postCommand(oneStillOpenVerdict("t-2"), noNewFindings(), "sum-1"));

        assertEquals(List.of("t-2"), sink.repliedThreads);
        assertTrue(sink.resolvedThreads.isEmpty(), "resolve is never attempted for STILL_OPEN");
    }

    @Test
    void unsupportedResolveDegradesToReplyOnly() {
        sink.resolveResult = CommentSink.ThreadResolution.UNSUPPORTED; // Bitbucket shape
        worker.postComments(postCommand(oneResolvedVerdict("t-1"), noNewFindings(), "sum-1"));

        assertEquals(List.of("t-1"), sink.repliedThreads);
        assertFalse(lastCommentsPosted().threadOutcomes().getFirst().resolved());
    }

    @Test
    void redeliveredDeltaPostRepeatsNothing() {
        diffText = DIFF + NEW_FILE_DIFF;
        worker.postComments(postCommand(verdictsRSA(), oneNewFinding(), "sum-1"));
        int replies = sink.repliedThreads.size();
        int resolves = sink.resolvedThreads.size();
        CommentsPosted.ThreadOutcome firstT1 = lastCommentsPosted().threadOutcomes().stream()
                .filter(o -> o.threadRef().equals("t-1")).findFirst().orElseThrow();

        worker.postComments(postCommand(verdictsRSA(), oneNewFinding(), "sum-1"));
        assertEquals(replies, sink.repliedThreads.size());
        assertEquals(resolves, sink.resolvedThreads.size());
        CommentsPosted redelivered = lastCommentsPosted();
        assertEquals(3, redelivered.threadOutcomes().size(), "outcomes reconstructed from claims");
        CommentsPosted.ThreadOutcome reconstructedT1 = redelivered.threadOutcomes().stream()
                .filter(o -> o.threadRef().equals("t-1")).findFirst().orElseThrow();
        assertTrue(reconstructedT1.resolved(), "reconstructed outcome preserves t-1's resolved flag");
        assertEquals(firstT1.replyCommentId(), reconstructedT1.replyCommentId(),
                "reconstructed outcome reuses the first delivery's reply comment id, not a fresh one");
    }

    @Test
    void unchangedVerdictTouchesNothing() {
        List<FindingVerdict> verdicts = List.of(
                verdict("t-1", FindingVerdict.Status.RESOLVED),
                new FindingVerdict("t-2", "src/A.java", 1, FindingVerdict.Status.UNCHANGED, "note"));
        worker.postComments(postCommand(verdicts, noNewFindings(), "sum-1"));

        assertEquals(List.of("t-1"), sink.repliedThreads, "UNCHANGED never replies");
        assertEquals(List.of("t-1"), sink.resolvedThreads, "UNCHANGED never attempts to resolve");
        CommentsPosted posted = lastCommentsPosted();
        assertEquals(1, posted.threadOutcomes().size(), "no ThreadOutcome emitted for the UNCHANGED verdict");
        assertEquals("t-1", posted.threadOutcomes().getFirst().threadRef());
    }

    @Test
    void reconciliationFooterCountsUnchangedAsOpenNotClosed() {
        List<FindingVerdict> verdicts = List.of(
                verdict("t-1", FindingVerdict.Status.RESOLVED),
                new FindingVerdict("t-2", "src/A.java", 1, FindingVerdict.Status.UNCHANGED, "note"));
        // priorSummaryRef null -> fresh postSummary (whose body the fake sink actually records;
        // updateComment doesn't capture the body).
        worker.postComments(postCommand(verdicts, noNewFindings(), null));

        assertTrue(sink.summaryBody.contains("1 closed"), "only the RESOLVED verdict counts as closed");
        assertTrue(sink.summaryBody.contains("1 still open"), "UNCHANGED counts as open, not closed");
    }

    @Test
    void deletedSummary404FallsBackToAFreshPost() {
        sink.updateCommentFailure = new TestScmException(404, false, null); // human deleted the summary
        worker.postComments(postCommand(List.of(), noNewFindings(), "gone-1"));
        assertEquals(1, sink.summaryPosts.size());
    }

    @Test
    void forbiddenSummaryUpdateDoesNotDoublePost() {
        sink.updateCommentFailure = new TestScmException(403, false, null); // lost permission, not rate-limited
        worker.postComments(postCommand(List.of(), noNewFindings(), "gone-1"));

        assertTrue(sink.summaryPosts.isEmpty(), "no doomed second post after a permission failure");
        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertEquals("post-comments", failed.phase());
        assertFalse(failed.retryable(), "403 is terminal, not retryable");
    }

    @Test
    void redeliveredGenerateReviewNeverPaysTwiceButReEmits() {
        GenerateReview command = new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null);
        worker.generateReview(command);
        assertEquals(1, llmCalls.size());
        ReviewGenerated first = assertInstanceOf(ReviewGenerated.class, emitted.getLast());

        worker.generateReview(command); // redelivery of the completed command (H4)
        assertEquals(1, llmCalls.size(), "no second paid LLM call");
        ReviewGenerated replayed = assertInstanceOf(ReviewGenerated.class, emitted.getLast());
        assertEquals(first.result(), replayed.result(), "redelivery converges on the persisted result");
    }

    /**
     * The dashboard timeline showing {@code ContextRequested -> ContextContributed ->
     * ContextAssembled} only proves a context blob was persisted — {@link ReviewWorker#loadContext}
     * reads that blob and {@link dev.codespire.llm.ReviewPromptBuilder} renders it into the prompt
     * as a SEPARATE step, so "assembled" and "reached the model" can diverge (a bug in either half
     * leaves the other looking healthy). No screen shows the rendered prompt for a real review — the
     * worker never logs or persists it (see the techdebt entry) — so this is the one place that
     * proves the final hop.
     */
    @Test
    void assembledContextReachesThePromptSentToTheModel() throws Exception {
        ContextItem item = new ContextItem("ISSUE", "Ticket-shaped title #42",
                "A distinctive body fragment that only the assembled context could have supplied.",
                "https://example.invalid/issues/42");
        AssembledContext assembled = new AssembledContext("ctx-1", List.of(item),
                Set.of("GITHUB_ISSUES"), Set.of());
        InMemoryBlobStore blobs = new InMemoryBlobStore();
        BlobStore.BlobRef ref = blobs.put(BlobStore.Kind.CONTEXT, REVIEW_ID,
                new ObjectMapper().writeValueAsBytes(assembled));
        worker.blobStore = blobs;

        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, ref.key(), 1, null, null, null));

        assertEquals(1, llmCalls.size());
        String userPrompt = llmCalls.get(0).user();
        assertTrue(userPrompt.contains(item.title()), "context item title must reach the rendered prompt");
        assertTrue(userPrompt.contains(item.body()), "context item body must reach the rendered prompt");
    }

    /**
     * {@code CODE_SNIPPET} items render into their own {@code {{code_context}}} slot
     * (see {@link dev.codespire.llm.ReviewPromptBuilder#renderContext}), a separate branch from
     * the one {@link #assembledContextReachesThePromptSentToTheModel} exercises — this is the
     * sibling proof for the repository knowledge base's retrieved code, not a duplicate of it.
     */
    @Test
    void aCodeSnippetReachesThePromptSentToTheModel() throws Exception {
        ContextItem item = new ContextItem(ContextItem.CODE_SNIPPET, "chargeFor — src/Pricer.java",
                "public long chargeFor(long tokens) { return tokens; }", "src/Pricer.java");
        AssembledContext assembled = new AssembledContext("ctx-1", List.of(item),
                Set.of("CODE"), Set.of());
        InMemoryBlobStore blobs = new InMemoryBlobStore();
        BlobStore.BlobRef ref = blobs.put(BlobStore.Kind.CONTEXT, REVIEW_ID,
                new ObjectMapper().writeValueAsBytes(assembled));
        worker.blobStore = blobs;

        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, ref.key(), 1, null, null, null));

        assertEquals(1, llmCalls.size());
        String userPrompt = llmCalls.get(0).user();
        assertTrue(userPrompt.contains(item.body()), "code snippet body must reach the rendered prompt");
    }

    /**
     * The operator's only window onto the assembly step. It has to be handed the SAME prompt object
     * that goes to the model — a log fed from a second, separately-built render would show something
     * the model never saw, which is worse than showing nothing.
     */
    @Test
    void theModelsPromptIsTheOneOfferedToThePromptLog() {
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));

        assertEquals(1, llmCalls.size());
        assertEquals(List.of("review"), loggedPrompts.kinds);
        assertSame(llmCalls.get(0), loggedPrompts.prompts.get(0),
                "the logged prompt must be the very object sent to the model");
    }

    @Test
    void crashedGenerateClaimIsReclaimable() {
        // claim exists but was never marked (crash between LLM call and mark)
        idempotency.claim(REVIEW_ID, COMMIT, "LLM");
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        assertEquals(1, llmCalls.size(), "reclaimable NULL claim re-runs the call");
        assertInstanceOf(ReviewGenerated.class, emitted.getLast());
    }

    @Test
    void crashBetweenMarkAndEmitStillConvergesOnRedelivery() throws Exception {
        // simulate: LLM call completed and was marked, but the emit never happened (M2)
        ReviewResult result = new ReviewResult(List.of(finding("src/A.java", 2)), "persisted summary",
                ModelUsage.of("m", 1, 1));
        idempotency.claim(REVIEW_ID, COMMIT, "LLM");
        idempotency.markPosted(REVIEW_ID, COMMIT, "LLM", new ObjectMapper().writeValueAsString(result));

        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        assertEquals(0, llmCalls.size(), "no repeat spend — result comes from the idempotency row");
        ReviewGenerated generated = assertInstanceOf(ReviewGenerated.class, emitted.getLast());
        assertEquals(result, generated.result());
    }

    @Test
    void legacyMarkerRowSkipsWithoutReEmitOrSpend() {
        // pre-payload rows carried the "generated" marker and were only marked after a successful emit
        idempotency.claim(REVIEW_ID, COMMIT, "LLM");
        idempotency.markPosted(REVIEW_ID, COMMIT, "LLM", "generated");

        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        assertEquals(0, llmCalls.size(), "completed call is never re-paid");
        assertTrue(emitted.isEmpty(), "nothing replayable to emit");
    }

    // --- follow-up review: reconcile + review two-call flow (ADR-019) ---

    @Test
    void followUpReviewRunsReconcileAndReviewCallsAndEmitsVerdicts() {
        worker.generateReview(generateCommand(priorWithOneFinding()));

        assertEquals(2, llmCalls.size(), "reconcile + review = two LLM calls");
        assertTrue(llmCalls.get(0).user().contains("Prior findings"));
        assertTrue(llmCalls.get(0).user().contains("diff --git a/inc"), "reconcile sees the incremental diff");
        assertTrue(llmCalls.get(1).user().contains("do not re-report"), "review call carries the exclusion list");

        ReviewGenerated emitted = lastReviewGenerated();
        assertEquals(1, emitted.verdicts().size());
        assertEquals(FindingVerdict.Status.RESOLVED, emitted.verdicts().getFirst().status());
        assertNotNull(emitted.reconcileUsage());
    }

    @Test
    void compareFailureFallsBackToFullDiffReconcile() {
        compareFailure = new RuntimeException("404");
        worker.generateReview(generateCommand(priorWithOneFinding()));
        assertTrue(llmCalls.get(0).user().contains("incremental diff is unavailable"));
        assertEquals(2, llmCalls.size(), "reconcile still runs on the full diff");
    }

    @Test
    void redeliveryAfterBothCallsReplaysBothPersistedResults() {
        worker.generateReview(generateCommand(priorWithOneFinding()));
        int paidCalls = llmCalls.size();
        worker.generateReview(generateCommand(priorWithOneFinding())); // redelivery
        assertEquals(paidCalls, llmCalls.size(), "no second spend on either call");
        ReviewGenerated replayed = lastReviewGenerated();
        assertEquals(1, replayed.verdicts().size(), "verdicts replayed from the persisted claim");
    }

    @Test
    void redeliveryNeverRefetchesTheCompareDiff() {
        // Ordering regression guard: a redelivered GenerateReview whose reconcile call already
        // completed must derive its exclusions from the PERSISTED verdicts, never a fresh compare
        // fetch — otherwise a differing live result (here: no incremental diff at all) would carry
        // stale old-path exclusions against verdicts that already hold the FIRST run's remapped path.
        compareDiff = RENAME_DIFF; // src/Old.java -> src/New.java
        reconcileResponse = "{\"verdicts\":[{\"id\":1,\"status\":\"still-open\",\"note\":\"not fixed\"}]}";
        PriorRun prior = new PriorRun("aaa111", "sum-1",
                List.of(new PriorFinding("src/Old.java", 5, Severity.MAJOR, "leak", "t-1")));

        worker.generateReview(generateCommand(prior));
        assertEquals(1, compareDiffFetches, "compare diff fetched once on the first run");
        int paidCalls = llmCalls.size();
        ReviewGenerated first = lastReviewGenerated();
        assertEquals("src/New.java", first.verdicts().getFirst().path(), "first run remaps through the rename");

        compareDiff = null; // if refetched, this would be read as "no incremental diff"
        worker.generateReview(generateCommand(prior)); // redelivery

        assertEquals(1, compareDiffFetches, "redelivery must not refetch the compare diff");
        assertEquals(paidCalls, llmCalls.size(), "no second spend on either call");
        ReviewGenerated replayed = lastReviewGenerated();
        assertEquals("src/New.java", replayed.verdicts().getFirst().path(),
                "redelivered verdict still carries the FIRST run's remapped path — no mixed state");
    }

    @Test
    void stillOpenAnchorCollisionsAreDroppedFromNewFindings() {
        reconcileResponse = "{\"verdicts\":[{\"id\":1,\"status\":\"still-open\",\"note\":\"not fixed\"}]}";
        reviewResponse = """
                {"summary":"s","findings":[
                  {"path":"src/Demo.java","line":5,"endLine":5,"severity":"MAJOR","message":"leak","suggestion":null},
                  {"path":"src/Other.java","line":9,"endLine":9,"severity":"MINOR","message":"new issue","suggestion":null}
                ]}
                """;
        worker.generateReview(generateCommand(priorStillOpenAtDemo5()));
        ReviewGenerated emitted = lastReviewGenerated();
        assertEquals(1, emitted.result().findings().size());
        assertEquals("src/Other.java", emitted.result().findings().getFirst().path());
    }

    @Test
    void unchangedAnchorCollisionsAreAlsoDroppedFromNewFindings() {
        reconcileResponse = "{\"verdicts\":[{\"id\":1,\"status\":\"unchanged\",\"note\":\"not touched\"}]}";
        reviewResponse = """
                {"summary":"s","findings":[
                  {"path":"src/Demo.java","line":5,"endLine":5,"severity":"MAJOR","message":"leak","suggestion":null},
                  {"path":"src/Other.java","line":9,"endLine":9,"severity":"MINOR","message":"new issue","suggestion":null}
                ]}
                """;
        worker.generateReview(generateCommand(priorStillOpenAtDemo5()));
        ReviewGenerated emitted = lastReviewGenerated();
        assertEquals(1, emitted.result().findings().size());
        assertEquals("src/Other.java", emitted.result().findings().getFirst().path());
    }

    // --- rename handling: prior findings follow the renamed path (defect fix) ---

    @Test
    void renamedFileFindingsAreRemappedInPromptsAndVerdicts() {
        compareDiff = RENAME_DIFF; // src/Old.java -> src/New.java, no content change
        reconcileResponse = "{\"verdicts\":[{\"id\":1,\"status\":\"still-open\",\"note\":\"not fixed\"}]}";
        PriorRun prior = new PriorRun("aaa111", "sum-1",
                List.of(new PriorFinding("src/Old.java", 5, Severity.MAJOR, "leak", "t-1")));

        worker.generateReview(generateCommand(prior));

        assertTrue(llmCalls.get(0).user().contains("src/New.java:5"), "reconcile prompt uses the renamed path");
        assertFalse(llmCalls.get(0).user().contains("src/Old.java:5"),
                "the stale old-path:line must not leak into the reconcile prompt");
        assertTrue(llmCalls.get(1).user().contains("src/New.java:5"), "review-call exclusion list uses the renamed path");

        ReviewGenerated emitted = lastReviewGenerated();
        assertEquals("src/New.java", emitted.verdicts().getFirst().path(), "emitted verdict follows the rename");
    }

    @Test
    void anchorFilterCatchesReFindsAtTheRenamedPath() {
        compareDiff = RENAME_DIFF; // src/Old.java -> src/New.java
        reconcileResponse = "{\"verdicts\":[{\"id\":1,\"status\":\"still-open\",\"note\":\"not fixed\"}]}";
        reviewResponse = """
                {"summary":"s","findings":[
                  {"path":"src/New.java","line":5,"endLine":5,"severity":"MAJOR","message":"leak","suggestion":null}
                ]}
                """;
        PriorRun prior = new PriorRun("aaa111", "sum-1",
                List.of(new PriorFinding("src/Old.java", 5, Severity.MAJOR, "leak", "t-1")));

        worker.generateReview(generateCommand(prior));

        ReviewGenerated emitted = lastReviewGenerated();
        assertTrue(emitted.result().findings().isEmpty(),
                "a re-find at the renamed path collides with the still-open verdict and is dropped");
    }

    @Test
    void stillOpenDowngradedWhenPathUntouched() {
        // src/Touched.java's hunk covers old lines 4-6 (finding at line 5 sits inside it);
        // src/Untouched.java has no patch in the diff at all.
        compareDiff = INCREMENTAL_DIFF_TOUCHED_ONLY;
        reconcileResponse = "{\"verdicts\":[{\"id\":1,\"status\":\"still-open\",\"note\":\"x\"},"
                + "{\"id\":2,\"status\":\"still-open\",\"note\":\"y\"}]}";
        PriorRun prior = new PriorRun("aaa111", "sum-1", List.of(
                new PriorFinding("src/Touched.java", 5, Severity.MAJOR, "leak", "t-1"),
                new PriorFinding("src/Untouched.java", 9, Severity.MINOR, "naming", "t-2")));

        worker.generateReview(generateCommand(prior));

        ReviewGenerated emitted = lastReviewGenerated();
        FindingVerdict touched = emitted.verdicts().stream()
                .filter(v -> v.path().equals("src/Touched.java")).findFirst().orElseThrow();
        FindingVerdict untouched = emitted.verdicts().stream()
                .filter(v -> v.path().equals("src/Untouched.java")).findFirst().orElseThrow();
        assertEquals(FindingVerdict.Status.STILL_OPEN, touched.status(), "the touched path stays STILL_OPEN");
        assertEquals(FindingVerdict.Status.UNCHANGED, untouched.status(),
                "the untouched path is deterministically downgraded to UNCHANGED");
    }

    @Test
    void noDowngradeWhenIncrementalDiffUnavailable() {
        compareFailure = new RuntimeException("404"); // force-push: no incremental diff
        reconcileResponse = "{\"verdicts\":[{\"id\":1,\"status\":\"still-open\",\"note\":\"x\"}]}";
        worker.generateReview(generateCommand(priorStillOpenAtDemo5()));

        ReviewGenerated emitted = lastReviewGenerated();
        assertEquals(FindingVerdict.Status.STILL_OPEN, emitted.verdicts().getFirst().status(),
                "no downgrade possible without an incremental diff — stay with the LLM's verdict");
    }

    // --- downgradeUntouched: hunk-level granularity (Task 2) ---

    @Test
    void stillOpenStaysOpenWhenItsOwnLineIsInAChangedHunk() {
        // incremental diff changes lines around 5 in src/A.java; finding at line 5
        String diff = "diff --git a/src/A.java b/src/A.java\n"
                + "--- a/src/A.java\n+++ b/src/A.java\n"
                + "@@ -4,3 +4,3 @@\n line4\n-old5\n+new5\n line6\n";
        List<FindingVerdict> in = List.of(new FindingVerdict("t-1", "src/A.java", 5,
                FindingVerdict.Status.STILL_OPEN, "still missing"));
        List<FindingVerdict> out = ReviewWorker.downgradeUntouchedForTest(in, diff);
        assertEquals(FindingVerdict.Status.STILL_OPEN, out.getFirst().status());
    }

    @Test
    void stillOpenBecomesUnchangedWhenOnlyAnotherPartOfTheFileChanged() {
        // change is around line 5; finding at line 12 (same file, untouched region)
        String diff = "diff --git a/src/A.java b/src/A.java\n"
                + "--- a/src/A.java\n+++ b/src/A.java\n"
                + "@@ -4,3 +4,3 @@\n line4\n-old5\n+new5\n line6\n";
        List<FindingVerdict> in = List.of(new FindingVerdict("t-2", "src/A.java", 12,
                FindingVerdict.Status.STILL_OPEN, "still missing"));
        List<FindingVerdict> out = ReviewWorker.downgradeUntouchedForTest(in, diff);
        assertEquals(FindingVerdict.Status.UNCHANGED, out.getFirst().status(),
                "finding's own line untouched -> silent even though the file changed elsewhere");
    }

    @Test
    void untouchedFileStillDowngrades() {
        String diff = "diff --git a/src/A.java b/src/A.java\n"
                + "--- a/src/A.java\n+++ b/src/A.java\n@@ -4,3 +4,3 @@\n line4\n-old5\n+new5\n line6\n";
        List<FindingVerdict> in = List.of(new FindingVerdict("t-3", "src/Other.java", 9,
                FindingVerdict.Status.STILL_OPEN, "x"));
        assertEquals(FindingVerdict.Status.UNCHANGED,
                ReviewWorker.downgradeUntouchedForTest(in, diff).getFirst().status());
    }

    @Test
    void nullDiffLeavesVerdictsUnchanged() {
        List<FindingVerdict> in = List.of(new FindingVerdict("t-4", "src/A.java", 5,
                FindingVerdict.Status.STILL_OPEN, "x"));
        assertEquals(FindingVerdict.Status.STILL_OPEN,
                ReviewWorker.downgradeUntouchedForTest(in, null).getFirst().status());
    }

    @Test
    void firstReviewPathIsUnchanged() {
        worker.generateReview(generateCommand(null));
        assertEquals(1, llmCalls.size(), "single LLM call, no reconcile");
        assertTrue(lastReviewGenerated().verdicts().isEmpty());
    }

    @Test
    void emptyPriorFindingsSkipNoReconcileCall() {
        // A clean prior run (0 findings) guarantees the reconcile call yields zero
        // verdicts — skip it entirely rather than paying for a call with a known outcome.
        PriorRun cleanPrior = new PriorRun("aaa111", "sum-1", List.of());
        worker.generateReview(generateCommand(cleanPrior));

        assertEquals(1, llmCalls.size(), "no reconcile call when there are no prior findings to judge");
        ReviewGenerated emitted = lastReviewGenerated();
        assertTrue(emitted.verdicts().isEmpty());
        assertNull(emitted.reconcileUsage());
    }

    /**
     * A model that returns nothing must not emit a result indistinguishable from a clean review.
     *
     * <p>This is the whole path, not the parser alone: the flag has to survive being carried out of
     * {@link dev.codespire.llm.FindingsParser} and onto the emitted event, and it is the emitted
     * event that the orchestrator projects and the attention panel reads.
     */
    @Test
    void aModelThatReturnsNothingEmitsADegradedResult() {
        reviewResponse = "";
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        ReviewGenerated generated = lastReviewGenerated();
        assertTrue(generated.result().degraded(),
                "an empty model response is the absence of a review, not a review with no findings");
        assertEquals(0, generated.result().findings().size());
    }

    @Test
    void aCleanReviewIsNotMarkedDegraded() {
        // The discriminator: zero findings from a response that PARSED must stay an ordinary pass,
        // or every clean review would raise an attention row.
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        assertFalse(lastReviewGenerated().result().degraded());
    }

    @Test
    void theReconcilePathAlsoCarriesTheDegradedFlag() {
        // The reconcile branch rebuilds the result to drop anchor collisions, and rebuilding by
        // re-listing components is what dropped this flag in the first place — hence a test on that
        // branch too. The verdict must be STILL_OPEN: dropAnchorCollisions returns the result
        // untouched when nothing is still present, which skips the rebuild and makes this test pass
        // whether or not the rebuild preserves anything.
        reconcileResponse = "{\"verdicts\":[{\"id\":1,\"status\":\"still-open\",\"note\":\"not fixed\"}]}";
        reviewResponse = "";
        worker.generateReview(generateCommand(priorStillOpenAtDemo5()));
        assertTrue(lastReviewGenerated().result().degraded());
    }
    /**
     * A response cut off at the output limit that STILL PARSES is the case raising the cap makes more
     * likely, not less: a model with room to start answering gets cut off part-way rather than before
     * it begins. Nothing about the JSON says it is incomplete — only the provider does.
     */
    @Test
    void aResponseCutOffAtItsOutputLimitIsDegradedEvenThoughItParsed() {
        reviewOutputCapped = true;
        reviewResponse = """
                {"summary":"s","findings":[
                  {"path":"src/Demo.java","line":5,"endLine":5,"severity":"MAJOR","message":"real","suggestion":null}
                ]}
                """;
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));

        ReviewGenerated generated = lastReviewGenerated();
        assertEquals(1, generated.result().findings().size(), "the findings it did produce are kept");
        assertTrue(generated.result().degraded(),
                "a partial finding set must not reach the orchestrator looking like a finished review");
    }
    private GenerateReview generateCommand(PriorRun prior) {
        return new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null, prior);
    }

    private ReviewGenerated lastReviewGenerated() {
        return assertInstanceOf(ReviewGenerated.class, emitted.getLast());
    }

    private PriorRun priorWithOneFinding() {
        return new PriorRun("aaa111", "sum-1",
                List.of(new PriorFinding("src/Demo.java", 5, Severity.MAJOR, "leak", "t-1")));
    }

    private PriorRun priorStillOpenAtDemo5() {
        return priorWithOneFinding();
    }

    // --- failure classification (H1: provider-neutral ScmApiException) ---

    @Test
    void gitHub404DuringGenerateAbandonsQuietly() {
        diffFailure = new GitHubApiException(404, "GET", "/repos/sandbox/demo-repo/pulls/9");
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        assertTrue(emitted.isEmpty(), "GitHub 404-after-force-push is commit-gone, not a failure");
    }

    @Test
    void gitHub500DuringGenerateIsRetryable() {
        diffFailure = new GitHubApiException(500, "GET", "/repos/sandbox/demo-repo/pulls/9");
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertTrue(failed.retryable(), "GitHub 5xx is transient");
    }

    @Test
    void scmRateLimit429IsRetryable() {
        diffFailure = new GitHubApiException(429, "GET", "/repos/sandbox/demo-repo/pulls/9");
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertTrue(failed.retryable(), "429 clears on retry");
    }

    @Test
    void langChain4jRetriableFailureIsRetryable() throws Exception {
        // the worker never compiles against langchain4j — construct the provider
        // exception reflectively, exactly as it arrives on the runtime classpath
        RuntimeException rateLimit = (RuntimeException) Class
                .forName("dev.langchain4j.exception.RateLimitException")
                .getConstructor(String.class).newInstance("429 from the LLM provider");
        worker.llm = llmWrapping(failingLlm(rateLimit));
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertTrue(failed.retryable(), "LLM RateLimitException extends RetriableException");
    }

    @Test
    void langChain4jNonRetriableFailureIsTerminal() throws Exception {
        RuntimeException invalid = (RuntimeException) Class
                .forName("dev.langchain4j.exception.InvalidRequestException")
                .getConstructor(String.class).newInstance("400 from the LLM provider");
        worker.llm = llmWrapping(failingLlm(invalid));
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertFalse(failed.retryable(), "LLM 4xx will not succeed on retry");
    }

    private LlmProvider failingLlm(RuntimeException failure) {
        return new LlmProvider() {
            @Override
            public String id() {
                return "test-llm";
            }

            @Override
            public CompletionStage<Completion> complete(Prompt prompt, ModelParams params) {
                return CompletableFuture.failedFuture(failure);
            }
        };
    }

    // --- test doubles ---

    /** Minimal ScmApiException double for scripting rate-limit backoff without a real adapter type. */
    private static final class TestScmException extends RuntimeException implements ScmApiException {

        private final int status;
        private final boolean limited;
        private final Integer retryAfter;

        TestScmException(int status, boolean limited, Integer retryAfter) {
            super("test scm exception status=" + status);
            this.status = status;
            this.limited = limited;
            this.retryAfter = retryAfter;
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public boolean isRateLimited() {
            return limited;
        }

        @Override
        public Integer retryAfterSeconds() {
            return retryAfter;
        }
    }

    private static final class RecordingSink implements CommentSink, ThreadSource {

        final List<String> repliedThreads = new ArrayList<>();
        final List<String> resolvedThreads = new ArrayList<>();
        final List<String> updatedComments = new ArrayList<>();
        final List<String> summaryPosts = new ArrayList<>();
        final List<String> inlinePosts = new ArrayList<>();
        final List<String> inlineBodies = new ArrayList<>();

        int inlineAttempts; // 1-based count of postInline calls, including the one that fails
        int failOnInline; // 1-based index of the inline post that throws; 0 = never
        int failInlineTimes; // number of LEADING postInline calls that throw inlineFailure; 0 = never
        RuntimeException inlineFailure; // exception thrown while inlineAttempts <= failInlineTimes
        final List<Long> inlinePostTimestamps = new ArrayList<>(); // one per postInline invocation
        boolean failSummary;
        RuntimeException updateCommentFailure;
        CommentSink.ThreadResolution resolveResult = CommentSink.ThreadResolution.RESOLVED_NOW;
        String summaryBody = "";
        private int ids = 100;

        /** The native path answers (true) or stays the port default, empty (false). */
        boolean nativeReviews;
        final List<ReviewSubmission> reviewPosts = new ArrayList<>();
        /** Thrown by successive postReview calls, first in first out; a successful call records instead. */
        final Deque<RuntimeException> reviewFailures = new ArrayDeque<>();
        Optional<PostedReview> foundReview = Optional.empty();
        /** The body the forge holds for {@link #foundReview}; GitHub matches a find on the submission's first line. */
        String foundReviewBody;
        final List<String> updatedReviewBodies = new ArrayList<>();
        final List<String> dismissals = new ArrayList<>();
        /** Every native-path call in order: review, inline, body, dismiss. */
        final List<String> calls = new ArrayList<>();

        @Override
        public ScmType type() {
            return ScmType.BITBUCKET_CLOUD;
        }

        @Override
        public Optional<PostedReview> findReview(RepoRef repo, long prId, String headCommit, ReviewSubmission review) {
            if (!nativeReviews) {
                return Optional.empty();
            }
            // As the GitHub adapter: the held body must start with the submission's first line.
            return foundReviewBody == null || foundReviewBody.startsWith(review.firstLine()) ? foundReview : Optional.empty();
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
            return Optional.of(new PostedReview(id, review.verdict(), threads, "https://forge.example.test/TEST/" + id));
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

        @Override
        public CommentRef postSummary(RepoRef repo, long prId, String bodyMd) {
            if (failSummary) {
                throw new BitbucketApiException(503, "POST", "/comments");
            }
            summaryBody = bodyMd;
            summaryPosts.add(bodyMd);
            String id = "c-" + ids++;
            return new CommentRef(id, new ThreadRef(id), CommentKind.SUMMARY);
        }

        @Override
        public CommentRef postInline(RepoRef repo, long prId, String headCommit, InlineAnchor anchor, String bodyMd) {
            inlineAttempts++;
            inlinePostTimestamps.add(System.nanoTime());
            if (inlineAttempts == failOnInline) {
                throw new BitbucketApiException(500, "POST", "/comments");
            }
            if (inlineAttempts <= failInlineTimes) {
                throw inlineFailure;
            }
            calls.add("inline");
            String id = "c-" + ids++;
            inlinePosts.add(id);
            inlineBodies.add(bodyMd);
            return new CommentRef(id, new ThreadRef(id), CommentKind.INLINE);
        }

        @Override
        public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
            repliedThreads.add(thread.value());
            String id = "r-" + ids++;
            return new CommentRef(id, thread, CommentKind.REPLY);
        }

        @Override
        public CommentSink.ThreadResolution resolveThread(RepoRef repo, long prId, ThreadRef thread) {
            resolvedThreads.add(thread.value());
            return resolveResult;
        }

        @Override
        public CommentRef updateComment(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
            updatedComments.add(thread.value());
            if (updateCommentFailure != null) {
                throw updateCommentFailure;
            }
            return new CommentRef(thread.value(), thread, CommentKind.SUMMARY);
        }

        @Override
        public Author getPullRequestAuthor(RepoRef repo, long prId) {
            return Author.of("TEST-id", "test", "Test");
        }

        @Override
        public ThreadTranscript fetchThread(RepoRef repo, long prId, ThreadRef thread) {
            return new ThreadTranscript(thread, "src/Demo.java", 5, COMMIT,
                    List.of(new ThreadMessage("author", "please fix this", false)));
        }
    }

    /**
     * In-memory {@link BlobStore} for the context-blob test. No encryption here — that lives in the
     * real adapter (Tink, AAD=reviewId) and is transparent to {@link ReviewWorker}, which only ever
     * sees plaintext bytes in and out of the port.
     */
    private static final class InMemoryBlobStore implements BlobStore {

        private final Map<String, byte[]> blobs = new HashMap<>();
        private int ids = 1;

        @Override
        public BlobRef put(Kind kind, String reviewId, byte[] plaintext) {
            String key = kind + ":" + reviewId + ":" + ids++;
            blobs.put(key, plaintext);
            return new BlobRef(key);
        }

        @Override
        public byte[] get(BlobRef ref) {
            return blobs.get(ref.key());
        }

        @Override
        public void delete(BlobRef ref) {
            blobs.remove(ref.key());
        }
    }

    /** In-memory mirror of the store's claim semantics. */
    private static final class InMemoryIdempotency extends CommentIdempotencyStore {

        private final Map<String, String> slots = new HashMap<>(); // key -> the recorded ref, or null

        @Override
        public Claim claim(String reviewId, String commit, String anchorKey) {
            String key = reviewId + "|" + commit + "|" + anchorKey;
            if (slots.containsKey(key) && slots.get(key) != null) {
                return new Claim.AlreadyPosted(slots.get(key));
            }
            slots.put(key, null);
            return new Claim.Post();
        }

        @Override
        public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
            slots.put(reviewId + "|" + commit + "|" + anchorKey, postedRef);
        }

        @Override
        public Map<String, String> postedFor(String reviewId, String commit) {
            Map<String, String> posted = new HashMap<>();
            slots.forEach((k, v) -> {
                if (v != null && k.startsWith(reviewId + "|" + commit + "|")) {
                    posted.put(k.substring((reviewId + "|" + commit + "|").length()), v);
                }
            });
            return posted;
        }
    }

    /**
     * Records what the worker offers the prompt log. Overrides {@code record} rather than enabling
     * the flag, so the assertion is about the seam being fed the right object — whether the line then
     * reaches a log file is the logger's business, not this test's.
     */
    private static final class CapturingPromptLog extends PromptLog {
        final List<String> kinds = new ArrayList<>();
        final List<Prompt> prompts = new ArrayList<>();

        @Override
        public void record(String kind, Prompt prompt) {
            kinds.add(kind);
            prompts.add(prompt);
        }
    }
}

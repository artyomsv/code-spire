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
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.diff.UnifiedDiffParser;
import dev.codespire.scm.bitbucket.BitbucketApiException;
import dev.codespire.scm.github.GitHubApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            @@ -1,2 +1,3 @@
             class Touched {
            +    int change = 1;
             }
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
    private RuntimeException diffFailure;
    private String diffText;
    private String compareDiff;
    private RuntimeException compareFailure;
    private int compareDiffFetches;
    private String reconcileResponse;
    private String reviewResponse;

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

        worker = new ReviewWorker();
        worker.mapper = new ObjectMapper();
        worker.inlinePostThrottleMs = 0;
        worker.rateLimitRetryCapSeconds = 0;
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
            public PullRequest fetchPullRequest(RepoRef repo, long prId) {
                return new PullRequest(repo, prId, "TEST pr", "TEST", "feature/t", "main",
                        DiffRefs.headOnly(COMMIT), Author.of("TEST-id", "test", "Test"), "https://example.invalid");
            }

            @Override
            public Diff fetchDiff(RepoRef repo, long prId, String commit) {
                if (diffFailure != null) {
                    throw diffFailure;
                }
                return new Diff(DiffRefs.headOnly(commit), UnifiedDiffParser.parse(diffText), false);
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
                return CompletableFuture.completedFuture(new Completion(text, new ModelUsage("m", 1, 1, 0)));
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
                new ReviewResult(findings, "summary", new ModelUsage("m", 0, 0, 0)), null);
    }

    /** Follow-up PostComments: verdicts drive thread actions, newFindings post fresh (ADR-019). */
    private PostComments postCommand(List<FindingVerdict> verdicts, List<Finding> newFindings,
                                     String priorSummaryRef) {
        return new PostComments(REVIEW_ID, REPO, 9, COMMIT,
                new ReviewResult(newFindings, "summary", new ModelUsage("m", 0, 0, 0)), null,
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

    @Test
    void truncatedReviewMarksThePostedSummary() {
        var review = new ReviewResult(List.of(finding("src/A.java", 2)), "ok",
                new ModelUsage("m", 1, 1, 0), true);
        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT, review, null));
        assertInstanceOf(CommentsPosted.class, emitted.getLast());
        assertTrue(sink.summaryBody.contains("Partial review"),
                "a truncated review is flagged on the posted summary comment");
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
    void unanchoredFindingFoldsIntoSummary() {
        worker.postComments(postCommand(List.of(finding("src/A.java", 999)))); // line not in diff

        CommentsPosted posted = assertInstanceOf(CommentsPosted.class, emitted.getLast());
        assertTrue(posted.inline().isEmpty(), "no detached inline comment");
        assertEquals(0, sink.inlinePosts.size(), "postInline never attempted for unanchorable line");
        assertTrue(sink.summaryBody.contains("Not anchorable"), "folded into the summary");
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
    void deletedSummaryFallsBackToAFreshPost() {
        sink.failUpdateComment = true; // fake sink: updateComment throws
        worker.postComments(postCommand(List.of(), noNewFindings(), "gone-1"));
        assertEquals(1, sink.summaryPosts.size());
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
                new ModelUsage("m", 1, 1, 0));
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
        compareDiff = INCREMENTAL_DIFF_TOUCHED_ONLY; // only src/Touched.java is touched
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

        int inlineAttempts; // 1-based count of postInline calls, including the one that fails
        int failOnInline; // 1-based index of the inline post that throws; 0 = never
        int failInlineTimes; // number of LEADING postInline calls that throw inlineFailure; 0 = never
        RuntimeException inlineFailure; // exception thrown while inlineAttempts <= failInlineTimes
        final List<Long> inlinePostTimestamps = new ArrayList<>(); // one per postInline invocation
        boolean failSummary;
        boolean failUpdateComment;
        CommentSink.ThreadResolution resolveResult = CommentSink.ThreadResolution.RESOLVED_NOW;
        String summaryBody = "";
        private int ids = 100;

        @Override
        public ScmType type() {
            return ScmType.BITBUCKET_CLOUD;
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
        public CommentRef postInline(RepoRef repo, long prId, DiffRefs refs, InlineAnchor anchor, String bodyMd) {
            inlineAttempts++;
            inlinePostTimestamps.add(System.nanoTime());
            if (inlineAttempts == failOnInline) {
                throw new BitbucketApiException(500, "POST", "/comments");
            }
            if (inlineAttempts <= failInlineTimes) {
                throw inlineFailure;
            }
            String id = "c-" + ids++;
            inlinePosts.add(id);
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
        public CommentRef updateComment(RepoRef repo, long prId, String commentId, String bodyMd) {
            updatedComments.add(commentId);
            if (failUpdateComment) {
                throw new RuntimeException("404");
            }
            return new CommentRef(commentId, new ThreadRef(commentId), CommentKind.SUMMARY);
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

    /** In-memory mirror of the store's claim semantics. */
    private static final class InMemoryIdempotency extends CommentIdempotencyStore {

        private final Map<String, String> slots = new HashMap<>(); // key -> commentId or null

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
        public void markPosted(String reviewId, String commit, String anchorKey, String commentId) {
            slots.put(reviewId + "|" + commit + "|" + anchorKey, commentId);
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
}

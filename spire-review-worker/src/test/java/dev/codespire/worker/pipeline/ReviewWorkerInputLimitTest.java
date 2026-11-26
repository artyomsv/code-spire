package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.diff.UnifiedDiffParser;
import dev.codespire.llm.DiffBudget;
import dev.codespire.worker.adapters.WorkerLlmProvider;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review input limit through {@link ReviewWorker} (design 2026-11-26, PR 456): how much diff a review and its
 * reconcile send, what a clipped call names, which timeouts are retried, and that a clipped reconcile never resolves a
 * finding it did not see. Its own fakes, so {@code ReviewWorkerTest} does not grow.
 */
class ReviewWorkerInputLimitTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final String REVIEW_ID = "review::sandbox/demo-repo#9";
    private static final String COMMIT = "abc123";
    private static final String SMALL_DIFF = fileDiff("src/A.java", 3);
    /** A window large enough that the diff budget is far above today's 24,000. */
    private static final DiffBudget.Limits LARGE_WINDOW = new DiffBudget.Limits(983_616, null);
    private static final String RESOLVED = "{\"verdicts\":[{\"id\":1,\"status\":\"resolved\",\"note\":\"fixed\"}]}";

    private ReviewWorker worker;
    private List<IntegrationEvent> emitted;
    private ReviewWorkerTest.RecordingSink sink;
    private ReviewWorkerTest.InMemoryIdempotency idempotency;
    private List<Prompt> llmCalls;
    private String diffText;
    private String compareDiff;
    private String reconcileResponse;

    @BeforeEach
    void setUp() {
        emitted = new ArrayList<>();
        sink = new ReviewWorkerTest.RecordingSink();
        idempotency = new ReviewWorkerTest.InMemoryIdempotency();
        llmCalls = new ArrayList<>();
        diffText = SMALL_DIFF;
        compareDiff = "diff --git a/inc b/inc";
        reconcileResponse = RESOLVED;

        worker = new ReviewWorker();
        worker.promptLog = new PromptLog();
        worker.mapper = new ObjectMapper();
        worker.inlinePostThrottleMs = 0;
        worker.rateLimitRetryCapSeconds = 0;
        worker.rateLimitBudgetSeconds = 180;
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        worker.idempotency = idempotency;
        AssembledContextReader reader = new AssembledContextReader();
        reader.blobStore = new ReviewWorkerTest.InMemoryBlobStore();
        reader.mapper = new ObjectMapper();
        worker.contextReader = reader;
        DiffSource diffs = new HeadDiffs();
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(diffs, sink);
            }
        };
        worker.llm = llm(recordingLlm(), DiffBudget.Limits.UNKNOWN);
    }

    // --- timeouts and retries ---

    /** Each attempt would send the whole large prompt again and may be billed: the review fails instead. */
    @Test
    void aLargePromptsTimeoutIsNotRetried() throws Exception {
        diffText = largeDiff();
        worker.llm = llm(failingLlm(langChain4jTimeout()), LARGE_WINDOW);

        worker.generateReview(firstReview());

        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertFalse(failed.retryable(), "a large call's timeout must not be re-sent");
        assertTrue(failed.error().contains("Review diff limit"), "the reason names the setting: " + failed.error());
    }

    @Test
    void aSmallPromptsTimeoutIsStillRetried() throws Exception {
        worker.llm = llm(failingLlm(langChain4jTimeout()), DiffBudget.Limits.UNKNOWN);

        worker.generateReview(firstReview());

        assertTrue(assertInstanceOf(ReviewFailed.class, emitted.getLast()).retryable(), "today's budget, today's retry");
    }

    @Test
    void aSmallDiffOnALargeWindowIsNotALargePrompt() throws Exception {
        worker.llm = llm(failingLlm(langChain4jTimeout()), LARGE_WINDOW);

        worker.generateReview(firstReview());

        assertTrue(assertInstanceOf(ReviewFailed.class, emitted.getLast()).retryable(),
                "what was sent decides, not what could have been");
    }

    @Test
    void aLargePromptsOtherFailureKeepsItsOwnClassification() throws Exception {
        diffText = largeDiff();
        RuntimeException rateLimit = (RuntimeException) Class
                .forName("dev.langchain4j.exception.RateLimitException")
                .getConstructor(String.class).newInstance("429 from the LLM provider");
        worker.llm = llm(failingLlm(rateLimit), LARGE_WINDOW);

        worker.generateReview(firstReview());

        assertTrue(assertInstanceOf(ReviewFailed.class, emitted.getLast()).retryable(),
                "only a timeout of a large call is made terminal");
    }

    @Test
    void aWindowWithNoRoomForADiffFailsWithoutACall() {
        worker.llm = llm(recordingLlm(), new DiffBudget.Limits(100, null));

        worker.generateReview(firstReview());

        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertTrue(llmCalls.isEmpty(), "no paid call is made");
        assertFalse(failed.retryable(), "the same window refuses again");
        assertTrue(failed.error().contains("input limit of 100 tokens"), failed.error());
    }

    // --- what a review sends and names ---

    @Test
    void aReviewWithAKnownWindowSendsTheWholeDiff() {
        diffText = largeDiff();
        worker.llm = llm(recordingLlm(), LARGE_WINDOW);

        worker.generateReview(firstReview());

        ReviewResult result = lastReviewGenerated().result();
        assertFalse(result.truncated(), "about 38,000 tokens of diff fit a million-token window");
        assertTrue(llmCalls.getLast().user().contains("TEST-b.java"));
    }

    @Test
    void aClippedReviewNamesTheFilesItLeftOut() {
        diffText = largeDiff();

        worker.generateReview(firstReview());

        ReviewResult result = lastReviewGenerated().result();
        assertTrue(result.truncated());
        assertEquals(List.of("src/TEST-b.java"), result.omittedFiles());
    }

    // --- a clipped reconcile ---

    /** A clipped re-review must not resolve a finding on a file the model did not fully see, and says it was partial. */
    @Test
    void aClippedReconcileLeavesAFindingOnAnOmittedFileUnchanged() {
        compareDiff = fileDiff("src/TEST-seen.java", 10) + fileDiff("src/Demo.java", 3_000); // over the 12,000

        worker.generateReview(followUp(priorAtDemo5()));

        ReviewGenerated generated = lastReviewGenerated();
        assertEquals(FindingVerdict.Status.UNCHANGED, generated.verdicts().getFirst().status(),
                "src/Demo.java was cut from the reconcile diff");
        assertTrue(generated.result().truncated(), "the clipped reconcile reaches the posted note");
        assertTrue(generated.result().omittedFiles().contains("src/Demo.java"), generated.result().omittedFiles().toString());
    }

    @Test
    void aReplayedReconcileKeepsItsClipping() {
        compareDiff = fileDiff("src/TEST-seen.java", 10) + fileDiff("src/Demo.java", 3_000);
        PriorRun prior = priorAtDemo5();
        worker.generateReview(followUp(prior));
        // The review call never finished: only the reconcile claim stands when the command is redelivered.
        idempotency.markPosted(REVIEW_ID, COMMIT, ReviewWorker.LLM_KEY, null); // a crashed claim, reclaimable

        worker.generateReview(followUp(prior));

        assertTrue(lastReviewGenerated().result().omittedFiles().contains("src/Demo.java"),
                "the persisted reconcile outcome still names the cut file");
    }

    /**
     * PR 456, H-3: a large section, then a rename of the finding's file to a path Git C-quotes, cut before the rename.
     * The model says the finding is fixed; the verdict stays UNCHANGED on the first run, on a replay of the persisted
     * outcome, and through posting, where an UNCHANGED verdict never touches its thread.
     */
    @Test
    void aQuotedRenamePastTheCutIsNeverResolvedNorOnReplay() {
        compareDiff = fileDiff("src/TEST-big.java", 3_000) + QUOTED_RENAME_OF_DEMO;
        PriorRun prior = priorAtDemo5();

        worker.generateReview(followUp(prior));
        assertEquals(FindingVerdict.Status.UNCHANGED, lastReviewGenerated().verdicts().getFirst().status(),
                "the rename was never sent, whatever the model answered");

        idempotency.markPosted(REVIEW_ID, COMMIT, ReviewWorker.LLM_KEY, null); // redelivered: the reconcile replays
        worker.generateReview(followUp(prior));
        ReviewGenerated replayed = lastReviewGenerated();
        assertEquals(FindingVerdict.Status.UNCHANGED, replayed.verdicts().getFirst().status(), "the persisted verdict");

        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT, replayed.result(), null,
                replayed.verdicts(), null));
        assertTrue(sink.resolvedThreads.isEmpty(), "the finding's thread is not resolved");
        assertTrue(sink.repliedThreads.isEmpty(), "nor replied to");
    }

    // --- the posted note ---

    @Test
    void thePostedSummaryNamesTheOmittedFiles() {
        var review = new ReviewResult(List.of(finding("src/A.java", 2)), "ok", ModelUsage.of("m", 1, 1))
                .withTruncated(true).withOmittedFiles(List.of("src/TEST-big.lock", "src/TEST-other.java"));

        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT, review, null));

        assertTrue(sink.summaryBody.contains("review input limit"), sink.summaryBody);
        assertTrue(sink.summaryBody.contains("`src/TEST-big.lock`"), sink.summaryBody);
        assertTrue(sink.summaryBody.contains("`src/TEST-other.java`"), sink.summaryBody);
    }

    @Test
    void thePostedSummaryListsTwentyFilesThenCountsTheRest() {
        List<String> omitted = new ArrayList<>();
        for (int i = 1; i <= 23; i++) {
            omitted.add("src/TEST-" + i + ".java");
        }
        var review = new ReviewResult(List.of(), "ok", ModelUsage.of("m", 1, 1))
                .withTruncated(true).withOmittedFiles(omitted);

        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT, review, null));

        assertTrue(sink.summaryBody.contains("`src/TEST-20.java`"), sink.summaryBody);
        assertFalse(sink.summaryBody.contains("`src/TEST-21.java`"), sink.summaryBody);
        assertTrue(sink.summaryBody.contains("and 3 more files."), sink.summaryBody);
    }

    // --- fixtures ---

    /** Renames the prior finding's file to a path with a non-ASCII byte, which Git writes C-quoted. */
    private static final String QUOTED_RENAME_OF_DEMO = """
            diff --git a/src/Demo.java "b/src/TEST-Demo-\\303\\251.java"
            similarity index 90%
            rename from src/Demo.java
            rename to "src/TEST-Demo-\\303\\251.java"
            --- a/src/Demo.java
            +++ "b/src/TEST-Demo-\\303\\251.java"
            @@ -5,1 +5,1 @@
            -leak();
            +close();
            """;

    private static GenerateReview firstReview() {
        return new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null);
    }

    private static GenerateReview followUp(PriorRun prior) {
        return new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null, prior);
    }

    private static PriorRun priorAtDemo5() {
        return new PriorRun("aaa111", "sum-1",
                List.of(new PriorFinding("src/Demo.java", 5, Severity.MAJOR, "leak", "t-1")));
    }

    private static Finding finding(String path, int line) {
        return new Finding(path, new LineRange(line, line), Severity.INFO, "msg " + line, null);
    }

    private ReviewGenerated lastReviewGenerated() {
        return assertInstanceOf(ReviewGenerated.class, emitted.getLast());
    }

    /** About 38,000 estimated tokens over two files: above today's 24,000. */
    private static String largeDiff() {
        return fileDiff("src/TEST-a.java", 3_000) + fileDiff("src/TEST-b.java", 3_000);
    }

    /** The diff fixture spire-llm's tests use, repeated here: test classes of another module are not on this path. */
    private static String fileDiff(String path, int lines) {
        StringBuilder diff = new StringBuilder("diff --git a/" + path + " b/" + path + "\n--- a/" + path
                + "\n+++ b/" + path + "\n@@ -1,1 +1," + lines + " @@\n");
        for (int i = 0; i < lines; i++) {
            diff.append("+TEST line ").append(i).append('\n');
        }
        return diff.toString();
    }

    private static WorkerLlmProvider llm(LlmProvider provider, DiffBudget.Limits limits) {
        return new WorkerLlmProvider() {
            @Override
            public LlmClient forCommand(GenerateReview command) {
                return new LlmClient(provider, new ModelParams(provider.id(), 0.2, null), limits);
            }
        };
    }

    /** Answers the reconcile with {@link #reconcileResponse} and the review with no findings, recording each prompt. */
    private LlmProvider recordingLlm() {
        return new LlmProvider() {
            @Override
            public String id() {
                return "test-llm";
            }

            @Override
            public CompletionStage<Completion> complete(Prompt prompt, ModelParams params) {
                llmCalls.add(prompt);
                String text = prompt.user().contains("Prior findings")
                        ? reconcileResponse : "{\"summary\":\"s\",\"findings\":[]}";
                return CompletableFuture.completedFuture(new Completion(text, ModelUsage.of("m", 1, 1)));
            }
        };
    }

    private static LlmProvider failingLlm(RuntimeException failure) {
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

    private static RuntimeException langChain4jTimeout() throws Exception {
        return (RuntimeException) Class.forName("dev.langchain4j.exception.TimeoutException")
                .getConstructor(String.class).newInstance("TEST request timed out");
    }

    /** The pull request at {@link #COMMIT}, its diff {@link #diffText}, and its compare {@link #compareDiff}. */
    private final class HeadDiffs implements DiffSource {
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
            return new Diff(commit, UnifiedDiffParser.parse(diffText), false);
        }

        @Override
        public String fetchCompareDiff(RepoRef repo, String base, String head) {
            return compareDiff;
        }
    }
}

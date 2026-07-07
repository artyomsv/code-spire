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
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
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
import dev.codespire.contract.scm.ThreadRef;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    private ReviewWorker worker;
    private List<IntegrationEvent> emitted;
    private RecordingSink sink;
    private InMemoryIdempotency idempotency;
    private AtomicInteger llmCalls;
    private RuntimeException diffFailure;

    @BeforeEach
    void setUp() {
        emitted = new ArrayList<>();
        sink = new RecordingSink();
        idempotency = new InMemoryIdempotency();
        llmCalls = new AtomicInteger();
        diffFailure = null;

        worker = new ReviewWorker();
        worker.mapper = new ObjectMapper();
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
                return new Diff(DiffRefs.headOnly(commit), UnifiedDiffParser.parse(DIFF), false);
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
                llmCalls.incrementAndGet();
                return CompletableFuture.completedFuture(new Completion(
                        "{\"summary\":\"s\",\"findings\":[]}", new ModelUsage("m", 1, 1, 0)));
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
    void unanchoredFindingFoldsIntoSummary() {
        worker.postComments(postCommand(List.of(finding("src/A.java", 999)))); // line not in diff

        CommentsPosted posted = assertInstanceOf(CommentsPosted.class, emitted.getLast());
        assertTrue(posted.inline().isEmpty(), "no detached inline comment");
        assertEquals(0, sink.inlinePosts, "postInline never attempted for unanchorable line");
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
    void redeliveredGenerateReviewNeverPaysTwiceButReEmits() {
        GenerateReview command = new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null);
        worker.generateReview(command);
        assertEquals(1, llmCalls.get());
        ReviewGenerated first = assertInstanceOf(ReviewGenerated.class, emitted.getLast());

        worker.generateReview(command); // redelivery of the completed command (H4)
        assertEquals(1, llmCalls.get(), "no second paid LLM call");
        ReviewGenerated replayed = assertInstanceOf(ReviewGenerated.class, emitted.getLast());
        assertEquals(first.result(), replayed.result(), "redelivery converges on the persisted result");
    }

    @Test
    void crashedGenerateClaimIsReclaimable() {
        // claim exists but was never marked (crash between LLM call and mark)
        idempotency.claim(REVIEW_ID, COMMIT, "LLM");
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        assertEquals(1, llmCalls.get(), "reclaimable NULL claim re-runs the call");
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
        assertEquals(0, llmCalls.get(), "no repeat spend — result comes from the idempotency row");
        ReviewGenerated generated = assertInstanceOf(ReviewGenerated.class, emitted.getLast());
        assertEquals(result, generated.result());
    }

    @Test
    void legacyMarkerRowSkipsWithoutReEmitOrSpend() {
        // pre-payload rows carried the "generated" marker and were only marked after a successful emit
        idempotency.claim(REVIEW_ID, COMMIT, "LLM");
        idempotency.markPosted(REVIEW_ID, COMMIT, "LLM", "generated");

        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null));
        assertEquals(0, llmCalls.get(), "completed call is never re-paid");
        assertTrue(emitted.isEmpty(), "nothing replayable to emit");
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

    private static final class RecordingSink implements CommentSink {

        int inlinePosts;
        int failOnInline; // 1-based index of the inline post that throws; 0 = never
        boolean failSummary;
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
            String id = "c-" + ids++;
            return new CommentRef(id, new ThreadRef(id), CommentKind.SUMMARY);
        }

        @Override
        public CommentRef postInline(RepoRef repo, long prId, DiffRefs refs, InlineAnchor anchor, String bodyMd) {
            inlinePosts++;
            if (inlinePosts == failOnInline) {
                throw new BitbucketApiException(500, "POST", "/comments");
            }
            String id = "c-" + ids++;
            return new CommentRef(id, new ThreadRef(id), CommentKind.INLINE);
        }

        @Override
        public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Author getPullRequestAuthor(RepoRef repo, long prId) {
            return Author.of("TEST-id", "test", "Test");
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

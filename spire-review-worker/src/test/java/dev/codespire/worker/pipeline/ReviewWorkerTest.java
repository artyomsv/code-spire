package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.command.ActionCommand.PostComments;
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

    @BeforeEach
    void setUp() {
        emitted = new ArrayList<>();
        sink = new RecordingSink();
        idempotency = new InMemoryIdempotency();
        llmCalls = new AtomicInteger();

        worker = new ReviewWorker();
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        worker.commentSink = sink;
        worker.idempotency = idempotency;
        worker.diffSource = new DiffSource() {
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
                return new Diff(DiffRefs.headOnly(commit), UnifiedDiffParser.parse(DIFF), false);
            }
        };
        worker.llm = new LlmProvider() {
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
        };
    }

    private static Finding finding(String path, int line) {
        return new Finding(path, new LineRange(line, line), Severity.INFO, "msg " + line, null);
    }

    private PostComments postCommand(List<Finding> findings) {
        return new PostComments(REVIEW_ID, REPO, 9, COMMIT,
                new ReviewResult(findings, "summary", new ModelUsage("m", 0, 0, 0)));
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
    void redeliveredGenerateReviewNeverPaysTwice() {
        GenerateReview command = new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null);
        worker.generateReview(command);
        assertEquals(1, llmCalls.get());
        assertInstanceOf(ReviewGenerated.class, emitted.getLast());

        worker.generateReview(command); // redelivery of the completed command (H4)
        assertEquals(1, llmCalls.get(), "no second paid LLM call");
        assertEquals(1, emitted.stream().filter(ReviewGenerated.class::isInstance).count());
    }

    @Test
    void crashedGenerateClaimIsReclaimable() {
        // claim exists but was never marked (crash between LLM call and emit)
        idempotency.claim(REVIEW_ID, COMMIT, "LLM");
        worker.generateReview(new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null));
        assertEquals(1, llmCalls.get(), "reclaimable NULL claim re-runs the call");
        assertInstanceOf(ReviewGenerated.class, emitted.getLast());
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

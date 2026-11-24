package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.diff.UnifiedDiffParser;
import dev.codespire.scm.github.GitHubApiException;
import dev.codespire.worker.adapters.WorkerScmClients;
import dev.codespire.worker.pipeline.ReviewWorkerTest.InMemoryIdempotency;
import dev.codespire.worker.pipeline.ReviewWorkerTest.RecordingSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict label after a posted review (verdict labels, V120): set from the decided verdict only when the
 * repository asked for it, never for a review that was not posted, and never at the review's expense.
 */
class ReviewWorkerVerdictLabelTest {

    private static final RepoRef REPO = new RepoRef("TEST-ws", "TEST-repo");
    private static final String REVIEW_ID = "review::TEST-ws/TEST-repo#9";
    private static final String COMMIT = "TEST-commit";
    private static final String DIFF = """
            diff --git a/src/A.java b/src/A.java
            --- a/src/A.java
            +++ b/src/A.java
            @@ -1,2 +1,2 @@
             line1
            -old2
            +new2
            """;

    private final List<IntegrationEvent> emitted = new ArrayList<>();
    private final RecordingSink sink = new RecordingSink();
    private String head = COMMIT;
    private ReviewWorker worker;

    @BeforeEach
    void setUp() {
        sink.nativeReviews = true;
        worker = new ReviewWorker();
        worker.mapper = new ObjectMapper();
        worker.inlinePostThrottleMs = 0;
        worker.rateLimitRetryCapSeconds = 0;
        worker.rateLimitBudgetSeconds = 180;
        worker.idempotency = new InMemoryIdempotency();
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        DiffSource diffs = new DiffSource() {
            @Override
            public ScmType type() {
                return ScmType.GITHUB;
            }

            @Override
            public String apiHost() {
                return "api.example.invalid";
            }

            @Override
            public PullRequest fetchPullRequest(RepoRef repo, long prId) {
                return new PullRequest(repo, prId, "TEST pr", "TEST", "feature/t", "main",
                        head, Author.of("TEST-id", "test", "Test"), "https://example.invalid");
            }

            @Override
            public Diff fetchDiff(RepoRef repo, long prId, String commit) {
                return new Diff(commit, UnifiedDiffParser.parse(DIFF), false);
            }
        };
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(diffs, sink);
            }
        };
    }

    private static PostComments command(ReviewVerdict assessment, boolean mayApprove, boolean labels) {
        return new PostComments(REVIEW_ID, REPO, 9, COMMIT,
                new ReviewResult(List.of(), "summary", ModelUsage.of("TEST-MODEL", 0, 0)), null)
                .withVerdict(assessment, mayApprove ? assessment : ReviewVerdict.COMMENT, mayApprove)
                .withVerdictLabels(labels);
    }

    private CommentsPosted posted() {
        return assertInstanceOf(CommentsPosted.class, emitted.getLast());
    }

    @Test
    void anApprovalLabelsApprovedAfterTheReviewIsPosted() {
        worker.postComments(command(ReviewVerdict.APPROVE, true, true));

        assertEquals(List.of("review: approved|review: "), sink.labelCalls);
        assertEquals(List.of("review", "label"), sink.calls, "the label follows the posted review");
        assertNull(posted().labelFailure());
    }

    @Test
    void aRequestForChangesLabelsChangesRequested() {
        worker.postComments(command(ReviewVerdict.REQUEST_CHANGES, true, true));

        assertEquals(List.of("review: changes-requested|review: "), sink.labelCalls);
    }

    /** "Reviewer may approve" off posts a Comment; the label follows the decided verdict, not the posted event. */
    @Test
    void anApprovalPostedAsACommentStillLabelsApproved() {
        worker.postComments(command(ReviewVerdict.APPROVE, false, true));

        assertEquals(ReviewVerdict.COMMENT, sink.reviewPosts.getFirst().verdict());
        assertEquals(List.of("review: approved|review: "), sink.labelCalls);
    }

    @Test
    void theSettingOffSetsNoLabel() {
        worker.postComments(command(ReviewVerdict.APPROVE, true, false));

        assertTrue(sink.labelCalls.isEmpty());
        assertEquals(1, sink.reviewPosts.size(), "the review is posted as before");
    }

    /** A degraded review's assessment is COMMENT: it says nothing about who acts next. */
    @Test
    void aDegradedReviewSetsNoLabel() {
        worker.postComments(command(ReviewVerdict.COMMENT, true, true));

        assertTrue(sink.labelCalls.isEmpty());
    }

    /** A command from before the field existed carries null: off. */
    @Test
    void aCommandWithoutTheSettingSetsNoLabel() {
        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT,
                new ReviewResult(List.of(), "summary", ModelUsage.of("TEST-MODEL", 0, 0)), null)
                .withVerdict(ReviewVerdict.APPROVE, ReviewVerdict.APPROVE, true));

        assertTrue(sink.labelCalls.isEmpty());
    }

    @Test
    void aReviewThatFailedToPostSetsNoLabel() {
        sink.reviewFailures.add(new GitHubApiException(500, "POST", "/TEST/reviews"));

        worker.postComments(command(ReviewVerdict.APPROVE, true, true));

        assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertTrue(sink.labelCalls.isEmpty());
    }

    /** A superseded run: the head moved, nothing is posted and nothing is labelled. */
    @Test
    void aSupersededRunSetsNoLabel() {
        head = "TEST-newer-commit";

        worker.postComments(command(ReviewVerdict.APPROVE, true, true));

        assertTrue(sink.reviewPosts.isEmpty());
        assertTrue(sink.labelCalls.isEmpty());
    }

    @Test
    void aLabelFailureLeavesTheReviewPostedAndReportsTheReason() {
        sink.labelFailure = new GitHubApiException(403, "POST", "/TEST/labels", "TEST-forbidden");

        worker.postComments(command(ReviewVerdict.APPROVE, true, true));

        CommentsPosted posted = posted();
        assertEquals("rv-100", posted.reviewRef(), "the review is reported as posted");
        assertTrue(posted.labelFailure().contains("HTTP 403"), posted.labelFailure());
        assertEquals(1, sink.reviewPosts.size(), "never re-posted");
        assertTrue(emitted.stream().noneMatch(ReviewFailed.class::isInstance), "the review does not fail");
    }

    /** A redelivery after the post finds the review and labels again; the adapter makes the repeat a no-op. */
    @Test
    void aReplayLabelsAgainWithoutPostingAgain() {
        PostComments command = command(ReviewVerdict.APPROVE, true, true);
        worker.postComments(command);

        worker.postComments(command);

        assertEquals(1, sink.reviewPosts.size());
        assertEquals(2, sink.labelCalls.size());
    }

    /** GitLab and Bitbucket keep the port's default. */
    @Test
    void aForgeWithoutLabelsAnswersUnsupported() {
        CommentSink plain = new CommentSink() {
            @Override
            public ScmType type() {
                return ScmType.GITLAB;
            }

            @Override
            public CommentRef postSummary(RepoRef repo, long prId, String bodyMd) {
                throw new UnsupportedOperationException("TEST: not reached");
            }

            @Override
            public CommentRef postInline(RepoRef repo, long prId, String headCommit, InlineAnchor anchor, String bodyMd) {
                throw new UnsupportedOperationException("TEST: not reached");
            }

            @Override
            public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
                throw new UnsupportedOperationException("TEST: not reached");
            }

            @Override
            public Author getPullRequestAuthor(RepoRef repo, long prId) {
                throw new UnsupportedOperationException("TEST: not reached");
            }
        };

        assertEquals(CommentSink.LabelOutcome.UNSUPPORTED,
                plain.setVerdictLabel(REPO, 9, VerdictLabels.APPROVED, VerdictLabels.PREFIX));
    }
}

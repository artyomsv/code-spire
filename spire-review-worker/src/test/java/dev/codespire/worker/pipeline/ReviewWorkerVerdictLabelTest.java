package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.scm.github.GitHubApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.COMMIT;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.REPO;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.REVIEW_ID;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.labelled;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.labelledPostedAsComment;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.unlabelled;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.unlabelledCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict label after a posted review (verdict labels, V120): set from the decided verdict only when the
 * repository asked for it, never for a review that was not posted, never at the review's expense, and once per run.
 */
class ReviewWorkerVerdictLabelTest {

    private static final String APPROVED = VerdictLabel.APPROVED.name();
    private static final String CHANGES_REQUESTED = VerdictLabel.CHANGES_REQUESTED.name();

    private final VerdictLabelWorkerFixture fx = new VerdictLabelWorkerFixture();
    private final ReviewWorker worker = fx.worker();

    private CommentsPosted posted() {
        return assertInstanceOf(CommentsPosted.class, fx.emitted.getLast());
    }

    @Test
    void anApprovalLabelsApprovedAfterTheReviewIsPosted() {
        worker.postComments(labelled(ReviewVerdict.APPROVE));

        assertEquals(List.of(APPROVED), fx.sink.labelCalls);
        assertEquals(List.of("review", "label"), fx.sink.calls, "the label follows the posted review");
        assertNull(posted().labelFailure());
    }

    @Test
    void aRequestForChangesLabelsChangesRequested() {
        worker.postComments(labelled(ReviewVerdict.REQUEST_CHANGES));

        assertEquals(List.of(CHANGES_REQUESTED), fx.sink.labelCalls);
    }

    /** "Reviewer may approve" off posts a Comment; the label follows the decided verdict, not the posted event. */
    @Test
    void anApprovalPostedAsACommentStillLabelsApproved() {
        worker.postComments(labelledPostedAsComment(ReviewVerdict.APPROVE));

        assertEquals(ReviewVerdict.COMMENT, fx.sink.reviewPosts.getFirst().verdict());
        assertEquals(List.of(APPROVED), fx.sink.labelCalls);
    }

    @Test
    void theSettingOffSetsNoLabel() {
        worker.postComments(unlabelled(ReviewVerdict.APPROVE));

        assertTrue(fx.sink.labelCalls.isEmpty());
        assertEquals(1, fx.sink.reviewPosts.size(), "the review is posted as before");
    }

    /** A degraded review's assessment is COMMENT: it says nothing about who acts next. */
    @Test
    void aDegradedReviewSetsNoLabel() {
        worker.postComments(labelled(ReviewVerdict.COMMENT));

        assertTrue(fx.sink.labelCalls.isEmpty());
    }

    /** A command from before the field existed carries null: off. */
    @Test
    void aCommandWithoutTheSettingSetsNoLabel() {
        worker.postComments(unlabelledCommand().withVerdict(ReviewVerdict.APPROVE, ReviewVerdict.APPROVE, true));

        assertTrue(fx.sink.labelCalls.isEmpty());
    }

    @Test
    void aReviewThatFailedToPostSetsNoLabel() {
        fx.sink.reviewFailures.add(new GitHubApiException(500, "POST", "/TEST/reviews"));

        worker.postComments(labelled(ReviewVerdict.APPROVE));

        assertInstanceOf(ReviewFailed.class, fx.emitted.getLast());
        assertTrue(fx.sink.labelCalls.isEmpty());
    }

    /** A superseded run: the head moved, nothing is posted and nothing is labelled. */
    @Test
    void aSupersededRunSetsNoLabel() {
        fx.head = "TEST-newer-commit";

        worker.postComments(labelled(ReviewVerdict.APPROVE));

        assertTrue(fx.sink.reviewPosts.isEmpty());
        assertTrue(fx.sink.labelCalls.isEmpty());
    }

    @Test
    void aLabelFailureLeavesTheReviewPostedAndReportsTheReason() {
        fx.sink.labelFailure = new GitHubApiException(403, "POST", "/TEST/labels", "TEST-forbidden");

        worker.postComments(labelled(ReviewVerdict.APPROVE));

        CommentsPosted posted = posted();
        assertEquals("rv-100", posted.reviewRef(), "the review is reported as posted");
        assertTrue(posted.labelFailure().contains("HTTP 403"), posted.labelFailure());
        assertEquals(1, fx.sink.reviewPosts.size(), "never re-posted");
        assertTrue(fx.emitted.stream().noneMatch(ReviewFailed.class::isInstance), "the review does not fail");
    }

    /**
     * A response body that echoes a credential never reaches the published failure, the log or the stored receipt
     * (review of PR #458, M-1 and qa/L-1).
     */
    @Test
    void aForgeResponseBodyNeverReachesTheFailureTheLogOrTheReceipt() {
        fx.sink.labelFailure = new GitHubApiException(401, "POST", "/TEST/labels", "TEST-SECRET-token-echo");
        List<String> logged;
        try (LogCapture log = LogCapture.of(VerdictLabels.class)) {
            worker.postComments(labelled(ReviewVerdict.APPROVE));
            logged = log.lines();
        }
        String failure = posted().labelFailure();
        assertTrue(failure.contains("HTTP 401"), failure);
        assertFalse(failure.contains("TEST-SECRET"), failure);
        assertFalse(logged.isEmpty(), "the failure must be logged at all");
        assertTrue(logged.stream().noneMatch(line -> line.contains("TEST-SECRET")), String.join("\n", logged));
        String receipt = fx.idempotency.postedFor(REVIEW_ID, COMMIT).get(VerdictLabels.CLAIM_KEY);
        assertTrue(receipt.startsWith("failed:") && receipt.contains("HTTP 401"), receipt);
        assertFalse(receipt.contains("TEST-SECRET"), receipt);
    }

    /**
     * Review of PR #444, M-1: another reviewer requests changes after this run labelled approved, then this run's
     * PostComments is redelivered. The recovered review reports what the run already did and leaves the newer label.
     */
    @Test
    void aReplayDoesNotOverwriteALabelSetSinceTheFirstAttempt() {
        PostComments command = labelled(ReviewVerdict.APPROVE);
        worker.postComments(command);
        fx.sink.labels.clear();
        fx.sink.labels.add(CHANGES_REQUESTED);

        worker.postComments(command);

        assertEquals(Set.of(CHANGES_REQUESTED), fx.sink.labels, "the newer label stands");
        assertEquals(List.of(APPROVED), fx.sink.labelCalls, "one label call for the run");
        assertEquals(1, fx.sink.reviewPosts.size());
        assertNull(posted().labelFailure());
    }

    /** A failed attempt is reported again on a replay, and not retried. */
    @Test
    void aReplayOfAFailedAttemptReportsTheFailureWithoutRetrying() {
        PostComments command = labelled(ReviewVerdict.APPROVE);
        fx.sink.labelFailure = new GitHubApiException(403, "POST", "/TEST/labels", "TEST-forbidden");
        worker.postComments(command);
        String reported = posted().labelFailure();
        fx.sink.labelFailure = null;

        worker.postComments(command);

        assertEquals(reported, posted().labelFailure());
        assertEquals(1, fx.sink.labelCalls.size(), "no second label call");
        assertTrue(fx.sink.labels.isEmpty());
    }

    /** The label attempt's claim is never mistaken for a posted inline comment on recovery. */
    @Test
    void theLabelClaimNeverBecomesAnInlineComment() {
        PostComments command = labelled(ReviewVerdict.APPROVE);
        worker.postComments(command);

        worker.postComments(command);

        assertTrue(posted().inline().isEmpty(), posted().inline().toString());
        assertFalse(InlineRecovery.isInline(VerdictLabels.CLAIM_KEY));
        assertTrue(fx.idempotency.postedFor(REVIEW_ID, COMMIT).containsKey(VerdictLabels.CLAIM_KEY));
    }

    /** Review of PR #444, M-2: the claim store is down after the review was posted. Unknown state: no label call. */
    @Test
    void aLabelClaimThatCannotBeReadSkipsTheLabelAndStillReportsTheReview() {
        fx.idempotency.failLabelClaim = true;

        worker.postComments(labelled(ReviewVerdict.APPROVE));

        CommentsPosted posted = posted();
        assertEquals("rv-100", posted.reviewRef(), "the posted review is still reported");
        assertTrue(posted.labelFailure().startsWith("the label claim could not be read"), posted.labelFailure());
        assertTrue(fx.sink.labelCalls.isEmpty(), "no label call while the run's own attempt is unknown");
        assertTrue(fx.emitted.stream().noneMatch(ReviewFailed.class::isInstance), "the review does not fail");
    }

    /** The label is set but its outcome cannot be kept: the label stands, and the report says a replay may repeat it. */
    @Test
    void aLabelOutcomeThatCannotBeRecordedKeepsTheLabelAndStillReportsTheReview() {
        fx.idempotency.failLabelMark = true;

        worker.postComments(labelled(ReviewVerdict.APPROVE));

        CommentsPosted posted = posted();
        assertEquals("rv-100", posted.reviewRef());
        assertEquals(Set.of(APPROVED), fx.sink.labels, "the label call happened");
        assertTrue(posted.labelFailure().startsWith("the label outcome could not be recorded"), posted.labelFailure());
        assertTrue(fx.emitted.stream().noneMatch(ReviewFailed.class::isInstance), "the review does not fail");
    }

    /** GitLab and Bitbucket keep the port's default. */
    @Test
    void aForgeWithoutLabelsAnswersUnsupported() {
        CommentSink plain = new VerdictLabelWorkerFixture.PlainSink();

        assertEquals(CommentSink.LabelOutcome.UNSUPPORTED, plain.setVerdictLabel(REPO, 9, VerdictLabel.APPROVED));
    }
}

package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.codespire.orchestrator.pipeline.ResultSagaVerdictFixture.COMMIT;
import static dev.codespire.orchestrator.pipeline.ResultSagaVerdictFixture.REVIEW_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR 456, H-2: a review that did not see its whole diff never approves and is never labelled approved, on a first
 * review and on a re-review whose reconcile was clipped, with approval and labels otherwise on. A blocking one still
 * requests changes. Asserted on the PostComments the saga sends, which is what the worker posts.
 */
class ResultSagaPartialReviewTest {

    private final ResultSagaVerdictFixture fx = new ResultSagaVerdictFixture();

    @BeforeEach
    void approvalAndLabelsOn() {
        fx.setting = Optional.of(true);
        fx.identityKnown = true;
        fx.labels = Optional.of(true);
    }

    @Test
    void aCompleteReviewWithNoBlockersStillApproves() {
        PostComments post = postFor(firstReview(clean()));

        assertEquals(ReviewVerdict.APPROVE, post.verdict(), "the control: the hold applies to partial reviews only");
        assertEquals(Boolean.TRUE, post.verdictLabels());
    }

    @Test
    void aFirstReviewThatSkippedFilesPostsACommentAndNoApprovedLabel() {
        PostComments post = postFor(firstReview(clean().withTruncated(true)
                .withOmittedFiles(List.of("src/TEST-big.lock"))));

        assertEquals(ReviewVerdict.APPROVE, post.assessment(), "the findings alone would approve");
        assertEquals(ReviewVerdict.COMMENT, post.verdict(), "but the model did not see the whole diff");
        assertEquals(Boolean.FALSE, post.reviewerMayApprove());
        assertEquals(Boolean.FALSE, post.verdictLabels(), "no review: approved label");
        assertTrue(fx.timelineLines.stream().anyMatch(line -> line.contains(ReviewVerdictDecision.REASON_PARTIAL)),
                fx.timelineLines.toString());
    }

    /** An older worker sends {@code truncated} with no file list during a rolling upgrade. */
    @Test
    void aLegacyTruncatedResultPostsAComment() {
        PostComments post = postFor(firstReview(clean().withTruncated(true)));

        assertEquals(ReviewVerdict.COMMENT, post.verdict());
        assertEquals(Boolean.FALSE, post.verdictLabels());
    }

    @Test
    void aReReviewWhoseReconcileWasClippedPostsAComment() {
        ReviewResult clipped = clean().withTruncated(true).withOmittedFiles(List.of("src/TEST-Demo.java"));
        FindingVerdict unchanged = new FindingVerdict("t-1", "src/TEST-Demo.java", 5,
                FindingVerdict.Status.UNCHANGED, null);

        PostComments post = postFor(new ReviewGenerated(REVIEW_ID, 412L, COMMIT, clipped, List.of(unchanged),
                ModelUsage.of(null, 0, 0)));

        assertEquals(ReviewVerdict.COMMENT, post.verdict());
        assertEquals(Boolean.FALSE, post.verdictLabels());
    }

    @Test
    void aPartialReviewWithBlockersStillRequestsChanges() {
        fx.openBlockers = 1;

        PostComments post = postFor(firstReview(clean().withTruncated(true)
                .withOmittedFiles(List.of("src/TEST-big.lock"))));

        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.verdict(), "a partial review can only understate a block");
        assertEquals(Boolean.TRUE, post.verdictLabels(), "review: changes-requested still applies");
    }

    private PostComments postFor(ReviewGenerated generated) {
        fx.saga().on(generated);
        return assertInstanceOf(PostComments.class, fx.emitted.getLast());
    }

    private static ReviewGenerated firstReview(ReviewResult result) {
        return new ReviewGenerated(REVIEW_ID, 412L, COMMIT, result);
    }

    private static ReviewResult clean() {
        return new ReviewResult(List.of(), "TEST summary", ModelUsage.of(null, 0, 0));
    }
}

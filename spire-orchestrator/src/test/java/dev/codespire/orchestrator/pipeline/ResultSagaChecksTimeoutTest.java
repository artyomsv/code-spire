package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.review.ReviewVerdict;
import org.junit.jupiter.api.Test;

import static dev.codespire.orchestrator.pipeline.ResultSagaVerdictFixture.COMMIT;
import static dev.codespire.orchestrator.pipeline.ResultSagaVerdictFixture.generated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review waits for CI: a review started on the 60-minute check timeout never approves, and its posted summary says CI
 * had not finished (docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md, "Timeout").
 */
class ResultSagaChecksTimeoutTest {

    private final ResultSagaVerdictFixture fx = new ResultSagaVerdictFixture();

    @Test
    void aReviewStartedOnTheTimeoutIsPostedAsACommentAndSaysCiHadNotFinished() {
        fx.unfinishedChecksOn = COMMIT;
        fx.saga().on(generated());
        var post = assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast());
        assertEquals(ReviewVerdict.APPROVE, post.assessment());
        assertEquals(ReviewVerdict.COMMENT, post.verdict());
        assertEquals(Boolean.FALSE, post.reviewerMayApprove());
        assertTrue(post.findings().summary().startsWith(ReviewPostSettings.CHECKS_UNFINISHED_NOTICE),
                post.findings().summary());
        assertTrue(post.findings().summary().endsWith("s"), "the review's own summary follows the notice");
        assertTrue(fx.timelineLines.contains("ReviewVerdictDecided verdict:COMMENT · assessment:APPROVE · "
                + "no_open_blockers · checks_unfinished"), fx.timelineLines.toString());
    }

    /** The control: the hold names its own commit, so a review of another head approves as before. */
    @Test
    void aReviewWhoseChecksFinishedApprovesAsBefore() {
        fx.unfinishedChecksOn = "TEST-older-head";
        fx.saga().on(generated());
        var post = assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast());
        assertEquals(ReviewVerdict.APPROVE, post.verdict());
        assertEquals("s", post.findings().summary());
    }

    /** A timed-out review with blockers still requests nothing: it is posted as a Comment, its assessment kept. */
    @Test
    void aTimedOutReviewWithBlockersIsAlsoOnlyAComment() {
        fx.unfinishedChecksOn = COMMIT;
        fx.openBlockers = 1;
        fx.saga().on(generated());
        var post = assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.assessment());
        assertEquals(ReviewVerdict.COMMENT, post.verdict());
    }
}

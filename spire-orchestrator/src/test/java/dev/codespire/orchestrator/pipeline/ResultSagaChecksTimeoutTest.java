package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.review.ReviewVerdict;
import org.junit.jupiter.api.Test;

import java.util.Optional;

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
        ActionCommand.PostComments post = assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast());
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
        ActionCommand.PostComments post = assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast());
        assertEquals(ReviewVerdict.APPROVE, post.verdict());
        assertEquals("s", post.findings().summary());
    }

    /**
     * Round 1 timed out on this head; the repository then switched to "Immediately" and round 2 re-ran the same head.
     * Round 1's hold is not round 2's: it approves, with no notice, and labels approved.
     */
    @Test
    void anEarlierRoundsHoldDoesNotReachARerunOfTheSameHead() {
        fx.unfinishedChecksOn = COMMIT;
        fx.round = 2;
        fx.labels = Optional.of(true);
        fx.saga().on(generated());
        ActionCommand.PostComments post = assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast());
        assertEquals(ReviewVerdict.APPROVE, post.verdict());
        assertEquals("s", post.findings().summary());
        assertEquals(Boolean.TRUE, post.verdictLabels());
    }

    /** With Verdict labels on, an approval the timeout held back never reaches the label either. */
    @Test
    void aTimedOutApprovalSetsNoApprovedLabel() {
        fx.unfinishedChecksOn = COMMIT;
        fx.labels = Optional.of(true);
        fx.saga().on(generated());
        assertEquals(Boolean.FALSE, assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).verdictLabels());
    }

    /** Blocking findings still label changes-requested: an unfinished build does not weaken them. */
    @Test
    void aTimedOutReviewWithBlockersStillLabelsChangesRequested() {
        fx.unfinishedChecksOn = COMMIT;
        fx.labels = Optional.of(true);
        fx.openBlockers = 1;
        fx.saga().on(generated());
        assertEquals(Boolean.TRUE, assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).verdictLabels());
    }

    /** "Reviewer may approve" off keeps ADR-047's assessment-based label: only the CI hold suppresses approved. */
    @Test
    void aHeldApprovalWhoseChecksFinishedStillLabelsApproved() {
        fx.setting = Optional.of(false);
        fx.labels = Optional.of(true);
        fx.saga().on(generated());
        assertEquals(Boolean.TRUE, assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).verdictLabels());
    }

    /** A decision with no reason of its own gets the hold's reason alone, never "null · checks_unfinished". */
    @Test
    void theHoldOfADecisionWithoutAReasonIsTheHoldAlone() {
        ReviewVerdictDecision.Decision held = ReviewVerdictDecision.holdForUnfinishedChecks(
                new ReviewVerdictDecision.Decision(ReviewVerdict.APPROVE, ReviewVerdict.APPROVE, true, null));
        assertEquals(ReviewVerdictDecision.REASON_CHECKS_UNFINISHED, held.reason());
        assertEquals(ReviewVerdict.COMMENT, held.verdict());
    }

    /** A timed-out review with blockers still requests nothing: it is posted as a Comment, its assessment kept. */
    @Test
    void aTimedOutReviewWithBlockersIsAlsoOnlyAComment() {
        fx.unfinishedChecksOn = COMMIT;
        fx.openBlockers = 1;
        fx.saga().on(generated());
        ActionCommand.PostComments post = assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.assessment());
        assertEquals(ReviewVerdict.COMMENT, post.verdict());
    }
}

package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.review.ReviewVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.codespire.orchestrator.pipeline.ResultSagaVerdictFixture.COMMIT;
import static dev.codespire.orchestrator.pipeline.ResultSagaVerdictFixture.REVIEW_ID;
import static dev.codespire.orchestrator.pipeline.ResultSagaVerdictFixture.generated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saga carries the verdict decision on the command, and records what was posted (ADR-047 §4).
 *
 * <p>The saga and its hand-written fakes live in {@link ResultSagaVerdictFixture}: no CDI container, no
 * mocking framework, and every method the ReviewGenerated path reaches is overridden on purpose.
 */
class ResultSagaVerdictTest {

    private final ResultSagaVerdictFixture fx = new ResultSagaVerdictFixture();

    @Test
    void theCommandCarriesTheRunToken() {
        // The worker marks this run's reviews with it, so a re-run posts beside the earlier run's
        // review instead of reusing it (spire-test PR 55, 2026-10-16).
        fx.saga().on(generated());
        assertEquals("TEST-run-7", assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).runToken());
    }

    @Test
    void theCommandCarriesTheDecision() {
        fx.openBlockers = 1;
        fx.saga().on(generated());
        var post = assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.assessment());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.verdict());
        assertEquals(Boolean.TRUE, post.reviewerMayApprove());
        assertTrue(fx.timelineLines.contains("ReviewVerdictDecided verdict:REQUEST_CHANGES · assessment:REQUEST_CHANGES · open_blockers=1"),
                fx.timelineLines.toString());
    }

    @Test
    void theSettingOffPostsACommentAndSaysSo() {
        fx.setting = Optional.of(false);
        fx.saga().on(generated());
        var post = assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast());
        assertEquals(ReviewVerdict.APPROVE, post.assessment());
        assertEquals(ReviewVerdict.COMMENT, post.verdict());
        assertEquals(Boolean.FALSE, post.reviewerMayApprove());
    }

    @Test
    void aReviewerWithoutIdentityNeverGetsAnApprove() {
        fx.identityKnown = false;
        fx.saga().on(generated());
        assertEquals(ReviewVerdict.COMMENT, assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).verdict());
    }

    @Test
    void commentsPostedWithAVerdictRecordsIt() {
        fx.saga().on(new CommentsPosted(REVIEW_ID, 412L, COMMIT, "review:501", List.of(), List.of())
                .withPostedReview(ReviewVerdict.APPROVE, "501", "https://forge.example.test/TEST/501"));
        assertEquals(List.of("APPROVE:501:" + COMMIT + ":https://forge.example.test/TEST/501"), fx.recordedVerdicts);
    }

    @Test
    void commentsPostedWithoutAVerdictRecordsNothing() {
        fx.saga().on(new CommentsPosted(REVIEW_ID, 412L, COMMIT, "sum-1", List.of(), List.of()));
        assertTrue(fx.recordedVerdicts.isEmpty());
    }

    /** V120: the repository's "Verdict labels" rides on the command; the worker labels only when it is true. */
    @Test
    void theCommandCarriesVerdictLabelsWhenTheRepositoryTurnedThemOn() {
        fx.labels = Optional.of(true);
        fx.saga().on(generated());
        assertEquals(Boolean.TRUE, assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).verdictLabels());
    }

    @Test
    void verdictLabelsAreOffByDefaultAndForAnUnknownRepository() {
        fx.saga().on(generated());
        assertEquals(Boolean.FALSE, assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).verdictLabels());
        fx.labels = Optional.empty();
        fx.saga().on(generated());
        assertEquals(Boolean.FALSE, assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).verdictLabels());
    }

    /** The failure raises REVIEW_LABEL_FAILED; the next report without one clears it, so both are written. */
    @Test
    void commentsPostedRecordsTheLabelFailureAndItsClear() {
        ResultSaga saga = fx.saga();
        saga.on(new CommentsPosted(REVIEW_ID, 412L, COMMIT, "review:501", List.of(), List.of())
                .withPostedReview(ReviewVerdict.APPROVE, "501", "https://forge.example.test/TEST/501")
                .withLabelFailure("TEST-forbidden"));
        saga.on(new CommentsPosted(REVIEW_ID, 412L, COMMIT, "review:501", List.of(), List.of())
                .withPostedReview(ReviewVerdict.APPROVE, "501", "https://forge.example.test/TEST/501"));
        assertEquals(List.of(COMMIT + ":TEST-forbidden", COMMIT + ":null"), fx.recordedLabelFailures);
    }
}

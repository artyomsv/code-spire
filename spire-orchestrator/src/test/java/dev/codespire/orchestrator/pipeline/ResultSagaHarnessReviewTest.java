package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.review.Severity;
import dev.codespire.orchestrator.review.run.HarnessReviewStart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.COMMIT;
import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.PR;
import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.REVIEW_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A first review on a harness repository (harness reviews, step 5): at {@code ContextAssembled} the saga asks
 * {@link HarnessReviewStart}, and a started run sends no {@code GenerateReview}; a refused one ends the review
 * {@code refused} with the reason the attention feed shows. The run's outcome comes back through
 * {@link ResultSaga#acceptHarnessOutcome}, which takes the existing outcome path and never charges again.
 *
 * <p>A plain unit test with hand-written fakes, as {@code ResultSagaVerdictTest} has: {@link HarnessSagaFakes} and
 * {@link HarnessSagaProjection} override every method these paths reach (CLAUDE.md, saga test fakes).
 */
class ResultSagaHarnessReviewTest {

    private static final ContextAssembled CONTEXT =
            new ContextAssembled(REVIEW_ID, PR, COMMIT, "TEST-context-ref", Set.of(), Set.of());

    private HarnessReviewStart.Decision decision = new HarnessReviewStart.NotHarness();

    private final HarnessSagaProjection projection = new HarnessSagaProjection();
    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<RecordCommand> recorded = new ArrayList<>();
    private final List<String> timelineLines = new ArrayList<>();

    @Test
    void aStartedHarnessReviewSendsNoGenerateReview() {
        decision = new HarnessReviewStart.Started("TEST-run-1", "TEST-harness");

        saga().on(CONTEXT);

        assertTrue(emitted.stream().noneMatch(ActionCommand.GenerateReview.class::isInstance),
                "a harness review is a run, never a worker call: " + emitted);
        assertTrue(timelineLines.contains("harness-review:TEST-run-1"), timelineLines.toString());
        assertEquals("Reviewing on TEST-harness, run TEST-run-1", projection.notes.getLast());
    }

    @Test
    void aRefusedHarnessReviewIsRefusedWithAttentionReason() {
        decision = new HarnessReviewStart.Refused("codex", "subscription_unavailable");

        saga().on(CONTEXT);

        assertTrue(emitted.stream().noneMatch(ActionCommand.GenerateReview.class::isInstance),
                "a refused harness review never falls back to the API: " + emitted);
        assertEquals("refused", projection.statuses.getLast());
        assertEquals("codex: subscription_unavailable", projection.harnessRefusals.getLast());
        assertEquals("Harness review refused: subscription_unavailable", projection.notes.getLast());
        RecordCommand.RecordFailure failure = assertInstanceOf(RecordCommand.RecordFailure.class, recorded.getLast());
        assertEquals("harness-review", failure.phase());
        assertFalse(failure.retryable(), "a refusal leaves REVIEWING terminally");
    }

    /** The control: a repository on the API reviews exactly as today. */
    @Test
    void aNonHarnessReviewSendsGenerateReviewAsToday() {
        saga().on(CONTEXT);

        assertInstanceOf(ActionCommand.GenerateReview.class, emitted.getLast());
    }

    /** A new round clears the last round's refusal, so a re-run that is no longer refused drops its attention row. */
    @Test
    void aNewRoundClearsTheLastHarnessRefusal() {
        saga().on(CONTEXT);

        assertEquals(1, projection.refusalClears);
    }

    /**
     * Charged once: step 4's ReviewRunCharges already charged the run under the review's call slot. The same event
     * through the worker path does charge, which proves the fake would have seen a charge.
     */
    @Test
    void aHarnessOutcomeIsNotChargedAgain() {
        ResultSaga saga = saga();

        saga.acceptHarnessOutcome(generated(COMMIT));
        assertTrue(projection.charges.isEmpty(), "a harness outcome is never charged by the saga: " + projection.charges);

        saga.on(generated(COMMIT));
        assertFalse(projection.charges.isEmpty(), "the control: a worker's ReviewGenerated is charged");
    }

    @Test
    void aHarnessOutcomeForAnOldCommitIsDropped() {
        saga().acceptHarnessOutcome(generated("fedcba9876543210fedcba9876543210fedcba98"));

        assertTrue(emitted.isEmpty(), "a superseded commit's answer is never posted: " + emitted);
    }

    @Test
    void aHarnessOutcomeEmitsPostComments() {
        projection.openBlockers = 1;

        saga().acceptHarnessOutcome(generated(COMMIT));

        ActionCommand.PostComments post = assertInstanceOf(ActionCommand.PostComments.class, emitted.getLast());
        assertEquals(1, post.findings().findings().size());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.verdict(), "the verdict is decided here, from the findings");
    }

    @Test
    void anOutcomeOfAnotherKindIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> saga().acceptHarnessOutcome(CONTEXT));
    }

    private static ReviewGenerated generated(String commit) {
        Finding finding = new Finding("src/TEST.java", new LineRange(3, 3), Severity.BLOCKER, null,
                "TEST-message", null);
        return new ReviewGenerated(REVIEW_ID, PR, commit,
                new ReviewResult(List.of(finding), "TEST-summary", ModelUsage.of("TEST-model", 10, 5)));
    }

    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.harnessStart = new HarnessReviewStart() {
            @Override
            public Decision start(ContextAssembled event) {
                return decision;
            }
        };
        saga.findings = HarnessSagaFakes.SILENT_FINDINGS;
        saga.preferenceFilter = HarnessSagaFakes.NO_PREFERENCES;
        saga.lifecycle = HarnessSagaFakes.lifecycle(recorded);
        saga.commands = HarnessSagaFakes.commands(emitted);
        saga.timeline = HarnessSagaFakes.timeline(timelineLines);
        saga.threads = HarnessSagaFakes.silentThreads();
        saga.projection = projection;
        HarnessSagaFakes.wireRepository(saga);
        HarnessSagaFakes.wireCalls(saga);
        return saga;
    }
}

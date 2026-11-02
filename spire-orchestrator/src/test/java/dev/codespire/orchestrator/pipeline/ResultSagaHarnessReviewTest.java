package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewGenerated;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewPrepared;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.review.Severity;
import dev.codespire.orchestrator.review.run.HarnessReviewLaunch;
import dev.codespire.orchestrator.review.run.HarnessReviewStart;
import dev.codespire.orchestrator.review.run.RealHarnessResults;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private static final String OLD_COMMIT = "fedcba9876543210fedcba9876543210fedcba98";

    private HarnessReviewStart.Decision decision = new HarnessReviewStart.NotHarness();

    /** What the launch answers a prepared review (step 6). */
    private HarnessReviewStart.Decision launchDecision = new HarnessReviewStart.Started("TEST-run-1", "TEST-harness");

    private final List<HarnessReviewPrepared> launched = new ArrayList<>();

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

    /** Step 6: a re-review on a harness repository is prepared for the harness, never sent to the API. */
    @Test
    void aHarnessRepositorysReReviewSendsNoGenerateReview() {
        projection.priorRun = Optional.of(prior());
        decision = new HarnessReviewStart.Preparing("TEST-harness");

        saga().on(CONTEXT);

        assertTrue(emitted.stream().noneMatch(ActionCommand.GenerateReview.class::isInstance), emitted.toString());
        assertTrue(timelineLines.contains("harness-prepare"), timelineLines.toString());
    }

    @Test
    void aPreparingReviewSetsItsNote() {
        decision = new HarnessReviewStart.Preparing("TEST-harness");

        saga().on(CONTEXT);

        assertEquals("Preparing the review on TEST-harness", projection.notes.getLast());
        assertTrue(projection.statuses.isEmpty(), "preparing is not a refusal: " + projection.statuses);
    }

    /** A supersede while the worker prepared: the old commit's inputs are never dispatched. */
    @Test
    void aPreparedReviewOfAnOldCommitIsNotDispatched() {
        saga().on(prepared(OLD_COMMIT));

        assertTrue(launched.isEmpty(), "a superseded commit's run is never dispatched: " + launched);
    }

    /** The control for the test above: the current commit's prepared review is launched and noted as started. */
    @Test
    void aPreparedReviewOfTheCurrentCommitIsLaunched() {
        saga().on(prepared(COMMIT));

        assertEquals(1, launched.size(), launched.toString());
        assertTrue(timelineLines.contains("harness-review:TEST-run-1"), timelineLines.toString());
        assertEquals("Reviewing on TEST-harness, run TEST-run-1", projection.notes.getLast());
    }

    @Test
    void aRefusedLaunchIsRefusedWithAttentionReason() {
        launchDecision = new HarnessReviewStart.Refused("codex", "subscription_unavailable");

        saga().on(prepared(COMMIT));

        assertEquals("refused", projection.statuses.getLast());
        assertEquals("codex: subscription_unavailable", projection.harnessRefusals.getLast());
        RecordCommand.RecordFailure failure = assertInstanceOf(RecordCommand.RecordFailure.class, recorded.getLast());
        assertEquals("harness-review", failure.phase());
        assertFalse(failure.retryable());
        assertTrue(emitted.stream().noneMatch(ActionCommand.GenerateReview.class::isInstance), emitted.toString());
    }

    /** An ended round's prepared review is dropped on the timeline: nothing is refused or recorded. */
    @Test
    void aDroppedLaunchRefusesNothing() {
        launchDecision = new HarnessReviewStart.Dropped("round 1 ended");

        saga().on(prepared(COMMIT));

        assertTrue(timelineLines.contains("dropped:HarnessReviewPrepared"), timelineLines.toString());
        assertTrue(projection.statuses.isEmpty(), projection.statuses.toString());
        assertTrue(recorded.isEmpty(), recorded.toString());
    }

    /**
     * The worker's finished answer is applied with no charge: ReviewRunCharges charged the run. The same outcome as a
     * worker's ReviewGenerated is charged, which proves the fake would have seen one.
     */
    @Test
    void aHarnessReviewGeneratedIsNeverCharged() {
        ResultSaga saga = saga();

        saga.on(harnessGenerated(COMMIT, List.of()));
        assertTrue(projection.charges.isEmpty(), "a harness answer is never charged by the saga: " + projection.charges);
        assertInstanceOf(ActionCommand.PostComments.class, emitted.getLast(), "and it is applied: " + emitted);

        saga.on(generated(COMMIT));
        assertFalse(projection.charges.isEmpty(), "the control: a worker's ReviewGenerated is charged");
    }

    @Test
    void aHarnessReReviewPostsItsVerdicts() {
        projection.priorRun = Optional.of(prior());
        List<FindingVerdict> verdicts = List.of(new FindingVerdict("TEST-thread", "src/TEST.java", 3,
                FindingVerdict.Status.RESOLVED, "TEST-note"));

        saga().on(harnessGenerated(COMMIT, verdicts));

        assertEquals(List.of(verdicts), projection.reconciliations);
        ActionCommand.PostComments post = assertInstanceOf(ActionCommand.PostComments.class, emitted.getLast());
        assertEquals(verdicts, post.verdicts());
        assertEquals("TEST-summary-ref", post.priorSummaryRef());
    }

    @Test
    void aHarnessReviewGeneratedForAnOldCommitIsDropped() {
        saga().on(harnessGenerated(OLD_COMMIT, List.of()));

        assertTrue(emitted.isEmpty(), "a superseded commit's answer is never posted: " + emitted);
    }

    private static HarnessReviewPrepared prepared(String commit) {
        return new HarnessReviewPrepared(REVIEW_ID, PR, commit, 1, ReviewRunBinding.Kind.REVIEW,
                HarnessReviewInputs.NONE);
    }

    private static HarnessReviewGenerated harnessGenerated(String commit, List<FindingVerdict> verdicts) {
        return new HarnessReviewGenerated(REVIEW_ID, PR, commit, 1, generated(commit).result(), verdicts);
    }

    private static PriorRun prior() {
        return new PriorRun("TEST-prior-commit", "TEST-summary-ref",
                List.of(new PriorFinding("src/TEST.java", 3, Severity.MAJOR, "TEST-message", "TEST-thread")));
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
        saga.harnessLaunch = launch();
        // The real one: it reads round 1, as wireCalls does, and hands the finished answer to this saga.
        saga.harnessResults = RealHarnessResults.results(saga, saga.commands);
        return saga;
    }

    /** The real launch funds and dispatches a run; the saga only routes its decision. */
    private HarnessReviewLaunch launch() {
        return new HarnessReviewLaunch() {
            @Override
            public HarnessReviewStart.Decision launch(HarnessReviewPrepared prepared) {
                launched.add(prepared);
                return launchDecision;
            }
        };
    }
}

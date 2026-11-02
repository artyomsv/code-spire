package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
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
import dev.codespire.contract.review.Severity;
import dev.codespire.orchestrator.review.run.HarnessReviewLaunch;
import dev.codespire.orchestrator.review.run.HarnessReviewStart;
import dev.codespire.orchestrator.review.run.RealHarnessResults;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.COMMIT;
import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.PR;
import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.REVIEW_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A harness review after the worker prepared it (harness reviews, step 6): the saga launches the prepared run on its
 * per-review ordered consumer, never for a superseded commit, and routes the launch's decision; the worker's finished
 * answer, {@code HarnessReviewGenerated}, is applied with no charge, verdicts included. Split from
 * {@code ResultSagaHarnessReviewTest}, which keeps the start.
 *
 * <p>A plain unit test with hand-written fakes: {@link HarnessSagaFakes} and {@link HarnessSagaProjection} override
 * every method these paths reach (CLAUDE.md, saga test fakes); the launch is a fake that records what it was given.
 */
class ResultSagaHarnessRunTest {

    private static final String OLD_COMMIT = "fedcba9876543210fedcba9876543210fedcba98";

    /** What the launch answers a prepared review. */
    private HarnessReviewStart.Decision launchDecision = new HarnessReviewStart.Started("TEST-run-1", "TEST-harness");

    private final List<HarnessReviewPrepared> launched = new ArrayList<>();

    private final HarnessSagaProjection projection = new HarnessSagaProjection();
    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<RecordCommand> recorded = new ArrayList<>();
    private final List<String> timelineLines = new ArrayList<>();

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

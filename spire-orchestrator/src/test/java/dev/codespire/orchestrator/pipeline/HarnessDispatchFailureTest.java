package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewGenerated;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewPrepared;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.review.Severity;
import dev.codespire.orchestrator.factory.AmbiguousReviewLaunch;
import dev.codespire.orchestrator.review.run.RealHarnessLaunch;
import dev.codespire.orchestrator.review.run.RealHarnessResults;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.COMMIT;
import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.PR;
import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.REVIEW_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A harness review whose dispatch throws after its command may have reached the broker stays open, and the paid run's
 * late answer is still posted (review of PR #368, code-quality/M-2). The send's ack times out, so {@code RunLaunch}
 * records the launch as uncertain, and that status write fails: the exception leaves {@code ReviewRunDispatch},
 * {@code HarnessReviewLaunch} and {@code ResultSaga}, so the {@code HarnessReviewPrepared} message dead-letters instead
 * of refusing the review. Step 6 dispatches from the prepared inputs, so the test starts there.
 *
 * <p>Real: {@code RunLaunch}, {@code ReviewRunDispatch}, {@code HarnessReviewLaunch}, {@code ResultSaga},
 * {@code HarnessReviewResults} and the {@code ReviewLifecycle} decide table, held in memory by {@link InMemoryLifecycle}.
 * Fakes only where a database or a broker would be: the emitter, the run row, the dispatch's and launch's reads, the
 * round lock, and the saga's read model ({@link HarnessSagaProjection}, {@link HarnessSagaFakes}). The worker's finish
 * is played by hand: the test hands the saga the {@code HarnessReviewGenerated} the worker would emit.
 *
 * <p>Restoring the broad catch around the dispatch in {@code HarnessReviewLaunch} fails both tests: the launch returns
 * {@code Refused(harness_review_start_failed)}, so {@code ResultSaga.on} no longer throws, and {@code refuseHarness}
 * records a terminal {@code RecordFailure} that the real decide table turns into FAILED, after which
 * {@code ifCurrentRun} drops the answer and no {@code PostComments} is sent.
 */
class HarnessDispatchFailureTest {

    private static final HarnessReviewPrepared PREPARED = new HarnessReviewPrepared(REVIEW_ID, PR, COMMIT, 1,
            ReviewRunBinding.Kind.REVIEW, new HarnessReviewInputs(List.of(
                    new HarnessReviewInputs.InputFile(HarnessReviewInputs.CONTEXT, "TEST-CONTEXT"))));

    private static final String ONE_FINDING = """
            {"summary": "TEST-summary", "findings": [{"path": "src/TEST.java", "line": 12, "endLine": 12,
             "severity": "MAJOR", "category": "CORRECTNESS", "message": "TEST-message", "suggestion": null}]}
            """;

    private final AmbiguousReviewLaunch launch = new AmbiguousReviewLaunch();
    private final InMemoryLifecycle lifecycle = new InMemoryLifecycle(REVIEW_ID, COMMIT);
    private final HarnessSagaProjection projection = new HarnessSagaProjection();
    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<String> timelineLines = new ArrayList<>();

    @Test
    void aDispatchThatThrowsAfterTheSendFailsThePreparedMessageAndRefusesNothing() {
        ResultSaga saga = saga();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> saga.on(PREPARED),
                "the prepared message must fail, so the review is not refused");

        RunCommand.ExecuteReviewRun sent = onlySent();
        assertEquals(List.of(sent.runId()), launch.uncertainWritesTried(), "the launch was ambiguous");
        assertEquals(PREPARED.inputs(), sent.inputs(), "the run carries the prepared inputs");
        assertTrue(thrown.getMessage().contains(sent.runId()), thrown.getMessage());
        assertTrue(projection.statuses.isEmpty(), "never refused: " + projection.statuses);
        assertTrue(projection.harnessRefusals.isEmpty(), "no attention row: " + projection.harnessRefusals);
        assertTrue(lifecycle.currentState(REVIEW_ID).isReviewing(), "the review stays open for the run's answer");
    }

    @Test
    void theLateAnswerOfARunWhoseDispatchThrewIsPosted() {
        ResultSaga saga = saga();
        launchIgnoringTheFailedMessage(saga);
        RunCommand.ExecuteReviewRun sent = onlySent();

        saga.harnessResults.accept(answer(sent), sent.review());
        assertInstanceOf(ActionCommand.FinishHarnessReview.class, emitted.getLast(),
                "the answer goes to the worker to finish: " + emitted);
        saga.on(finished());

        assertTrue(lifecycle.recorded().stream().noneMatch(RecordCommand.RecordFailure.class::isInstance),
                "no terminal failure was recorded: " + lifecycle.recorded());
        assertTrue(emitted.stream().anyMatch(ActionCommand.PostComments.class::isInstance),
                "the paid run's answer is posted: " + emitted);
        assertTrue(emitted.stream().noneMatch(ActionCommand.GenerateReview.class::isInstance),
                "and never falls back to the API: " + emitted);
    }

    /** The first test asserts the failure; this one only needs what it leaves behind. */
    private void launchIgnoringTheFailedMessage(ResultSaga saga) {
        try {
            saga.on(PREPARED);
        } catch (IllegalStateException expected) {
            timelineLines.add("TEST-prepared-message-failed");
        }
    }

    private RunCommand.ExecuteReviewRun onlySent() {
        assertEquals(1, launch.sent().size(), "one command reached the emitter: " + launch.sent());
        return assertInstanceOf(RunCommand.ExecuteReviewRun.class, launch.sent().getFirst());
    }

    private static RunReviewArtifact answer(RunCommand.ExecuteReviewRun sent) {
        return new RunReviewArtifact(sent.runId(), sent.review(), ONE_FINDING, false,
                Map.of("INPUT", 1200L, "OUTPUT", 340L), 4, List.of());
    }

    /** What the worker emits for ONE_FINDING once it has finished the answer. */
    private static HarnessReviewGenerated finished() {
        Finding finding = new Finding("src/TEST.java", new LineRange(12, 12), Severity.MAJOR, null, "TEST-message",
                null);
        return new HarnessReviewGenerated(REVIEW_ID, PR, COMMIT, 1,
                new ReviewResult(List.of(finding), "TEST-summary", ModelUsage.of("TEST-model", 1200, 340)), List.of());
    }

    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.harnessLaunch = RealHarnessLaunch.launch(projection, launch.launch(), COMMIT);
        saga.lifecycle = lifecycle;
        saga.findings = HarnessSagaFakes.SILENT_FINDINGS;
        saga.preferenceFilter = HarnessSagaFakes.NO_PREFERENCES;
        saga.commands = HarnessSagaFakes.commands(emitted);
        saga.timeline = HarnessSagaFakes.timeline(timelineLines);
        saga.threads = HarnessSagaFakes.silentThreads();
        saga.projection = projection;
        HarnessSagaFakes.wireRepository(saga);
        HarnessSagaFakes.wireCalls(saga);
        saga.harnessResults = RealHarnessResults.results(saga, saga.commands);
        return saga;
    }
}

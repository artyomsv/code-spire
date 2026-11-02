package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.orchestrator.factory.AmbiguousReviewLaunch;
import dev.codespire.orchestrator.review.run.RealHarnessResults;
import dev.codespire.orchestrator.review.run.RealHarnessStart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * {@code HarnessReviewStart} and {@code ResultSaga}, so the start message dead-letters instead of refusing the review.
 *
 * <p>Real: {@code RunLaunch}, {@code ReviewRunDispatch}, {@code HarnessReviewStart}, {@code ResultSaga},
 * {@code HarnessReviewResults} and the {@code ReviewLifecycle} decide table, held in memory by {@link InMemoryLifecycle}.
 * Fakes only where a database or a broker would be: the emitter, the run row, the dispatch's and start's reads, the
 * round lock, and the saga's read model ({@link HarnessSagaProjection}, {@link HarnessSagaFakes}).
 *
 * <p>Restoring the broad catch around the dispatch in {@code HarnessReviewStart} fails both tests: the start returns
 * {@code Refused(harness_review_start_failed)}, so {@code ResultSaga.on} no longer throws, and {@code refuseHarness}
 * records a terminal {@code RecordFailure} that the real decide table turns into FAILED, after which
 * {@code ifCurrentRun} drops the answer and no {@code PostComments} is sent.
 */
class HarnessDispatchFailureTest {

    private static final ContextAssembled CONTEXT =
            new ContextAssembled(REVIEW_ID, PR, COMMIT, "TEST-context-ref", Set.of(), Set.of());

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
    void aDispatchThatThrowsAfterTheSendFailsTheStartMessageAndRefusesNothing() {
        ResultSaga saga = saga();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> saga.on(CONTEXT),
                "the start message must fail, so the review is not refused");

        RunCommand.ExecuteReviewRun sent = onlySent();
        assertEquals(List.of(sent.runId()), launch.uncertainWritesTried(), "the launch was ambiguous");
        assertTrue(thrown.getMessage().contains(sent.runId()), thrown.getMessage());
        assertTrue(projection.statuses.isEmpty(), "never refused: " + projection.statuses);
        assertTrue(projection.harnessRefusals.isEmpty(), "no attention row: " + projection.harnessRefusals);
        assertTrue(lifecycle.currentState(REVIEW_ID).isReviewing(), "the review stays open for the run's answer");
    }

    @Test
    void theLateAnswerOfARunWhoseDispatchThrewIsPosted() {
        ResultSaga saga = saga();
        startIgnoringTheFailedMessage(saga);
        RunCommand.ExecuteReviewRun sent = onlySent();

        RealHarnessResults.results(saga).accept(answer(sent), sent.review());

        assertTrue(lifecycle.recorded().stream().noneMatch(RecordCommand.RecordFailure.class::isInstance),
                "no terminal failure was recorded: " + lifecycle.recorded());
        assertTrue(emitted.stream().anyMatch(ActionCommand.PostComments.class::isInstance),
                "the paid run's answer is posted: " + emitted);
        assertTrue(emitted.stream().noneMatch(ActionCommand.GenerateReview.class::isInstance),
                "and never falls back to the API: " + emitted);
    }

    /** The first test asserts the failure; this one only needs what it leaves behind. */
    private void startIgnoringTheFailedMessage(ResultSaga saga) {
        try {
            saga.on(CONTEXT);
        } catch (IllegalStateException expected) {
            timelineLines.add("TEST-start-message-failed");
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

    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.harnessStart = RealHarnessStart.start(projection, launch.launch(), COMMIT);
        saga.lifecycle = lifecycle;
        saga.findings = HarnessSagaFakes.SILENT_FINDINGS;
        saga.preferenceFilter = HarnessSagaFakes.NO_PREFERENCES;
        saga.commands = HarnessSagaFakes.commands(emitted);
        saga.timeline = HarnessSagaFakes.timeline(timelineLines);
        saga.threads = HarnessSagaFakes.silentThreads();
        saga.projection = projection;
        HarnessSagaFakes.wireRepository(saga);
        HarnessSagaFakes.wireCalls(saga);
        return saga;
    }
}

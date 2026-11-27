package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.event.IntegrationEvent.ChecksFailedLabelled;
import dev.codespire.contract.event.IntegrationEvent.ChecksFailedLabelled.Outcome;
import dev.codespire.orchestrator.attention.LabelFailures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A skipped run's ci-failed label report (review waits for CI, Verdict labels on): a history line for each outcome,
 * and the label failure row raised or cleared for that run only; a stale report changes no row.
 */
class ChecksFailedLabelOutcomesTest extends ChecksWaitFakes {

    private static final String REVIEW_ID = "review::TEST-owner/TEST-repo#7";
    private static final String HEAD = "a".repeat(40);

    /** Each label failure written, as {@code round:reason}. */
    private final List<String> written = new ArrayList<>();

    private ChecksFailedLabelOutcomes outcomes() {
        ChecksFailedLabelOutcomes outcomes = new ChecksFailedLabelOutcomes();
        outcomes.projection = recordingProjection();
        // The real one opens a database; the report reaches only the run-guarded write.
        outcomes.labelFailures = new LabelFailures() {
            @Override
            public void recordForRun(Run run, String reason) {
                assertEquals(REVIEW_ID, run.reviewId());
                assertEquals(HEAD, run.commit());
                written.add(run.round() + ":" + reason);
            }

            @Override
            public void record(String reviewId, String commit, String reason) {
                throw new AssertionError("a skipped run's report is guarded by its round");
            }
        };
        return outcomes;
    }

    private static ChecksFailedLabelled report(Outcome outcome, String reason) {
        return new ChecksFailedLabelled(REVIEW_ID, HEAD, 2, outcome, reason);
    }

    @Test
    void aSetLabelClearsTheRunsLabelFailure() {
        outcomes().record(report(Outcome.LABELLED, null));
        assertEquals(List.of("2:null"), written);
        assertEquals(List.of("ChecksFailedLabelled"), history);
    }

    @Test
    void aFailedLabelRaisesTheRunsLabelFailure() {
        outcomes().record(report(Outcome.FAILED, "TEST-HTTP-403"));
        assertEquals(List.of("2:TEST-HTTP-403"), written);
        assertEquals(List.of("ChecksFailedLabelFailed"), history);
    }

    /** The head moved: nothing was written on the forge, so no row is raised or cleared; the history says why. */
    @Test
    void aStaleReportOnlyLeavesAHistoryLine() {
        outcomes().record(report(Outcome.STALE, null));
        assertTrue(written.isEmpty(), written.toString());
        assertEquals(List.of("ChecksFailedLabelStale"), history);
    }
}

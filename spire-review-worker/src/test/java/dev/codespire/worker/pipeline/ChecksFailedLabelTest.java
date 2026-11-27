package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.LabelChecksFailed;
import dev.codespire.contract.event.IntegrationEvent.ChecksFailedLabelled;
import dev.codespire.contract.event.IntegrationEvent.ChecksFailedLabelled.Outcome;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.scm.github.GitHubApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.COMMIT;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.REPO;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.REVIEW_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A skipped run's {@code review: ci-failed} (review waits for CI, Verdict labels on): set only while the pull
 * request's head is still the skipped commit, at most once per run, nothing posted, and every outcome reported.
 */
class ChecksFailedLabelTest {

    private static final int ROUND = 3;
    private static final LabelChecksFailed COMMAND = new LabelChecksFailed(REVIEW_ID, REPO, 9L, COMMIT, ROUND,
            "TEST-cred");

    private final VerdictLabelWorkerFixture fx = new VerdictLabelWorkerFixture();

    private ChecksFailedLabelled reported() {
        return assertInstanceOf(ChecksFailedLabelled.class, fx.emitted.getLast());
    }

    @Test
    void anUnchangedHeadGetsTheLabelAndLosesTheOtherReviewLabels() {
        fx.sink.labels.addAll(List.of("review: requested", "review: approved", "TEST-unrelated"));
        fx.checksFailedLabel().apply(COMMAND);
        assertEquals(List.of(VerdictLabel.CI_FAILED.name()), fx.sink.labelCalls);
        assertEquals(Set.of("TEST-unrelated", "review: ci-failed"), fx.sink.labels);
        assertEquals(List.of("label"), fx.sink.calls, "no comment and no review: the label is the only mark");
        assertEquals(new ChecksFailedLabelled(REVIEW_ID, COMMIT, ROUND, Outcome.LABELLED, null), reported());
    }

    /** A push after the skip: the skipped commit is no longer the head, so nothing is written. */
    @Test
    void aMovedHeadWritesNothingAndReportsStale() {
        fx.sink.labels.add("review: requested");
        fx.head = "TEST-newer-commit";
        fx.checksFailedLabel().apply(COMMAND);
        assertTrue(fx.sink.labelCalls.isEmpty(), fx.sink.labelCalls.toString());
        assertEquals(Set.of("review: requested"), fx.sink.labels);
        assertEquals(Outcome.STALE, reported().outcome());
    }

    /** A redelivered command of the same run reports the recorded outcome and never calls the forge again. */
    @Test
    void aSecondCommandForTheSameRunWritesNothing() {
        fx.checksFailedLabel().apply(COMMAND);
        fx.sink.labels.clear();
        fx.sink.labels.add("review: approved");
        fx.checksFailedLabel().apply(COMMAND);
        assertEquals(1, fx.sink.labelCalls.size(), fx.sink.labelCalls.toString());
        assertEquals(Set.of("review: approved"), fx.sink.labels, "a label set since stays");
        assertEquals(List.of(Outcome.LABELLED, Outcome.LABELLED),
                fx.emitted.stream().map(e -> ((ChecksFailedLabelled) e).outcome()).toList());
    }

    /** A stale first attempt stays stale on redelivery, even once the head is back on the skipped commit. */
    @Test
    void aRecordedStaleOutcomeIsReportedAgainWithoutAForgeCall() {
        fx.head = "TEST-newer-commit";
        fx.checksFailedLabel().apply(COMMAND);
        fx.head = COMMIT;
        fx.checksFailedLabel().apply(COMMAND);
        assertTrue(fx.sink.labelCalls.isEmpty(), fx.sink.labelCalls.toString());
        assertEquals(Outcome.STALE, reported().outcome());
    }

    /** Another run of the same head (a re-run that was skipped again) is its own slot. */
    @Test
    void anotherRunOfTheSameHeadLabelsAgain() {
        fx.checksFailedLabel().apply(COMMAND);
        fx.checksFailedLabel().apply(new LabelChecksFailed(REVIEW_ID, REPO, 9L, COMMIT, ROUND + 1, "TEST-cred"));
        assertEquals(2, fx.sink.labelCalls.size());
    }

    /** Best effort: a forge failure is reported with its reason, never thrown, and not retried on redelivery. */
    @Test
    void aForgeFailureIsReportedOnceAndNotRetried() {
        fx.sink.labelFailure = new GitHubApiException(403, "POST", "/TEST/labels", "TEST-forbidden");
        fx.checksFailedLabel().apply(COMMAND);
        ChecksFailedLabelled failed = reported();
        assertEquals(Outcome.FAILED, failed.outcome());
        assertTrue(failed.labelFailure().contains("HTTP 403"), failed.labelFailure());
        fx.sink.labelFailure = null;
        fx.checksFailedLabel().apply(COMMAND);
        assertEquals(1, fx.sink.labelCalls.size());
        assertEquals(failed, reported());
    }

    /** The claim slot is never a posted inline comment, so a later PostComments does not recover it as one. */
    @Test
    void theClaimSlotIsNotAnInlineComment() {
        assertTrue(!InlineRecovery.isInline(ChecksFailedLabel.CLAIM_PREFIX + ROUND));
    }
}

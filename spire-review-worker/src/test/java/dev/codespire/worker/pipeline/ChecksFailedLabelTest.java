package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.LabelChecksFailed;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review waits for CI: a skipped run's label command makes the pull request's bot label converge to the desired label
 * of the review's current run, so a late, stale or redelivered command never leaves a stale ci-failed behind.
 */
class ChecksFailedLabelTest {

    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private static final String CI_FAILED = VerdictLabel.CI_FAILED.name();
    private static final String APPROVED = VerdictLabel.APPROVED.name();
    private static final LabelChecksFailed COMMAND = new LabelChecksFailed("TEST-review", REPO, 7L, "a".repeat(40), 1,
            "TEST-cred");

    private final LabelRecordingSink sink = new LabelRecordingSink();
    private final VerdictLabelWorkerFixture.DesiredState desired = new VerdictLabelWorkerFixture.DesiredState();

    @Test
    void theSkippedRunsLabelIsSetAndNothingIsPosted() {
        desired.desire(1, CI_FAILED);
        sink.labels.addAll(List.of("review: requested", "TEST-unrelated"));
        label().apply(COMMAND);
        assertEquals(Set.of("TEST-unrelated", CI_FAILED), sink.labels);
        assertEquals(List.of("label"), sink.calls, "no comment, no review: the label is the only mark");
    }

    /** Run 2 started at once after the skip and wants no verdict label yet: the stale ci-failed is removed. */
    @Test
    void anImmediateRerunAfterASkipLeavesNoStaleCiFailed() {
        sink.labels.addAll(List.of(CI_FAILED, "TEST-unrelated"));
        desired.desire(2, null);
        label().apply(COMMAND);
        assertEquals(Set.of("TEST-unrelated"), sink.labels);
    }

    /** The old command lands after run 2 approved: run 2's label stands. */
    @Test
    void aStaleEffectLandingAfterANewerRunsLabelEndsOnTheNewerRunsLabel() {
        desired.desire(1, CI_FAILED);
        sink.duringLabel = () -> {
            desired.desire(2, APPROVED);
            sink.labels.add(APPROVED);
        };
        label().apply(COMMAND);
        assertEquals(Set.of(APPROVED), sink.labels);
    }

    /** The worker dies after its GitHub call; the redelivered command converges. */
    @Test
    void aRedeliveryAfterACrashConverges() {
        desired.desire(1, CI_FAILED);
        sink.duringLabel = () -> {
            desired.desire(2, APPROVED);
            desired.failingReads = 1;
        };
        label().apply(COMMAND);
        assertEquals(Set.of(CI_FAILED), sink.labels, "the crash left the stale label");
        label().apply(COMMAND);
        assertEquals(Set.of(APPROVED), sink.labels);
    }

    /** Best effort: a forge failure is logged, never thrown, so the command is not dead-lettered. */
    @Test
    void aForgeFailureIsSwallowed() {
        desired.desire(1, CI_FAILED);
        sink.labelFailure = new IllegalStateException("TEST-forbidden");
        label().apply(COMMAND);
        assertTrue(sink.labels.isEmpty());
    }

    private ChecksFailedLabel label() {
        ChecksFailedLabel label = new ChecksFailedLabel();
        label.idempotency = desired;
        label.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(null, sink);
            }
        };
        return label;
    }
}

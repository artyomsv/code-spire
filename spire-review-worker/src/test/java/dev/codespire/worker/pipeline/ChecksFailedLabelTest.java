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

/** Review waits for CI: a skipped head gets "review: ci-failed" and nothing posted. */
class ChecksFailedLabelTest {

    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private static final LabelChecksFailed COMMAND = new LabelChecksFailed("TEST-review", REPO, 7L, "a".repeat(40),
            "TEST-cred");

    private final LabelRecordingSink sink = new LabelRecordingSink();

    @Test
    void theLabelReplacesTheOtherReviewLabelsAndNothingIsPosted() {
        sink.labels.addAll(List.of("review: requested", "TEST-unrelated"));
        label().apply(COMMAND);
        assertEquals(List.of(VerdictLabel.CI_FAILED.name()), sink.labelCalls);
        assertEquals(Set.of("TEST-unrelated", "review: ci-failed"), sink.labels);
        assertEquals(List.of("label"), sink.calls, "no comment, no review: the label is the only mark");
    }

    /** Best effort: a forge failure is logged, never thrown, so the command is not dead-lettered. */
    @Test
    void aForgeFailureIsSwallowed() {
        sink.labelFailure = new IllegalStateException("TEST-forbidden");
        label().apply(COMMAND);
        assertTrue(sink.labels.isEmpty());
    }

    private ChecksFailedLabel label() {
        ChecksFailedLabel label = new ChecksFailedLabel();
        label.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(null, sink);
            }
        };
        return label;
    }
}

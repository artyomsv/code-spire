package dev.codespire.worker.pipeline;

import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.VerdictLabel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The worker test's recording sink with a forge-native review and a pull request's labels held as state, as GitHub
 * holds them: setting a verdict label removes the other labels of its family and keeps the rest.
 */
class LabelRecordingSink extends ReviewWorkerTest.RecordingSink {

    /** The pull request's labels now; a test may change them between deliveries, as another reviewer would. */
    final Set<String> labels = new LinkedHashSet<>();
    /** Each label call, by the label it asked for. */
    final List<String> labelCalls = new ArrayList<>();
    /** Thrown by setVerdictLabel while set. */
    RuntimeException labelFailure;

    LabelRecordingSink() {
        nativeReviews = true;
    }

    @Override
    public CommentSink.LabelOutcome setVerdictLabel(RepoRef repo, long prId, VerdictLabel label) {
        calls.add("label");
        labelCalls.add(label.name());
        if (labelFailure != null) {
            throw labelFailure;
        }
        labels.removeIf(label::replaces);
        return labels.add(label.name()) ? CommentSink.LabelOutcome.APPLIED : CommentSink.LabelOutcome.ALREADY_SET;
    }
}

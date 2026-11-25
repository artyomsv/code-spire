package dev.codespire.worker.pipeline;

import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.worker.pipeline.CommentIdempotencyStore.DesiredLabel;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Makes a pull request's bot verdict label match the desired label of the review's current run (verdict labels, V120;
 * review waits for CI, V121), read from {@code review_label_desired}, which only the orchestrator writes.
 *
 * <p>Desired state, not an effect to perform: every label command (a posted review, a skip for failed checks) runs
 * this, and it applies whatever the current run wants, never what the command once meant. After each GitHub call the
 * desired label is read again; when a newer run changed it meanwhile, that one is applied (bounded by
 * {@link #MAX_PASSES}). So a stale or replayed command re-applies the current run's label, and a worker that died after
 * its GitHub call heals on redelivery. The bot owns labels starting {@code review: }: a desired label replaces every
 * other one, a human-set one included; "no label" removes only the bot's own verdict labels.
 */
final class LabelReconciler {

    /** Enough for a newer run deciding during a pass or two; a run deciding every pass is left to its own command. */
    static final int MAX_PASSES = 3;
    private static final List<VerdictLabel> BOT_LABELS =
            List.of(VerdictLabel.APPROVED, VerdictLabel.CHANGES_REQUESTED, VerdictLabel.CI_FAILED);
    private static final Logger LOG = Logger.getLogger(LabelReconciler.class);

    /** The pull request whose label converges. */
    record Target(String reviewId, RepoRef repo, long prId) {
    }

    private final CommentIdempotencyStore desired;
    private final CommentSink sink;

    LabelReconciler(CommentIdempotencyStore desired, CommentSink sink) {
        this.desired = desired;
        this.sink = sink;
    }

    /** Applies the current run's desired label until it stops changing. Forge and read failures propagate. */
    void converge(Target target) {
        Optional<DesiredLabel> applied = Optional.empty();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            Optional<DesiredLabel> wanted = desired.desiredLabel(target.reviewId());
            if (wanted.isEmpty() || Objects.equals(wanted, applied)) return;
            apply(target, wanted.orElseThrow());
            applied = wanted;
        }
        LOG.warnf("review %s: the desired verdict label kept changing; the next label command converges it",
                target.reviewId());
    }

    private void apply(Target target, DesiredLabel wanted) {
        if (wanted.label() != null) {
            sink.setVerdictLabel(target.repo(), target.prId(), new VerdictLabel(wanted.label(), VerdictLabel.PREFIX));
        } else {
            BOT_LABELS.forEach(label -> sink.removeVerdictLabel(target.repo(), target.prId(), label));
        }
        LOG.infof("review %s: verdict label on %s#%d is now %s (run %d)", target.reviewId(), target.repo().full(),
                target.prId(), wanted.label() == null ? "none" : wanted.label(), wanted.round());
    }
}

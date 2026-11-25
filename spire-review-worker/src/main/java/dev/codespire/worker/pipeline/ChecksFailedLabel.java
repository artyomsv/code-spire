package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.LabelChecksFailed;
import dev.codespire.worker.adapters.WorkerScmClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * A skipped run's label command (review waits for CI, docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md):
 * nothing is posted, and the pull request's bot verdict label converges to the desired label of the review's current
 * run ({@link LabelReconciler}). The orchestrator set that to {@code review: ci-failed} in the round lock that ended the
 * skipped run; if a newer run was admitted since, it is that run's label, so a late or replayed command never puts a
 * stale ci-failed back. The command's round is for the log only.
 *
 * <p>Best effort: a failure is logged and the command acknowledged; the next label command converges again.
 */
@ApplicationScoped
public class ChecksFailedLabel {

    private static final Logger LOG = Logger.getLogger(ChecksFailedLabel.class);

    @Inject
    WorkerScmClients scm;

    @Inject
    CommentIdempotencyStore idempotency;

    public void apply(LabelChecksFailed command) {
        try {
            new LabelReconciler(idempotency, scm.forCommand(command).comments())
                    .converge(new LabelReconciler.Target(command.reviewId(), command.repo(), command.prId()));
        } catch (RuntimeException e) {
            LOG.warnf(e, "review %s: the verdict label could not converge after run %d's failed checks on %s",
                    command.reviewId(), command.round(), command.commit());
        }
    }
}

package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.LabelChecksFailed;
import dev.codespire.contract.port.CommentSink.LabelOutcome;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.worker.adapters.WorkerScmClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Labels a pull request {@code review: ci-failed} when its checks failed and no review ran (review waits for CI,
 * docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md). Nothing is posted: the label is the only mark on
 * the pull request, and it replaces every other {@code review: } label like a verdict label does.
 *
 * <p>Guarded like the verdict label ({@link VerdictLabels}) before the visible change:
 * <ul>
 *   <li>once per skipped run: the attempt is recorded under {@code CI_FAILED:<round>}, so a replayed command never
 *   labels again over a label someone set since;</li>
 *   <li>never for a head the pull request has moved past (a delayed command);</li>
 *   <li>never after a later review of the same head set its verdict label (a re-run on the same commit).</li>
 * </ul>
 * Best effort: a forge failure is logged and the command is acknowledged, so a label never dead-letters a skip the
 * orchestrator already recorded.
 */
@ApplicationScoped
public class ChecksFailedLabel {

    /** The claim slot of one skipped run's label, beside the review's own slots; never a posted comment. */
    static final String CLAIM_PREFIX = "CI_FAILED:";
    private static final Logger LOG = Logger.getLogger(ChecksFailedLabel.class);

    @Inject
    WorkerScmClients scm;

    @Inject
    CommentIdempotencyStore idempotency;

    public void apply(LabelChecksFailed command) {
        try {
            label(command, scm.forCommand(command));
        } catch (RuntimeException e) {
            LOG.warnf(e, "review %s: could not set the label %s for failed checks on %s", command.reviewId(),
                    VerdictLabel.CI_FAILED.name(), command.commit());
        }
    }

    private void label(LabelChecksFailed command, WorkerScmClients.Clients clients) {
        String refusal = refusal(command, clients);
        if (refusal != null) {
            LOG.infof("review %s: no %s label: %s", command.reviewId(), VerdictLabel.CI_FAILED.name(), refusal);
            return;
        }
        String slot = CLAIM_PREFIX + command.round();
        if (idempotency.claim(command.reviewId(), command.commit(), slot)
                instanceof CommentIdempotencyStore.Claim.AlreadyPosted) {
            LOG.infof("review %s: run %d's %s label was already attempted", command.reviewId(), command.round(),
                    VerdictLabel.CI_FAILED.name());
            return;
        }
        LabelOutcome outcome = clients.comments().setVerdictLabel(command.repo(), command.prId(), VerdictLabel.CI_FAILED);
        idempotency.markPosted(command.reviewId(), command.commit(), slot, outcome.name());
        LOG.infof("review %s: label %s on %s#%d for failed checks on %s: %s", command.reviewId(),
                VerdictLabel.CI_FAILED.name(), command.repo().full(), command.prId(), command.commit(), outcome);
    }

    /** Why this command must not label, or null when it may. */
    private String refusal(LabelChecksFailed command, WorkerScmClients.Clients clients) {
        String head = clients.diff().fetchPullRequest(command.repo(), command.prId()).headCommit();
        if (!Commits.matches(head, command.commit())) {
            return "the pull request moved past " + command.commit();
        }
        if (idempotency.postedFor(command.reviewId(), command.commit()).containsKey(VerdictLabels.CLAIM_KEY)) {
            return "a later review of " + command.commit() + " set its verdict label";
        }
        return null;
    }
}

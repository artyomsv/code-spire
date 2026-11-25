package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.LabelChecksFailed;
import dev.codespire.contract.port.CommentSink.LabelOutcome;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.worker.adapters.WorkerScmClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Labels a pull request {@code review: ci-failed} when its checks failed and no review ran (review waits for CI,
 * docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md). Nothing is posted: the label is the only mark on
 * the pull request, and it replaces every other {@code review: } label like a verdict label does.
 *
 * <p>Guarded like the verdict label ({@link VerdictLabels}) before the visible change:
 * <ul>
 *   <li>once per skipped run, and never for a run a newer run replaced: the orchestrator opens the run's
 *   {@code CI_FAILED:<round>} receipt before it sends the command and, in the round lock that admits the next run of the
 *   review (a re-run of the same head included), closes every open or taken one ({@code superseded}). The worker takes
 *   the receipt with a compare-and-set before the forge call, so a closed receipt is never taken; and it records its
 *   outcome only over its own mark, so a receipt closed while the label was being set tells it to undo that label. A
 *   compare-and-set orders the receipts, not the forge calls: between the undo's read and its calls a newer run can
 *   still label (docs/UNVERIFIED.md). A re-run keeps these receipts when it clears the worker's other claims;</li>
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
    /** What the orchestrator records on a receipt it closed because a newer run was admitted. */
    static final String SUPERSEDED = "superseded";
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
        ReceiptKey receipt = new ReceiptKey(command.reviewId(), command.commit(), CLAIM_PREFIX + command.round());
        if (!idempotency.take(receipt)) {
            LOG.infof("review %s: no %s label for run %d: a newer run started, or it was already attempted",
                    command.reviewId(), VerdictLabel.CI_FAILED.name(), command.round());
            return;
        }
        LabelOutcome outcome = clients.comments().setVerdictLabel(command.repo(), command.prId(), VerdictLabel.CI_FAILED);
        if (!idempotency.markTaken(receipt, outcome.name())) {
            undoStale(command, clients);
            return;
        }
        LOG.infof("review %s: label %s on %s#%d for failed checks on %s: %s", command.reviewId(),
                VerdictLabel.CI_FAILED.name(), command.repo().full(), command.prId(), command.commit(), outcome);
    }

    /**
     * A newer run was admitted while this label was being set (the orchestrator closed the taken receipt): the label
     * now speaks for a run that no longer exists. Restores what the newer run shows: the verdict label its review set on
     * the current head, else none of this run's, unless a newer skipped run labelled ci-failed itself. The window between
     * this read and the forge calls below remains (docs/UNVERIFIED.md).
     */
    private void undoStale(LabelChecksFailed command, WorkerScmClients.Clients clients) {
        String head = clients.diff().fetchPullRequest(command.repo(), command.prId()).headCommit();
        Map<String, String> posted = idempotency.postedFor(command.reviewId(), head);
        String newer = VerdictLabels.appliedLabel(posted.get(VerdictLabels.CLAIM_KEY));
        if (newer != null) {
            clients.comments().setVerdictLabel(command.repo(), command.prId(), new VerdictLabel(newer, VerdictLabel.PREFIX));
        } else if (!newerRunLabelled(posted, command.round())) {
            clients.comments().removeVerdictLabel(command.repo(), command.prId(), VerdictLabel.CI_FAILED);
        }
        LOG.infof("review %s: run %d's %s label was undone; a newer run began while it was set", command.reviewId(),
                command.round(), VerdictLabel.CI_FAILED.name());
    }

    /** Whether a later skipped run set (or is setting) its own ci-failed label, which must stay. */
    private static boolean newerRunLabelled(Map<String, String> posted, int round) {
        return posted.entrySet().stream().anyMatch(slot -> slot.getKey().startsWith(CLAIM_PREFIX)
                && Integer.parseInt(slot.getKey().substring(CLAIM_PREFIX.length())) > round
                && !SUPERSEDED.equals(slot.getValue()));
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

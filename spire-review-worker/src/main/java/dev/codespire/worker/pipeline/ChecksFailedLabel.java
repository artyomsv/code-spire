package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.LabelChecksFailed;
import dev.codespire.contract.event.IntegrationEvent.ChecksFailedLabelled;
import dev.codespire.contract.event.IntegrationEvent.ChecksFailedLabelled.Outcome;
import dev.codespire.contract.port.CommentSink.LabelOutcome;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.worker.adapters.WorkerScmClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * A skipped run's {@code review: ci-failed} label (review waits for CI with Verdict labels on;
 * docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md, item 6). Nothing is posted: the label is set through
 * the verdict label's port, so it replaces every other {@code review: } label and is created when missing.
 *
 * <p><b>Two guards against a stale label.</b> Right before writing, the pull request's head is read; unless it is
 * exactly the skipped commit's full SHA (a missing or short head counts as unknown), nothing is written and the
 * outcome is {@code STALE}. And the attempt is claimed once per run, under {@code LABEL_CI_FAILED#<round>}, with its
 * outcome recorded there: a redelivered command reports the recorded outcome and never calls the forge again, so it
 * cannot put the label back over one set since. A dashboard Re-run keeps these receipts (it clears the other claims).
 * A later review's verdict label replaces it as any verdict label does. Failures are reported and logged without the
 * forge's response body ({@link LabelFailureReason}).
 *
 * <p>Best effort, like the verdict label: a failure is answered as {@code FAILED} with its reason, for the attention
 * row, and never retried. The window left: a dashboard Re-run on the same commit while this write is in flight can see
 * its verdict label replaced by this one (docs/UNVERIFIED.md).
 */
@ApplicationScoped
public class ChecksFailedLabel {

    /** The claim slot of one run's attempt, beside the review's own; never a posted inline comment. */
    static final String CLAIM_PREFIX = LabelChecksFailed.RECEIPT_PREFIX;
    private static final String STORED_APPLIED = "applied";
    private static final String STORED_STALE = "stale";
    private static final String STORED_FAILED = "failed:";
    private static final Logger LOG = Logger.getLogger(ChecksFailedLabel.class);

    @Inject
    WorkerScmClients scm;

    @Inject
    CommentIdempotencyStore idempotency;

    @Inject
    ResultsEmitter results;

    public void apply(LabelChecksFailed command) {
        results.emit(outcomeOf(command));
    }

    private ChecksFailedLabelled outcomeOf(LabelChecksFailed command) {
        CommentIdempotencyStore.Claim claim;
        try {
            claim = idempotency.claim(command.reviewId(), command.commit(), slot(command));
        } catch (RuntimeException e) {
            // Unknown whether this run labelled already: calling the forge could overwrite a label set since.
            LOG.warnf(e, "review %s: could not read the ci-failed label claim of run %d; no label call",
                    command.reviewId(), command.round());
            return failed(command, "the label claim could not be read, so no label was set: " + VerdictLabels.reason(e));
        }
        if (claim instanceof CommentIdempotencyStore.Claim.AlreadyPosted recorded) {
            return recorded(command, recorded.postedRef());
        }
        return record(command, attempt(command));
    }

    /** The head check, then the write; any failure on the way is the outcome, never a thrown command. */
    private ChecksFailedLabelled attempt(LabelChecksFailed command) {
        try {
            WorkerScmClients.Clients clients = scm.forCommand(command);
            String head = clients.diff().fetchPullRequest(command.repo(), command.prId()).headCommit();
            if (!Commits.isSameFullSha(head, command.commit())) {
                LOG.infof("review %s: no %s for run %d: the head is not %s but %s", command.reviewId(),
                        VerdictLabel.CI_FAILED.name(), command.round(), command.commit(), head);
                return outcome(command, Outcome.STALE, null);
            }
            LabelOutcome outcome = clients.comments().setVerdictLabel(command.repo(), command.prId(),
                    VerdictLabel.CI_FAILED);
            LOG.infof("review %s: %s on %s#%d for run %d's failed checks on %s: %s", command.reviewId(),
                    VerdictLabel.CI_FAILED.name(), command.repo().full(), command.prId(), command.round(),
                    command.commit(), outcome);
            return outcome == LabelOutcome.UNSUPPORTED ? failed(command, "this forge sets no labels")
                    : outcome(command, Outcome.LABELLED, null);
        } catch (RuntimeException e) {
            // No throwable: a forge failure's message carries its response body (LabelFailureReason).
            LOG.warnf("review %s: could not set %s for run %d: %s", command.reviewId(), VerdictLabel.CI_FAILED.name(),
                    command.round(), LabelFailureReason.of(e));
            return failed(command, VerdictLabels.reason(e));
        }
    }

    /** The outcome kept for a redelivery; when it cannot be kept, a redelivery tries again, and the report says so. */
    private ChecksFailedLabelled record(LabelChecksFailed command, ChecksFailedLabelled outcome) {
        try {
            idempotency.markPosted(command.reviewId(), command.commit(), slot(command), stored(outcome));
            return outcome;
        } catch (RuntimeException e) {
            LOG.warnf(e, "review %s: could not record the ci-failed label outcome of run %d", command.reviewId(),
                    command.round());
            String unrecorded = "the label outcome could not be recorded, so a redelivery may label again: "
                    + VerdictLabels.reason(e);
            return failed(command, outcome.labelFailure() == null ? unrecorded : outcome.labelFailure() + "; " + unrecorded);
        }
    }

    private static ChecksFailedLabelled recorded(LabelChecksFailed command, String stored) {
        if (stored != null && stored.startsWith(STORED_FAILED)) {
            return failed(command, stored.substring(STORED_FAILED.length()));
        }
        return outcome(command, STORED_STALE.equals(stored) ? Outcome.STALE : Outcome.LABELLED, null);
    }

    private static String stored(ChecksFailedLabelled outcome) {
        return switch (outcome.outcome()) {
            case LABELLED -> STORED_APPLIED;
            case STALE -> STORED_STALE;
            case FAILED -> STORED_FAILED + outcome.labelFailure();
        };
    }

    private static ChecksFailedLabelled failed(LabelChecksFailed command, String reason) {
        return outcome(command, Outcome.FAILED, VerdictLabels.bounded(reason));
    }

    private static ChecksFailedLabelled outcome(LabelChecksFailed command, Outcome outcome, String reason) {
        return new ChecksFailedLabelled(command.reviewId(), command.commit(), command.round(), outcome, reason);
    }

    private static String slot(LabelChecksFailed command) {
        return CLAIM_PREFIX + command.round();
    }
}

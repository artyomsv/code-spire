package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.port.CommentSink;
import org.jboss.logging.Logger;

/**
 * The verdict label a posted review leaves on its pull request (verdict labels, V120;
 * docs/superpowers/specs/2026-11-24-verdict-labels-design.md). Package-private; {@link ReviewWorker} calls it once the
 * native review is posted.
 *
 * <p>The label is the desired label of the review's current run, which the orchestrator recorded from its DECIDED
 * verdict before it sent this command ({@code review_label_desired}): with "Reviewer may approve" off an approving
 * review is posted as a Comment and still labels {@code review: approved}; a degraded review, or one held because CI
 * had not finished, wants no verdict label. The pull request converges to it ({@link LabelReconciler}), so a
 * redelivered or stale PostComments re-applies the current run's label and a worker that died after its GitHub call
 * heals on redelivery. Best effort: a failure is logged and answered as a reason for the attention row; it never
 * fails, retries or re-posts the review.
 */
final class VerdictLabels {

    /** A claim slot of earlier versions, beside the review's own; never a posted inline comment. */
    static final String CLAIM_KEY = "LABEL";
    /** The stored reason is for a person to read; a forge error body past this adds nothing. */
    private static final int MAX_REASON = 300;
    private static final Logger LOG = Logger.getLogger(VerdictLabels.class);

    private final CommentIdempotencyStore idempotency;
    private final CommentSink sink;

    VerdictLabels(CommentIdempotencyStore idempotency, CommentSink sink) {
        this.idempotency = idempotency;
        this.sink = sink;
    }

    /**
     * Never throws: the review is already posted, so a failed read or label call is reported, and the caller still
     * reports the posted review.
     *
     * @return why the label could not converge, or null when it did or the repository asked for no labels
     */
    String apply(PostComments command) {
        if (!Boolean.TRUE.equals(command.verdictLabels())) {
            return null;
        }
        try {
            new LabelReconciler(idempotency, sink)
                    .converge(new LabelReconciler.Target(command.reviewId(), command.repo(), command.prId()));
            return null;
        } catch (RuntimeException e) {
            LOG.warnf(e, "review %s: the verdict label could not converge; the review stays posted", command.reviewId());
            return reason(e);
        }
    }

    private static String reason(RuntimeException e) {
        Throwable cause = WorkerFailures.unwrap(e);
        String message = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName() : cause.getMessage();
        return message.length() <= MAX_REASON ? message : message.substring(0, MAX_REASON);
    }
}

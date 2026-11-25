package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.CommentSink.LabelOutcome;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.VerdictLabel;
import org.jboss.logging.Logger;

/**
 * The verdict label a posted review leaves on its pull request (verdict labels, V120;
 * docs/superpowers/specs/2026-11-24-verdict-labels-design.md). Package-private; {@link ReviewWorker} calls it once the
 * native review is posted.
 *
 * <p>The label follows the orchestrator's DECIDED verdict ({@code assessment}), not the posted review event: with
 * "Reviewer may approve" off an approving review is posted as a Comment and still labels {@code review: approved}. A
 * degraded review's assessment is COMMENT, so it sets no label. Best effort: a failure is logged and answered as a
 * reason for the attention row; it never fails, retries or re-posts the review.
 *
 * <p><b>Once per review run.</b> The attempt is claimed under {@link #CLAIM_KEY} and its outcome, success or failure,
 * is recorded there. A redelivered PostComments that recovers the posted review reports the recorded outcome and
 * never calls the forge again, so it cannot overwrite a label someone set after the first attempt, and a failed
 * attempt is not retried. A re-run clears the worker's claims, so a new run labels anew. The window left: a worker
 * that dies after the forge call and before the outcome is recorded, or whose record cannot be written, calls the
 * forge again on redelivery. A claim that cannot be read or a record that cannot be written is reported as the label
 * failure, never as a failed review.
 */
final class VerdictLabels {

    /** The claim slot of the label attempt, beside the review's own; never a posted inline comment. */
    static final String CLAIM_KEY = "LABEL";
    private static final String APPLIED = "applied";
    private static final String FAILED = "failed:";
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
     * Never throws: the review is already posted, so the claim store failing is reported like a forge failure (review of
     * PR #444, M-2) and the caller still reports the posted review.
     *
     * @return why the label was not set or its outcome is uncertain, or null when it succeeded or had nothing to do
     */
    String apply(PostComments command) {
        VerdictLabel label = labelFor(command);
        if (label == null) {
            return null;
        }
        CommentIdempotencyStore.Claim claim;
        try {
            claim = idempotency.claim(command.reviewId(), command.commit(), CLAIM_KEY);
        } catch (RuntimeException e) {
            // Unknown whether this run labelled already: calling the forge could overwrite a label set since.
            LOG.warnf(e, "review %s: could not read the verdict label claim; no label call", command.reviewId());
            return bounded("the label claim could not be read, so no label was set: " + reason(e));
        }
        if (claim instanceof CommentIdempotencyStore.Claim.AlreadyPosted recorded) {
            return failureOf(recorded.postedRef());
        }
        return record(command, attempt(command, label));
    }

    /** The outcome kept for a redelivery; when it cannot be kept, a redelivery labels again, and the report says so. */
    private String record(PostComments command, String failure) {
        try {
            idempotency.markPosted(command.reviewId(), command.commit(), CLAIM_KEY, failure == null ? APPLIED : FAILED + failure);
            return failure;
        } catch (RuntimeException e) {
            LOG.warnf(e, "review %s: could not record the verdict label outcome", command.reviewId());
            String unrecorded = "the label outcome could not be recorded, so a redelivery may label again: " + reason(e);
            return bounded(failure == null ? unrecorded : failure + "; " + unrecorded);
        }
    }

    /** The label for this command, or null when the repository asked for none or the verdict names none. */
    static VerdictLabel labelFor(PostComments command) {
        if (!Boolean.TRUE.equals(command.verdictLabels())) {
            return null;
        }
        if (command.assessment() == ReviewVerdict.APPROVE) {
            return VerdictLabel.APPROVED;
        }
        return command.assessment() == ReviewVerdict.REQUEST_CHANGES ? VerdictLabel.CHANGES_REQUESTED : null;
    }

    private String attempt(PostComments command, VerdictLabel label) {
        try {
            LabelOutcome outcome = sink.setVerdictLabel(command.repo(), command.prId(), label);
            LOG.infof("review %s: verdict label %s on %s#%d: %s", command.reviewId(), label.name(),
                    command.repo().full(), command.prId(), outcome);
            return null;
        } catch (RuntimeException e) {
            LOG.warnf(e, "review %s: could not set the verdict label %s; the review stays posted",
                    command.reviewId(), label.name());
            return reason(e);
        }
    }

    private static String failureOf(String recorded) {
        return recorded != null && recorded.startsWith(FAILED) ? recorded.substring(FAILED.length()) : null;
    }

    private static String reason(RuntimeException e) {
        Throwable cause = WorkerFailures.unwrap(e);
        String message = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName() : cause.getMessage();
        return bounded(message);
    }

    private static String bounded(String message) {
        return message.length() <= MAX_REASON ? message : message.substring(0, MAX_REASON);
    }
}

package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.CommentSink.LabelOutcome;
import dev.codespire.contract.review.ReviewVerdict;
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
 */
final class VerdictLabels {

    static final String PREFIX = "review: ";
    static final String APPROVED = PREFIX + "approved";
    static final String CHANGES_REQUESTED = PREFIX + "changes-requested";
    /** The stored reason is for a person to read; a forge error body past this adds nothing. */
    private static final int MAX_REASON = 300;
    private static final Logger LOG = Logger.getLogger(VerdictLabels.class);

    private VerdictLabels() {
    }

    /** @return why the label call failed, or null when it succeeded or had nothing to do */
    static String apply(CommentSink sink, PostComments command) {
        String label = labelFor(command);
        if (label == null) {
            return null;
        }
        try {
            LabelOutcome outcome = sink.setVerdictLabel(command.repo(), command.prId(), label, PREFIX);
            LOG.infof("review %s: verdict label %s on %s#%d: %s", command.reviewId(), label, command.repo().full(),
                    command.prId(), outcome);
            return null;
        } catch (RuntimeException e) {
            LOG.warnf(e, "review %s: could not set the verdict label %s; the review stays posted",
                    command.reviewId(), label);
            return reason(e);
        }
    }

    /** The label for this command, or null when the repository asked for none or the verdict names none. */
    static String labelFor(PostComments command) {
        if (!Boolean.TRUE.equals(command.verdictLabels())) {
            return null;
        }
        if (command.assessment() == ReviewVerdict.APPROVE) {
            return APPROVED;
        }
        return command.assessment() == ReviewVerdict.REQUEST_CHANGES ? CHANGES_REQUESTED : null;
    }

    private static String reason(RuntimeException e) {
        Throwable cause = WorkerFailures.unwrap(e);
        String message = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName() : cause.getMessage();
        return message.length() <= MAX_REASON ? message : message.substring(0, MAX_REASON);
    }
}

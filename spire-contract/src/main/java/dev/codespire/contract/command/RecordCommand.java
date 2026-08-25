package dev.codespire.contract.command;

import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.ThreadRef;

/**
 * Record commands go to the ReviewLifecycle decider and append domain events
 * (CONTRACT §5/§6). Sagas translate integration results into these.
 */
public sealed interface RecordCommand {

    /** force=true bypasses the reviewed-commit idempotency (FR-12, "/review"). */
    record RequestReview(String commit, String trigger, boolean force) implements RecordCommand {
    }

    /** Only PR-close/operator actions emit this — NEVER supersede (ADR-013). */
    record CancelReview(String reason) implements RecordCommand {
    }

    record RecordReviewOutcome(String commit, int findingsCount, String summaryDigest) implements RecordCommand {
    }

    record RecordCommentsPosted(String commit, String summaryCommentId, int count) implements RecordCommand {
    }

    /** Guarded by commit==currentCommit — a stale failure from a superseded run is a no-op (CONTRACT §6). */
    record RecordFailure(String commit, String phase, boolean retryable) implements RecordCommand {
    }

    record OpenThread(ThreadRef threadRef, String parentCommentId) implements RecordCommand {
    }

    record RecordFollowUp(ThreadRef threadRef, String commentId) implements RecordCommand {
    }

    /**
     * A human ran {@code /finding} in a review thread. The message rides here on its way to the
     * encrypted read model; it does NOT reach the domain event, which keeps only the anchor and
     * severity (a finding message may quote source code — DATA-MODEL §5).
     */
    record RaiseConversationFinding(ThreadRef threadRef, String path, int line, Severity severity,
                                    String message, String triggeringCommentId) implements RecordCommand {
    }
}

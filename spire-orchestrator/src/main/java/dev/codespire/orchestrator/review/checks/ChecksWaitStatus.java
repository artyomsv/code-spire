package dev.codespire.orchestrator.review.checks;

/**
 * The read-model words of review waits for CI. The two statuses are members of the dashboard's {@code ReviewStatus}
 * union ({@code spire-ui/src/api.ts}); the cancel reason is what the aggregate's {@code ReviewCancelled} carries for a
 * skip, and what {@code DomainEventSink} projects as {@link #SKIPPED} rather than {@code cancelled}.
 */
public final class ChecksWaitStatus {

    /** The review waits for the checks on its head; nothing has run or been charged. */
    public static final String WAITING = "waiting_for_checks";
    /** The checks on the head failed, so no review ran. Terminal for that head; a new commit waits again. */
    public static final String SKIPPED = "skipped";
    /** {@code SkipReview}'s reason, which its {@code ReviewCancelled} carries. */
    public static final String CHECKS_FAILED = "CHECKS_FAILED";

    private ChecksWaitStatus() {
    }
}

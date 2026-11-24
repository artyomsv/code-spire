package dev.codespire.orchestrator.review.checks;

import java.time.Instant;
import java.util.UUID;

/**
 * A review's wait for the checks on {@code head} (review waits for CI, V121), as one row of {@code review_checks_wait}.
 * {@code round} identifies the run: the count of {@code ReviewRequested} in the review's stream when the wait began, so
 * a re-run on the same head is another run. {@code untrusted} is the fork flag the review's {@code FetchDiff} carries;
 * the credential is packed again when the review starts. {@code failedChecks} names the failed checks of a skip.
 */
public record ChecksWait(String reviewId, UUID repositoryId, String head, int round, boolean untrusted,
                         Instant waitingSince, Stage stage, String failedChecks) {

    /** Where the wait is: its claimed stages are the recoverable intent of an outcome not yet complete. */
    public enum Stage {
        WAITING, STARTING, STARTING_UNFINISHED, SKIPPING, TIMED_OUT;

        /** The stored form ({@code review_checks_wait.state}). */
        public String stored() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Stage ofStored(String state) {
            return valueOf(state.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** A new wait, not yet read. */
    public static ChecksWait waiting(Run run, UUID repositoryId, boolean untrusted) {
        return new ChecksWait(run.reviewId(), repositoryId, run.head(), run.round(), untrusted, Instant.now(),
                Stage.WAITING, null);
    }

    /** The same wait in another stage, after a successful claim. */
    public ChecksWait at(Stage next) {
        return new ChecksWait(reviewId, repositoryId, head, round, untrusted, waitingSince, next, failedChecks);
    }

    /** The same wait claimed as a skip of {@code failed}. */
    public ChecksWait skipping(String failed) {
        return new ChecksWait(reviewId, repositoryId, head, round, untrusted, waitingSince, Stage.SKIPPING, failed);
    }

    public Run run() {
        return new Run(reviewId, head, round);
    }

    /** The run a wait belongs to: the review, its head and its round. */
    public record Run(String reviewId, String head, int round) {
    }
}

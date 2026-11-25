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

    /**
     * Where the run is: ADMITTED until its review header is set up, then WAITING; its claimed stages are the recoverable intent of an outcome not yet complete; STARTED, SKIPPED
     * and TIMED_OUT are terminal and stay as the admission record later writes fence on.
     */
    public enum Stage {
        ADMITTED, WAITING, STARTING, STARTING_UNFINISHED, SKIPPING, STARTED, SKIPPED, TIMED_OUT;

        /** The stored form ({@code review_checks_wait.state}). */
        public String stored() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Stage ofStored(String state) {
            return valueOf(state.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** A newly admitted run of the review: nothing reads it until it is made ready. */
    public static ChecksWait admitted(Run run, UUID repositoryId) {
        return new ChecksWait(run.reviewId(), repositoryId, run.head(), run.round(), false, Instant.now(), Stage.ADMITTED,
                null);
    }

    /** The same run with the fork flag its review's start sends. */
    public ChecksWait withUntrusted(boolean value) {
        return new ChecksWait(reviewId, repositoryId, head, round, value, waitingSince, stage, failedChecks);
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

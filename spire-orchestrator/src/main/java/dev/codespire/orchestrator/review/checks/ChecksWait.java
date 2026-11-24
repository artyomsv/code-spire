package dev.codespire.orchestrator.review.checks;

import java.time.Instant;
import java.util.UUID;

/**
 * A review that waits for the checks on {@code head} (review waits for CI, V121). {@code untrusted} is the fork flag the
 * review's {@code FetchDiff} carries, kept so the start sends the same one; the credential is packed again at start.
 */
public record ChecksWait(String reviewId, UUID repositoryId, String head, boolean untrusted, Instant waitingSince) {
}

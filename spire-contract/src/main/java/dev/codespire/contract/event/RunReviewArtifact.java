package dev.codespire.contract.event;

import dev.codespire.contract.review.ReviewRunBinding;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A harness review run's answer (harness reviews, step 4), scrubbed of the run's credentials by the worker. A terminal
 * result: nothing was pushed and the unit is destroyed once it is recorded.
 *
 * <p>An answer over {@link #MAX_TEXT_BYTES} is not a lost paid run: it comes back with {@code overCap} set and no
 * text, so the tokens it bought are still charged and the review is degraded rather than silently retried. Usage is
 * nullable and null IS unknown, as on {@link RunResult.RunFinished}.
 */
public record RunReviewArtifact(String runId, ReviewRunBinding review, String text, boolean overCap,
                                Map<String, Long> tokenUsage, long activeWallSeconds,
                                List<RunResult.EgressAttempt> egress) implements RunResult {

    /** The review answer's cap in UTF-8 bytes, eight times a draft's: findings for a whole pull request. */
    public static final int MAX_TEXT_BYTES = 256 * 1024;

    public RunReviewArtifact {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("A review answer needs its run");
        Objects.requireNonNull(review, "A review answer needs the call it answers");
        // An answer written before the field existed reads as none: that worker had no proxy to report on.
        egress = egress == null ? List.of() : List.copyOf(egress);
        requireTextOrOverCap(text, overCap);
        if (tokenUsage != null) {
            if (tokenUsage.isEmpty()) throw new IllegalArgumentException("An empty usage map is not a measurement");
            tokenUsage = Map.copyOf(tokenUsage);
        }
        if (activeWallSeconds < 0) throw new IllegalArgumentException("Active wall time cannot be negative");
    }

    /** Exactly one of the two: the text within the cap, or no text and the over-cap mark. */
    private static void requireTextOrOverCap(String text, boolean overCap) {
        if (overCap) {
            if (text != null) throw new IllegalArgumentException("An over-cap answer carries no text");
            return;
        }
        if (text == null || text.isBlank()) throw new IllegalArgumentException("An answer within the cap needs its text");
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT_BYTES)
            throw new IllegalArgumentException("An answer of " + bytes + " bytes is over the " + MAX_TEXT_BYTES + " byte cap");
    }

    /** Rebuild carrying what the agent asked its egress proxy for, enumerating the components once. */
    public RunReviewArtifact withEgress(List<RunResult.EgressAttempt> attempts) {
        return new RunReviewArtifact(runId, review, text, overCap, tokenUsage, activeWallSeconds, attempts);
    }
}

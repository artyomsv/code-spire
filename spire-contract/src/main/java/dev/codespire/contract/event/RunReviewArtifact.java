package dev.codespire.contract.event;

import dev.codespire.contract.review.ReviewRunBinding;

import java.util.List;
import java.util.Map;

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

    /** Rebuild carrying what the agent asked its egress proxy for, enumerating the components once. */
    public RunReviewArtifact withEgress(List<RunResult.EgressAttempt> attempts) {
        return new RunReviewArtifact(runId, review, text, overCap, tokenUsage, activeWallSeconds, attempts);
    }
}

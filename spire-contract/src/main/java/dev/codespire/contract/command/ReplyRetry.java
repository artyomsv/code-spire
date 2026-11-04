package dev.codespire.contract.command;

/**
 * How the review worker retries a harness reply's prepare or post (harness reviews, step 7): the conversation settings'
 * attempts and backoff, the same the API path's {@code AnswerFollowUp} carries, packed by the orchestrator.
 */
public record ReplyRetry(int maxAttempts, long backoffBaseMs, double backoffFactor) {

    public ReplyRetry {
        if (maxAttempts < 1) throw new IllegalArgumentException("A reply makes at least one attempt, not " + maxAttempts);
        if (backoffBaseMs < 0) throw new IllegalArgumentException("A backoff is not negative, not " + backoffBaseMs);
        if (backoffFactor < 1) throw new IllegalArgumentException("A backoff never shrinks, not x" + backoffFactor);
    }
}

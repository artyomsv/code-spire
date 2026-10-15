package dev.codespire.contract.scm;

import dev.codespire.contract.review.ReviewVerdict;

import java.util.Map;

/**
 * A forge-native review as the forge holds it: its id, the verdict it actually carries (COMMENT when the
 * forge refused the asked one, D6), and each inline comment's thread ref keyed by
 * {@link InlineAnchor#anchorKey()} — the key the worker's idempotency map uses.
 */
public record PostedReview(String reviewRef, ReviewVerdict verdict, Map<String, String> inlineThreads) {

    public PostedReview {
        inlineThreads = inlineThreads == null ? Map.of() : Map.copyOf(inlineThreads);
    }
}

package dev.codespire.contract.scm;

import dev.codespire.contract.review.ReviewVerdict;

import java.util.Map;

/**
 * A forge-native review as the forge holds it: its id, the verdict it actually carries (COMMENT when the
 * forge refused the asked one, D6), each inline comment's thread ref keyed by
 * {@link InlineAnchor#anchorKey()} — the key the worker's idempotency map uses — and the forge's own
 * link to the review. The link comes from the adapter because how a forge addresses a review is
 * provider knowledge the core never holds (ADR-047).
 */
public record PostedReview(String reviewRef, ReviewVerdict verdict, Map<String, String> inlineThreads, String reviewUrl) {

    public PostedReview {
        inlineThreads = inlineThreads == null ? Map.of() : Map.copyOf(inlineThreads);
    }

    /** A review whose link the adapter did not report. */
    public PostedReview(String reviewRef, ReviewVerdict verdict, Map<String, String> inlineThreads) {
        this(reviewRef, verdict, inlineThreads, null);
    }
}

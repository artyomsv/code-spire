package dev.codespire.worker.pipeline;

/** One slot of {@code comment_idempotency}: the review, the commit and the slot's key. */
public record ReceiptKey(String reviewId, String commit, String anchorKey) {
}

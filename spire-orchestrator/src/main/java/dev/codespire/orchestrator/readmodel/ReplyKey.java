package dev.codespire.orchestrator.readmodel;

/** One reply the bot was asked to answer (V122): the review it belongs to and the person's comment that asked. */
public record ReplyKey(String reviewId, String commentId) {
}

package dev.codespire.orchestrator.readmodel;

/**
 * One line of a review's event stream ({@code review_event}). {@code threadRef} is the SCM thread a conversation turn
 * belongs to (null for other events); {@code commentId} (V122, review of PR #457) is the person's reply a
 * conversation event belongs to: the reply itself on {@code AuthorReplied}, the reply answered or declined on {@code
 * FollowUpGenerated} and {@code FollowUpDeclined}, so the dashboard pairs each reply with its own ending.
 */
public record EventLine(String reviewId, String lane, String type, String detail, String threadRef,
                        String commentId) {
}

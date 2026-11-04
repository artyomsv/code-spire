package dev.codespire.orchestrator.review.run;

import dev.codespire.orchestrator.llm.CallRefs;

/**
 * The thread and the turn a reply run answers, read back from its call slot (harness reviews, step 7). The slot is
 * {@link CallRefs#followUpSlot}'s {@code <threadRef>:<triggeringCommentId>}, which the run's row stores, so a run failure
 * that carries no binding still names its thread. Split at the LAST colon: a comment id never holds one (every forge's
 * is numeric), while a thread ref may (a GitHub review's summary thread is {@code <prefix>::<id>}).
 */
public record ReplyCall(String threadRef, String triggeringCommentId) {

    public ReplyCall {
        if (threadRef == null || threadRef.isBlank() || triggeringCommentId == null || triggeringCommentId.isBlank()) {
            throw new IllegalArgumentException("A reply call names its thread and its turn");
        }
    }

    public static ReplyCall ofSlot(String callSlot) {
        int colon = callSlot == null ? -1 : callSlot.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("A reply run's call slot names its thread and its turn, not " + callSlot);
        }
        return new ReplyCall(callSlot.substring(0, colon), callSlot.substring(colon + 1));
    }

    /** The slot this call is charged under, the API reply's own ({@link CallRefs#followUpSlot}). */
    public String slot() {
        return CallRefs.followUpSlot(threadRef, triggeringCommentId);
    }
}

package dev.codespire.orchestrator.llm;

/**
 * The deterministic identity of one paid LLM call.
 *
 * <p>Mirrors the claim the worker already takes before spending
 * ({@code CommentIdempotencyStore.claim(reviewId, slot, key)}), rather than plumbing a new field
 * through the wire: the orchestrator can rebuild the same key from facts every delivery of the event
 * carries, so a redelivered result resolves to the same {@code call_ref} and the ledger's
 * {@code UNIQUE (call_ref, token_type)} makes the second recording a no-op.
 *
 * <p><b>The mirror is of the worker's whole (slot, key) identity, not of its slot alone.</b> For a
 * review or reconcile call the key is a constant, so the commit in the slot position carries the
 * identity by itself. A follow-up's is not: the worker claims {@code (threadRef, "followup:" +
 * triggeringCommentId)}, so the comment is what distinguishes turn 2 of a conversation from a
 * redelivery of turn 1. Comparing only the slot is what let every turn of a thread collapse onto one
 * ledger identity — see {@link #followUpSlot}.
 */
public final class CallRefs {

    private CallRefs() {
    }

    public static String of(String reviewId, String slot, ChargeKind kind) {
        if (reviewId == null || reviewId.isBlank() || slot == null || slot.isBlank()) {
            // A blank component still produces a well-formed-looking ref (e.g. "x||REVIEW") that
            // silently breaks the UNIQUE (call_ref, token_type) identity this method exists to give:
            // two different calls collide, and the second is discarded as a redelivery of the first —
            // a lost charge.
            throw new IllegalArgumentException(
                    "A charge needs a reviewId and a slot (the commit, or the thread ref for a follow-up); "
                            + "got reviewId='" + reviewId + "', slot='" + slot + "'");
        }
        return reviewId + '|' + slot + '|' + kind.name();
    }

    /**
     * A follow-up's slot: the conversation AND the turn within it.
     *
     * <p>Every turn is a distinct paid call that the worker's claim correctly permits, so every turn
     * needs its own identity here. Keyed on the thread alone, turns 2..N of one conversation resolved
     * to turn 1's {@code call_ref} and {@code recordCharges}' {@code ON CONFLICT DO NOTHING} discarded
     * them with no row, no log and no attention row — the default turn cap is 4, and an @-mention
     * removes the cap entirely, so the loss was unbounded.
     *
     * <p>A null {@code triggeringCommentId} means a legacy event recorded before the field existed.
     * It falls back to the thread ref, which is the identity such an event was originally charged
     * under, so replaying one reproduces its own row rather than adding a second.
     */
    public static String followUpSlot(String threadRef, String triggeringCommentId) {
        if (triggeringCommentId == null || triggeringCommentId.isBlank()) {
            return threadRef;
        }
        return threadRef + ':' + triggeringCommentId;
    }
}

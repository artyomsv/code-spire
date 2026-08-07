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
 * <p>The slot is the COMMIT for a review or reconcile call, and the THREAD REF for a follow-up —
 * matching what the worker puts in that position.
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
}

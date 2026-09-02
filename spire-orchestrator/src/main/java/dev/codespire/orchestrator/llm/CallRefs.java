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
 *
 * <p><b>And the claim is deletable.</b> Mirroring it is only sufficient while it survives, and a
 * re-run clears it on purpose so the LLM runs again on the same commit — see {@link #reviewSlot}.
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
     * A review or reconcile call's slot: the commit AND which run of it this is.
     *
     * <p>The commit alone is not the identity of the call, because a re-run is a second paid call on an
     * unchanged one: {@code ReviewRerunService} clears the worker's cached result and comment claims
     * before re-requesting, precisely so the LLM runs again. Keyed on the commit alone, run 2 resolved
     * to run 1's {@code call_ref} and {@code recordCharges}' {@code ON CONFLICT DO NOTHING} discarded
     * every line of it — no row, no log, no attention row, and a cost card that kept showing run 1's
     * figure however many times the operator re-ran. Both entry points reach it: the Re-run button and
     * the {@code /review} PR comment.
     *
     * <p>Run 1 keeps the bare commit, so a review that never re-ran reproduces the ref its charges
     * were written under and a replayed event yields its own row instead of a second one — the same
     * reasoning as {@link #followUpSlot}'s legacy fallback.
     *
     * <p><b>Known limit, stated rather than implied:</b> a {@code ReviewGenerated} redelivered from run
     * <i>N</i> after run <i>N+1</i> has started is priced under <i>N+1</i>'s ref, because the run
     * number is read when the result arrives rather than carried on the wire. The ADR-013 stale-run
     * gate cannot separate those either — {@code ifCurrentRun} compares the commit, and a re-run keeps
     * the commit — so this is a pre-existing hole in the same place, not one the run discriminator
     * introduces. Closing it properly means carrying the run on {@code GenerateReview} and back on
     * {@code ReviewGenerated}, the way {@code triggeringCommentId} is carried for follow-ups.
     */
    public static String reviewSlot(String commit, int run) {
        if (run <= ReviewRuns.FIRST_RUN) {
            return commit;
        }
        return commit + "#run" + run;
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

    /**
     * A run's charge identity.
     *
     * <p>{@code attempt} is what distinguishes a genuine second run from a redelivery — the same
     * distinction ReviewRuns draws for reviews, and getting it backwards is the difference between
     * silently-lost money and silently-inflated money. A re-run must NOT reuse the first run's key,
     * or its charges collide and are discarded by ON CONFLICT DO NOTHING; an auto-retry of the same
     * run MUST reuse it, or one paid call is charged twice.
     *
     * <p>The runId already carries its platform, so unlike the review ledger this key cannot
     * confuse two repositories that share a workspace name on different SCMs.
     */
    public static String forRun(String runId, String seq) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("a run charge needs its run id; a blank one would let "
                    + "every unattributed call share one key and collide");
        }
        if (seq == null || seq.isBlank()) {
            throw new IllegalArgumentException("a run charge needs its call sequence within the run");
        }
        // The run id already ends in its attempt (RunIds), so the key carries it once: a second
        // copy here could disagree with the first and pin the disagreement into the ledger.
        return "run:" + runId + ":" + seq;
    }
}

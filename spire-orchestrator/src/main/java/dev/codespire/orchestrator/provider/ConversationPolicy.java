package dev.codespire.orchestrator.provider;

import dev.codespire.contract.review.ConversationLevel;

/** Pure policy for whether the bot answers a reply (spec §4). No I/O — unit-tested as a matrix. */
public final class ConversationPolicy {

    public record ConversationDecision(boolean answer, boolean capReached) {
    }

    private ConversationPolicy() {
    }

    /**
     * @param onFlaggedLine the thread sits on a line the bot has an OPEN finding at, even though the
     * thread is not one the bot started. Commenting where the reviewer raised something is almost
     * always a response to it, so it engages — previously such a comment got silence. The
     * multi-party guard still applies downstream ({@code FollowUpWorker.shouldAnswer}), so the bot
     * drops out the moment a second human joins; this only opens the FIRST comment.
     */
    public static ConversationDecision decide(ConversationLevel level, boolean authorAllowed,
            boolean botIsAuthor, boolean threadIsOurs, boolean botMentioned, boolean onFlaggedLine,
            int priorTurns, int turnCap) {
        boolean eligible = level.answers() && authorAllowed && !botIsAuthor
                && (threadIsOurs || botMentioned || onFlaggedLine);
        if (!eligible) {
            return new ConversationDecision(false, false);
        }
        // An explicit @-mention overrides the cap. The cap exists to stop runaway automated
        // back-and-forth, not to refuse a person who deliberately asked for the bot — so a human who
        // mentions it after the hand-off gets an answer, and the notice can honestly invite that.
        if (priorTurns >= turnCap && !botMentioned) {
            return new ConversationDecision(false, true);
        }
        return new ConversationDecision(true, false);
    }
}

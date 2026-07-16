package dev.codespire.orchestrator.provider;

import dev.codespire.contract.review.ConversationLevel;

/** Pure policy for whether the bot answers a reply (spec §4). No I/O — unit-tested as a matrix. */
public final class ConversationPolicy {

    public record ConversationDecision(boolean answer, boolean capReached) {
    }

    private ConversationPolicy() {
    }

    public static ConversationDecision decide(ConversationLevel level, boolean authorAllowed,
            boolean botIsAuthor, boolean threadIsOurs, boolean botMentioned, int priorTurns, int turnCap) {
        boolean eligible = level.answers() && authorAllowed && !botIsAuthor && (threadIsOurs || botMentioned);
        if (!eligible) {
            return new ConversationDecision(false, false);
        }
        if (priorTurns >= turnCap) {
            return new ConversationDecision(false, true);
        }
        return new ConversationDecision(true, false);
    }
}

package dev.codespire.orchestrator.provider;

import dev.codespire.contract.review.ConversationLevel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationPolicyTest {

    private static final int CAP = 3;

    @Test
    void answersOwnThreadWhenAllowedAndExplainOrAbove() {
        var d = ConversationPolicy.decide(ConversationLevel.EXPLAIN, true, false, true, false, 0, CAP);
        assertTrue(d.answer());
        assertFalse(d.capReached());
    }

    @Test
    void reportOnlyNeverAnswers() {
        assertFalse(ConversationPolicy.decide(ConversationLevel.REPORT_ONLY, true, false, true, false, 0, CAP).answer());
    }

    @Test
    void botSelfIsDropped() {
        assertFalse(ConversationPolicy.decide(ConversationLevel.INTERACTIVE, true, true, true, false, 0, CAP).answer());
    }

    @Test
    void disallowedAuthorIsIgnored() {
        assertFalse(ConversationPolicy.decide(ConversationLevel.INTERACTIVE, false, false, true, false, 0, CAP).answer());
    }

    @Test
    void foreignThreadWithoutMentionIsIgnored() {
        assertFalse(ConversationPolicy.decide(ConversationLevel.INTERACTIVE, true, false, false, false, 0, CAP).answer());
        assertTrue(ConversationPolicy.decide(ConversationLevel.INTERACTIVE, true, false, false, true, 0, CAP).answer());
    }

    @Test
    void turnCapStopsAndFlags() {
        var d = ConversationPolicy.decide(ConversationLevel.INTERACTIVE, true, false, true, false, 3, CAP);
        assertFalse(d.answer());
        assertTrue(d.capReached());
    }
}

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

    /**
     * The cap bounds runaway automated chatter, not a person's direct request. A human who @-mentions
     * the bot after the hand-off gets an answer — which is also what the hand-off notice promises, so
     * this test and that wording move together.
     */
    @Test
    void anExplicitMentionOverridesTheTurnCap() {
        var d = ConversationPolicy.decide(ConversationLevel.INTERACTIVE, true, false, true, true, 3, CAP);
        assertTrue(d.answer(), "a direct @-mention past the cap is still answered");
        assertFalse(d.capReached(), "not a hand-off — no second notice for an overridden turn");
    }

    /** Way past the cap is no different: the override is the mention, not the margin. */
    @Test
    void aMentionOverridesTheCapHoweverFarPastItTheThreadIs() {
        var d = ConversationPolicy.decide(ConversationLevel.INTERACTIVE, true, false, true, true, 99, CAP);
        assertTrue(d.answer());
        assertFalse(d.capReached());
    }

    /** The override does not smuggle in eligibility: a disallowed author stays ignored, mention or not. */
    @Test
    void aMentionPastTheCapStillRespectsTheAuthorAllowlist() {
        var d = ConversationPolicy.decide(ConversationLevel.INTERACTIVE, false, false, true, true, 3, CAP);
        assertFalse(d.answer());
        assertFalse(d.capReached(), "ineligible replies are silent, not a hand-off");
    }

    /** REPORT_ONLY outranks the mention override too — the level gate runs first. */
    @Test
    void aMentionPastTheCapStillRespectsReportOnly() {
        var d = ConversationPolicy.decide(ConversationLevel.REPORT_ONLY, true, false, true, true, 3, CAP);
        assertFalse(d.answer());
        assertFalse(d.capReached());
    }
}

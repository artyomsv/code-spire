package dev.codespire.orchestrator.provider;

import dev.codespire.contract.review.ConversationLevel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationPolicyTest {

    private static final int CAP = 3;

    /** Named so the argument list stays readable: the flags are all booleans in a row. */
    private static ConversationPolicy.ConversationDecision decide(ConversationLevel level,
            boolean authorAllowed, boolean botIsAuthor, boolean threadIsOurs, boolean botMentioned,
            boolean onFlaggedLine, int priorTurns) {
        return ConversationPolicy.decide(level, authorAllowed, botIsAuthor, threadIsOurs,
                botMentioned, onFlaggedLine, priorTurns, CAP);
    }

    @Test
    void answersOwnThreadWhenAllowedAndExplainOrAbove() {
        var d = decide(ConversationLevel.EXPLAIN, true, false, true, false, false, 0);
        assertTrue(d.answer());
        assertFalse(d.capReached());
    }

    @Test
    void reportOnlyNeverAnswers() {
        assertFalse(decide(ConversationLevel.REPORT_ONLY, true, false, true, false, false, 0).answer());
    }

    @Test
    void botSelfIsDropped() {
        assertFalse(decide(ConversationLevel.INTERACTIVE, true, true, true, false, false, 0).answer());
    }

    @Test
    void disallowedAuthorIsIgnored() {
        assertFalse(decide(ConversationLevel.INTERACTIVE, false, false, true, false, false, 0).answer());
    }

    @Test
    void foreignThreadWithoutMentionIsIgnored() {
        assertFalse(decide(ConversationLevel.INTERACTIVE, true, false, false, false, false, 0).answer());
        assertTrue(decide(ConversationLevel.INTERACTIVE, true, false, false, true, false, 0).answer());
    }

    @Test
    void turnCapStopsAndFlags() {
        var d = decide(ConversationLevel.INTERACTIVE, true, false, true, false, false, 3);
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
        var d = decide(ConversationLevel.INTERACTIVE, true, false, true, true, false, 3);
        assertTrue(d.answer(), "a direct @-mention past the cap is still answered");
        assertFalse(d.capReached(), "not a hand-off — no second notice for an overridden turn");
    }

    /** Way past the cap is no different: the override is the mention, not the margin. */
    @Test
    void aMentionOverridesTheCapHoweverFarPastItTheThreadIs() {
        var d = decide(ConversationLevel.INTERACTIVE, true, false, true, true, false, 99);
        assertTrue(d.answer());
        assertFalse(d.capReached());
    }

    /** The override does not smuggle in eligibility: a disallowed author stays ignored, mention or not. */
    @Test
    void aMentionPastTheCapStillRespectsTheAuthorAllowlist() {
        var d = decide(ConversationLevel.INTERACTIVE, false, false, true, true, false, 3);
        assertFalse(d.answer());
        assertFalse(d.capReached(), "ineligible replies are silent, not a hand-off");
    }

    /** REPORT_ONLY outranks the mention override too — the level gate runs first. */
    @Test
    void aMentionPastTheCapStillRespectsReportOnly() {
        var d = decide(ConversationLevel.REPORT_ONLY, true, false, true, true, false, 3);
        assertFalse(d.answer());
        assertFalse(d.capReached());
    }

    // --- a thread the bot does not own, on a line it flagged (item 15) ---

    /**
     * Commenting where the reviewer raised something is almost always a response to it. Before this
     * such a comment got silence, with nothing in the PR explaining why.
     */
    @Test
    void aThreadOnAFlaggedLineEngagesWithoutOwnershipOrAMention() {
        var d = decide(ConversationLevel.INTERACTIVE, true, false, false, false, true, 0);
        assertTrue(d.answer());
        assertFalse(d.capReached());
    }

    /**
     * Unlike a mention, a flagged line grants ELIGIBILITY, not an exemption: the cap still applies, so
     * such a thread gets the hand-off notice rather than unlimited answers.
     */
    @Test
    void aFlaggedLineDoesNotOverrideTheTurnCap() {
        var d = decide(ConversationLevel.INTERACTIVE, true, false, false, false, true, 3);
        assertFalse(d.answer());
        assertTrue(d.capReached(), "eligible but capped — the hand-off notice still posts");
    }

    @Test
    void aFlaggedLineStillRespectsTheLevelTheAllowlistAndTheSelfDrop() {
        assertFalse(decide(ConversationLevel.REPORT_ONLY, true, false, false, false, true, 0).answer());
        assertFalse(decide(ConversationLevel.INTERACTIVE, false, false, false, false, true, 0).answer());
        assertFalse(decide(ConversationLevel.INTERACTIVE, true, true, false, false, true, 0).answer(),
                "the bot's own comment on a flagged line must not start a conversation with itself");
    }
}

package dev.codespire.contract.review;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationLevelTest {

    @Test
    void parseIsCaseInsensitiveAndDefaultsToReportOnly() {
        assertEquals(ConversationLevel.INTERACTIVE, ConversationLevel.parse("interactive"));
        assertEquals(ConversationLevel.EXPLAIN, ConversationLevel.parse("EXPLAIN"));
        assertEquals(ConversationLevel.REPORT_ONLY, ConversationLevel.parse(null));
        assertEquals(ConversationLevel.REPORT_ONLY, ConversationLevel.parse("nonsense"));
    }

    @Test
    void onlyExplainAndInteractiveAnswer() {
        assertFalse(ConversationLevel.REPORT_ONLY.answers());
        assertTrue(ConversationLevel.EXPLAIN.answers());
        assertTrue(ConversationLevel.INTERACTIVE.answers());
    }
}

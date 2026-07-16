package dev.codespire.orchestrator.provider;

import dev.codespire.contract.review.ConversationLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConversationLevelsTest {

    @Test
    void providerValueOverridesGlobalDefault() {
        assertEquals(ConversationLevel.INTERACTIVE,
                ConversationLevels.effective("INTERACTIVE", ConversationLevel.REPORT_ONLY));
        assertEquals(ConversationLevel.EXPLAIN,
                ConversationLevels.effective("explain", ConversationLevel.REPORT_ONLY));
    }

    @Test
    void blankOrNullProviderValueInheritsGlobalDefault() {
        assertEquals(ConversationLevel.EXPLAIN, ConversationLevels.effective(null, ConversationLevel.EXPLAIN));
        assertEquals(ConversationLevel.EXPLAIN, ConversationLevels.effective("  ", ConversationLevel.EXPLAIN));
    }
}

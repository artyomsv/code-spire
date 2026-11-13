package dev.codespire.contract.llm;

import dev.codespire.contract.review.TokenType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which key vendor pays a harness, whether it may use a subscription, and whether it may build (harness reviews 3b).
 * It restricts the harnesses it lists only: an unlisted name has no adapter, so the worker refuses it before it runs.
 */
class HarnessPolicyTest {

    @Test
    void codexPaysWithAnOpenAiKey() {
        assertEquals(Optional.of("openai"), HarnessPolicy.keyKind("codex"));
    }

    @Test
    void claudeCodePaysWithAnAnthropicKey() {
        assertEquals(Optional.of("anthropic"), HarnessPolicy.keyKind("claude_code"));
    }

    @Test
    void anUnlistedHarnessIsNotConstrained() {
        assertEquals(Optional.empty(), HarnessPolicy.keyKind("TEST-harness"));
        assertTrue(HarnessPolicy.allowsSubscription("TEST-harness"));
        assertTrue(HarnessPolicy.allowsBuilds("TEST-harness"));
    }

    @Test
    void claudeCodeMayNotUseASubscription() {
        assertFalse(HarnessPolicy.allowsSubscription("claude_code"));
        assertTrue(HarnessPolicy.allowsSubscription("codex"));
    }

    @Test
    void claudeCodeMayNotBuild() {
        assertFalse(HarnessPolicy.allowsBuilds("claude_code"));
        assertTrue(HarnessPolicy.allowsBuilds("codex"));
    }

    /**
     * The worker resolves an adapter by an uppercased name, so an agent-image key spelled in another case still runs
     * Claude Code. The policy must read the same name the worker does, or such a key escapes every rule above.
     */
    @Test
    void aHarnessNameIsReadWhateverItsCaseAndSpacing() {
        assertEquals(Optional.of("anthropic"), HarnessPolicy.keyKind("Claude_Code"));
        assertFalse(HarnessPolicy.allowsSubscription("CLAUDE_CODE"));
        assertFalse(HarnessPolicy.allowsBuilds(" claude_code "));
    }

    @Test
    void claudeCodeReportsNoReasoningBucket() {
        assertEquals(Set.of(TokenType.INPUT, TokenType.CACHED_INPUT, TokenType.CACHE_WRITE, TokenType.OUTPUT),
                HarnessTokenReport.reportedBy("claude_code"));
    }
}

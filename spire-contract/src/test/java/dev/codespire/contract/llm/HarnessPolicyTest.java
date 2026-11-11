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

    @Test
    void claudeCodeReportsNoReasoningBucket() {
        assertEquals(Set.of(TokenType.INPUT, TokenType.CACHED_INPUT, TokenType.CACHE_WRITE, TokenType.OUTPUT),
                HarnessTokenReport.reportedBy("claude_code"));
    }
}

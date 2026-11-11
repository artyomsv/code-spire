package dev.codespire.harness.claudecode;

import dev.codespire.harness.HarnessInvocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// NOT ALLOWED — kept for history only. Anthropic's terms forbid a third-party product to route requests
// through a Claude subscription or to hold claude.ai tokens:
// https://code.claude.com/docs/en/legal-and-compliance (read 2026-11-08). Claude Code pays with an API key.
/** A sign-in reaching the Claude Code arm is refused, never run and never swapped for a key. */
class ClaudeSubscriptionIsDisabledTest {

    private final ClaudeCodeAdapter adapter = new ClaudeCodeAdapter();

    private static HarnessInvocation signedIn() {
        return ClaudeCodeInvocations.review(Map.of(HarnessInvocation.SIGN_IN, ClaudeCodeModelProxy.PLACEHOLDER));
    }

    @Test
    void aSignInIsRefusedByCommand() {
        assertEquals("subscription_not_allowed",
                assertThrows(IllegalStateException.class, () -> adapter.command(signedIn())).getMessage());
    }

    @Test
    void aSignInIsRefusedByEnvironment() {
        assertEquals("subscription_not_allowed",
                assertThrows(IllegalStateException.class, () -> adapter.environment(signedIn())).getMessage());
    }

    /** Named before every other refusal: a sign-in on a direct run that loads guidance is still a sign-in. */
    @Test
    void aSignInIsRefusedFirst() {
        HarnessInvocation direct = ClaudeCodeInvocations.direct(Map.of(HarnessInvocation.SIGN_IN, "TEST-sign-in"));

        assertEquals("subscription_not_allowed",
                assertThrows(IllegalStateException.class, () -> adapter.command(direct)).getMessage());
    }

    @Test
    void aSignInHasNoRoute() {
        assertTrue(adapter.credentialRoute(signedIn()).isEmpty());
    }
}

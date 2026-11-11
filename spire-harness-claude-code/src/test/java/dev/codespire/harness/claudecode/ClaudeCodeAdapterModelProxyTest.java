package dev.codespire.harness.claudecode;

import dev.codespire.harness.CredentialRoute;
import dev.codespire.harness.HarnessInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Claude Code behind the unit's model proxy: the agent holds a placeholder, the proxy the key. */
class ClaudeCodeAdapterModelProxyTest {

    private static final String REAL_LOOKING = "TEST-real-looking-secret-0123456789";

    private final ClaudeCodeAdapter adapter = new ClaudeCodeAdapter();

    @Test
    void aProxiedRunHoldsOnlyAPlaceholder() {
        HarnessInvocation review = ClaudeCodeInvocations.review(Map.of(HarnessInvocation.CREDENTIAL, REAL_LOOKING));

        Map<String, String> env = adapter.environment(review);
        assertEquals(Map.of("ANTHROPIC_API_KEY", "TEST-PLACEHOLDER", "ANTHROPIC_BASE_URL", "http://spire-model:8080",
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"), env);
        assertFalse(env.containsValue(REAL_LOOKING), env.toString());
        assertFalse(String.join("\u0000", adapter.command(review)).contains(REAL_LOOKING));
    }

    /** A name the adapter sets itself, arriving in the credential map, is a collision and is refused. */
    @Test
    void aCredentialNamedLikeTheAdaptersOwnVariableIsRefused() {
        HarnessInvocation review = ClaudeCodeInvocations.review(Map.of("ANTHROPIC_API_KEY", REAL_LOOKING));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> adapter.environment(review));
    }

    @Test
    void theRouteIsTheMessagesPathOnly() {
        assertEquals(Optional.of(new CredentialRoute("https://api.anthropic.com", CredentialRoute.Injection.X_API_KEY,
                        Set.of("/v1/messages"), List.of())),
                adapter.credentialRoute(ClaudeCodeInvocations.review()));
    }

    @Test
    void aDirectRunHasNoRoute() {
        HarnessInvocation direct = ClaudeCodeInvocations.direct(Map.of(HarnessInvocation.CREDENTIAL, "TEST-key"));

        assertTrue(adapter.credentialRoute(direct).isEmpty());
        assertTrue(adapter.credentialRoute(direct.withProjectGuidance(HarnessInvocation.ProjectGuidance.IGNORED))
                .isEmpty());
    }
}

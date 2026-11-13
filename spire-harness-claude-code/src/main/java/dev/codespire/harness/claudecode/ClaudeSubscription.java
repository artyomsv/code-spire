package dev.codespire.harness.claudecode;

import dev.codespire.harness.HarnessInvocation;

import java.util.Map;

// NOT ALLOWED — kept for history only. Anthropic's terms forbid a third-party product to route requests
// through a Claude subscription or to hold claude.ai tokens:
// https://code.claude.com/docs/en/legal-and-compliance (read 2026-11-08). Claude Code pays with an API key.
/**
 * The Claude subscription branch, which only refuses. A sign-in that reaches this arm by any door is refused before a
 * container exists; it is never run and never swapped for a key. There is no sign-in flow, no token variable and no
 * login step here, by decision (2026-11-08).
 */
final class ClaudeSubscription {

    /** The refusal's reason, the same name the orchestrator refuses with at its own doors. */
    static final String NOT_ALLOWED = "subscription_not_allowed";

    private ClaudeSubscription() {
    }

    // NOT ALLOWED — kept for history only. Anthropic's terms forbid a third-party product to route requests
    // through a Claude subscription or to hold claude.ai tokens:
    // https://code.claude.com/docs/en/legal-and-compliance (read 2026-11-08). Claude Code pays with an API key.
    /** @throws IllegalStateException {@link #NOT_ALLOWED} when the credentials hold a sign-in */
    static void refuseIfSignIn(Map<String, String> credentials) {
        if (credentials.containsKey(HarnessInvocation.SIGN_IN)) {
            throw new IllegalStateException(NOT_ALLOWED);
        }
    }
}

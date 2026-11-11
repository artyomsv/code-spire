package dev.codespire.harness.claudecode;

import java.util.Map;

/** Stub: the disabled subscription branch. */
final class ClaudeSubscription {

    static final String NOT_ALLOWED = "subscription_not_allowed";

    private ClaudeSubscription() {
    }

    static void refuseIfSignIn(Map<String, String> credentials) {
        throw new UnsupportedOperationException("refuseIfSignIn");
    }
}

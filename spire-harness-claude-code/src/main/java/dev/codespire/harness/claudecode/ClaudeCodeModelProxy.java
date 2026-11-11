package dev.codespire.harness.claudecode;

import dev.codespire.harness.CredentialRoute;
import dev.codespire.harness.HarnessInvocation;

import java.util.Map;
import java.util.Optional;

/** Stub: Claude Code behind the unit's model proxy. */
final class ClaudeCodeModelProxy {

    static final String PLACEHOLDER = "TEST-PLACEHOLDER";

    private ClaudeCodeModelProxy() {
    }

    static Map<String, String> environment(HarnessInvocation invocation) {
        throw new UnsupportedOperationException("environment");
    }

    static Optional<CredentialRoute> route(HarnessInvocation invocation) {
        throw new UnsupportedOperationException("route");
    }
}

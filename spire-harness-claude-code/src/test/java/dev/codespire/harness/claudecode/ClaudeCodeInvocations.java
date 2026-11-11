package dev.codespire.harness.claudecode;

import dev.codespire.harness.HarnessInvocation;

import java.time.Duration;
import java.util.Map;

/** Invocations for the tests: the shape a review run is given, and the shapes the adapter refuses. */
final class ClaudeCodeInvocations {

    static final String MODEL = "claude-haiku-5-5";

    static final String PROMPT = "TEST-review the change";

    private ClaudeCodeInvocations() {
    }

    /** What the run worker hands a review run: placeholders, behind the model proxy, no repository guidance. */
    static HarnessInvocation review(Map<String, String> credentials) {
        return direct(credentials).proxied().withProjectGuidance(HarnessInvocation.ProjectGuidance.IGNORED);
    }

    static HarnessInvocation review() {
        return review(Map.of(HarnessInvocation.CREDENTIAL, ClaudeCodeModelProxy.PLACEHOLDER));
    }

    /** The agent holds the credential and loads the repository's guidance: every default of the record. */
    static HarnessInvocation direct(Map<String, String> credentials) {
        return new HarnessInvocation("run_TEST", PROMPT, "/workspace", MODEL, credentials, Duration.ofMinutes(30));
    }
}

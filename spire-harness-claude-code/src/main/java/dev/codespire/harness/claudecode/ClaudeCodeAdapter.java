package dev.codespire.harness.claudecode;

import dev.codespire.harness.CredentialRoute;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessCapabilities;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.HarnessType;
import dev.codespire.harness.PromptDelivery;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TerminalOutcome;
import dev.codespire.harness.UsageReport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Stub: drives Claude Code for review runs behind the model proxy. */
public final class ClaudeCodeAdapter implements HarnessAdapter {

    static final List<String> RUN_FLAGS = List.of();

    static final List<String> GUARDED_FLAGS = List.of();

    @Override
    public HarnessType type() {
        throw new UnsupportedOperationException("type");
    }

    @Override
    public HarnessCapabilities capabilities() {
        throw new UnsupportedOperationException("capabilities");
    }

    @Override
    public PromptDelivery promptDelivery() {
        throw new UnsupportedOperationException("promptDelivery");
    }

    @Override
    public List<String> command(HarnessInvocation invocation) {
        throw new UnsupportedOperationException("command");
    }

    @Override
    public Map<String, String> environment(HarnessInvocation invocation) {
        throw new UnsupportedOperationException("environment");
    }

    @Override
    public Optional<CredentialRoute> credentialRoute(HarnessInvocation invocation) {
        throw new UnsupportedOperationException("credentialRoute");
    }

    @Override
    public Set<String> egressHosts(HarnessInvocation invocation) {
        throw new UnsupportedOperationException("egressHosts");
    }

    @Override
    public Optional<RunEvent> parse(String line) {
        throw new UnsupportedOperationException("parse");
    }

    @Override
    public List<RunEvent> parseAll(String line) {
        throw new UnsupportedOperationException("parseAll");
    }

    @Override
    public TerminalOutcome classify(int exitCode, RunEventSummary seen) {
        throw new UnsupportedOperationException("classify");
    }

    @Override
    public UsageReport usage(RunEventSummary seen) {
        throw new UnsupportedOperationException("usage");
    }
}

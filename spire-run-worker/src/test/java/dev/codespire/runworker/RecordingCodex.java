package dev.codespire.runworker;

import dev.codespire.harness.codex.CodexAdapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Codex, recording every invocation it is handed, so what the arm SAW is asserted, not only what it returned. */
final class RecordingCodex implements dev.codespire.harness.HarnessAdapter {
    private final CodexAdapter codex = new CodexAdapter();
    final List<dev.codespire.harness.HarnessInvocation> seen = new java.util.ArrayList<>();

    private dev.codespire.harness.HarnessInvocation saw(dev.codespire.harness.HarnessInvocation invocation) {
        seen.add(invocation);
        return invocation;
    }

    @Override public dev.codespire.harness.HarnessType type() { return codex.type(); }
    @Override public dev.codespire.harness.HarnessCapabilities capabilities() { return codex.capabilities(); }
    @Override public dev.codespire.harness.PromptDelivery promptDelivery() { return codex.promptDelivery(); }
    @Override public List<String> command(dev.codespire.harness.HarnessInvocation i) { return codex.command(saw(i)); }
    @Override public Map<String, String> environment(dev.codespire.harness.HarnessInvocation i) {
        return codex.environment(saw(i));
    }
    @Override public java.util.Set<String> egressHosts(dev.codespire.harness.HarnessInvocation i) {
        return codex.egressHosts(saw(i));
    }
    @Override public Optional<dev.codespire.harness.CredentialRoute> credentialRoute(
            dev.codespire.harness.HarnessInvocation i) {
        return codex.credentialRoute(saw(i));
    }
    @Override public Optional<dev.codespire.harness.RunEvent> parse(String line) { return codex.parse(line); }
    @Override public dev.codespire.harness.TerminalOutcome classify(int exit, dev.codespire.harness.RunEventSummary s) {
        return codex.classify(exit, s);
    }
    @Override public dev.codespire.harness.UsageReport usage(dev.codespire.harness.RunEventSummary s) {
        return codex.usage(s);
    }
    @Override public Optional<SignInFlow> signIn() { return codex.signIn(); }
}

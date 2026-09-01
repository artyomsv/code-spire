package dev.codespire.runworker;

import dev.codespire.harness.FailureCause;
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

/**
 * A harness whose "model" is a shell script, so the whole chain — clone, sandbox, prompt on stdin,
 * handoff, gate, push — runs with no network model and no spend. Reports no usage, which must
 * arrive at the orchestrator as UNKNOWN (a null map), never as zero.
 */
final class ScriptHarness implements HarnessAdapter {

    private final String script;

    ScriptHarness(String script) {
        this.script = script;
    }

    @Override
    public HarnessType type() {
        return HarnessType.CODEX;
    }

    @Override
    public HarnessCapabilities capabilities() {
        return new HarnessCapabilities(false, false, false, false, false);
    }

    /** STDIN, so the test proves the prompt reaches the harness through the entrypoint contract. */
    @Override
    public PromptDelivery promptDelivery() {
        return PromptDelivery.STDIN;
    }

    @Override
    public List<String> command(HarnessInvocation invocation) {
        return List.of("sh", "-c", script);
    }

    @Override
    public Map<String, String> environment(HarnessInvocation invocation) {
        return Map.of();
    }

    @Override
    public Optional<RunEvent> parse(String line) {
        return Optional.empty();
    }

    @Override
    public TerminalOutcome classify(int exitCode, RunEventSummary seen) {
        return exitCode == 0
                ? TerminalOutcome.success("script exited 0")
                : TerminalOutcome.failure(FailureCause.PROVIDER_ERROR, "script exited " + exitCode);
    }

    @Override
    public UsageReport usage(RunEventSummary seen) {
        return UsageReport.unknown();
    }
}

package dev.codespire.harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drives one agent harness. Two contract rules that are not obvious:
 *
 * <ul>
 *   <li>{@link #usage} returning empty means UNKNOWN, never zero.</li>
 *   <li>{@link #command} returns argv, never a shell string — a prompt is untrusted text.</li>
 * </ul>
 */
public interface HarnessAdapter {

    HarnessType type();

    HarnessCapabilities capabilities();

    List<String> command(HarnessInvocation invocation);

    Map<String, String> environment(HarnessInvocation invocation);

    /** @return one normalized event, or empty when the line carries nothing the domain models. */
    Optional<RunEvent> parse(String line);

    TerminalOutcome classify(int exitCode, RunEventSummary seen);

    Optional<UsageReport> usage(RunEventSummary seen);
}

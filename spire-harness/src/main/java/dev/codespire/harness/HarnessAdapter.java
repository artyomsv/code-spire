package dev.codespire.harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drives one agent harness. Two contract rules that are not obvious:
 *
 * <ul>
 *   <li>{@link #usage} answers {@link UsageReport#unknown()} when the harness did not say. It is
 *       NOT Optional: two ways to spell one fact gives a caller an {@code orElse(0L)} door that
 *       reads as careful code and fabricates the very zero ADR-023 exists to prevent.</li>
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

    /**
     * @return what the run consumed. Never null; {@link UsageReport#unknown()} when the harness
     *         reported nothing this adapter recognises — never a zeroed report.
     */
    UsageReport usage(RunEventSummary seen);
}

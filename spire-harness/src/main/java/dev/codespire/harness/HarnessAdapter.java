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

    /**
     * How this arm receives the prompt. The worker must honour it: a {@link PromptDelivery#STDIN}
     * arm whose stdin nobody writes runs, produces nothing and exits cleanly.
     */
    PromptDelivery promptDelivery();

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

    /**
     * How an OPERATOR signs this arm in, for a run paid by a subscription rather than a key
     * (M3.5 part F).
     *
     * <p>Here rather than in the worker for the reason {@link #command} is here: which binary to run
     * and where it writes are this vendor's knowledge, and a worker that held them would be a core
     * module naming one arm — which the provider-neutrality guard fails the build over, correctly.
     *
     * <p>Empty for an arm that has no such flow, and then this deployment refuses a subscription
     * sign-in for it rather than starting a container that cannot do anything.
     */
    default Optional<SignInFlow> signIn() {
        return Optional.empty();
    }

    /**
     * @param command argv for a trusted unit, never a shell string
     * @param resultPath where the arm writes the finished sign-in, read out of the stopped container
     *     rather than from its output — a credential on stdout is a credential in the daemon's log
     */
    record SignInFlow(List<String> command, String resultPath) {
        public SignInFlow {
            command = List.copyOf(command);
            if (command.isEmpty()) throw new IllegalArgumentException("a sign-in flow needs a command");
            if (resultPath == null || !resultPath.startsWith("/")) {
                throw new IllegalArgumentException("the result path must be absolute inside the unit");
            }
        }
    }
}

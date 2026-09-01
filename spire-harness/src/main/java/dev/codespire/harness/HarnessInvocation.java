package dev.codespire.harness;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * One invocation of a harness. {@code credentials} are injected into the child process's
 * environment and MUST NOT be logged or echoed into a {@link RunEvent}.
 *
 * <p>That rule is enforced rather than asserted: a record's generated {@code toString()} prints
 * every component, so the obvious {@code log.info("dispatching {}", invocation)} would put the
 * machine-account token in a log line. The override below prints credential NAMES, which are
 * useful diagnostics, and never their values.
 */
public record HarnessInvocation(String runId, String prompt, String workspacePath,
                                String model, Map<String, String> credentials,
                                Duration wallClock) {

    public HarnessInvocation {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(workspacePath, "workspacePath");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(wallClock, "wallClock");
        credentials = Map.copyOf(Objects.requireNonNull(credentials, "credentials"));
    }

    @Override
    public String toString() {
        return "HarnessInvocation[runId=" + runId
                + ", model=" + model
                + ", workspacePath=" + workspacePath
                + ", wallClock=" + wallClock
                + ", credentials=" + credentials.keySet() + " (values redacted)"
                + ", promptChars=" + prompt.length() + "]";
    }
}

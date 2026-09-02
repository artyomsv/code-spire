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

    /**
     * The key under which the worker supplies the model credential in {@link #credentials()}. The
     * name the arm's own process reads it as ({@code OPENAI_API_KEY} for Codex, something else for
     * the next arm) is the arm's knowledge: {@link HarnessAdapter#environment} translates, and core
     * never spells a vendor's variable.
     */
    public static final String CREDENTIAL = "HARNESS_CREDENTIAL";

    public HarnessInvocation {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(wallClock, "wallClock");
        credentials = Map.copyOf(Objects.requireNonNull(credentials, "credentials"));

        // model and workspacePath both become argv elements, so their shape is checked here once
        // rather than by every arm. Neither is an injection risk on its own — argv is a list, so
        // "--model <value>" consumes exactly one element and nothing re-splits it — but both are
        // control:
        //
        //   model         ADR-036 says a repository may never select the model endpoint. Nothing
        //                 enforces that yet; when the per-repo override ladder is built, free text
        //                 lands here. A blank or flag-shaped value is a configuration fault that
        //                 should fail before a container starts, not as a CLI parse error inside it.
        //   workspacePath chooses the directory the agent operates in, and the agent runs
        //                 unconfined because the container is the boundary (ADR-039). A relative
        //                 path resolves against whatever the child's working directory happens to
        //                 be; "/" would hand it everything mounted.
        requireArgumentSafe(model, "model");
        requireArgumentSafe(workspacePath, "workspacePath");
        if (!workspacePath.startsWith("/")) {
            throw new IllegalArgumentException("workspacePath must be absolute, was: " + workspacePath);
        }
    }

    private static void requireArgumentSafe(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.startsWith("-")) {
            throw new IllegalArgumentException(field + " must not look like a flag, was: " + value);
        }
        if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " must not contain a NUL or a newline");
        }
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

package dev.codespire.runtime.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * What the init container said about its own failure, as {@code ": CAUSE: detail"}, or empty.
 *
 * <p>{@code CloneMain} writes one JSON line on stdout — {@code {"event":"failed","cause":…,
 * "detail":…}} — already scrubbed of the git credential by {@code OutcomeWriter}. <b>Nothing
 * read it.</b> Every init failure reached the run row as "exit 1", and the cause lived only in
 * {@code docker logs} on the host that ran it: a checkout conflict on a pull request's own
 * branch took a container log to diagnose, on a screen built to answer exactly that question.
 *
 * <p>Best effort by construction. The exit code is the fact; this is the explanation, and a
 * failure to read it must never replace a real failure with a reading error — so everything
 * here is caught and the caller still gets "exit N".
 */
final class InitFailureReport {

    /** The runtime's category, so this line reads in its log as it always has. */
    private static final System.Logger LOG = System.getLogger(DockerRunRuntime.class.getName());

    /** Reads the init container's own failure line; never writes, so one instance is enough. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A failure_detail is read in a table cell, not scrolled. */
    private static final int MAX_INIT_REPORT_CHARS = 500;

    /** A finished container's log, line by line: {@link DockerRunRuntime#logLinesOf}. */
    @FunctionalInterface
    interface LogLines {
        List<String> of(String containerId) throws IOException;
    }

    private InitFailureReport() {
    }

    static String of(String containerId, LogLines logs) {
        try {
            List<String> log = logs.of(containerId);
            String failure = lastFailureLine(log);
            if (failure.isEmpty()) {
                // No structured line: the process died before it could write one. Measured, not
                // hypothetical — a JGitInternalException escaped CloneMain's catch list and the
                // whole report was a stack trace on stderr. Its FIRST line carries the exception
                // and its message; every line after it is a stack frame.
                return log.isEmpty() ? "" : clipToOneLine(log.getFirst());
            }
            JsonNode node = JSON.readTree(failure);
            String cause = node.path("cause").asText("");
            String detail = node.path("detail").asText("");
            if (cause.isEmpty() && detail.isEmpty()) {
                return "";
            }
            return clipToOneLine(cause + (detail.isEmpty() ? "" : ": " + detail));
        } catch (RuntimeException | IOException unreadable) {
            LOG.log(System.Logger.Level.DEBUG,
                    "could not read why the init container " + containerId + " failed", unreadable);
            return "";
        }
    }

    /** One line, bounded: this becomes a run's failure_detail and is rendered in a table cell. */
    private static String clipToOneLine(String report) {
        String line = report.replaceAll("\\R", " ").strip();
        return line.isEmpty() ? ""
                : ": " + line.substring(0, Math.min(line.length(), MAX_INIT_REPORT_CHARS));
    }

    /** The structured report, or empty when the process died before writing one. */
    private static String lastFailureLine(List<String> log) {
        String last = "";
        for (String line : log) {
            if (line.startsWith("{") && line.contains("\"failed\"")) {
                last = line;
            }
        }
        return last;
    }
}

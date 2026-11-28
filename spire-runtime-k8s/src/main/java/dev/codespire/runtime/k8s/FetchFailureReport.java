package dev.codespire.runtime.k8s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * What the fetch container said about its own failure, as {@code ": CAUSE: detail"}, or empty. The clone writes one
 * JSON line {@code {"event":"failed","cause":...,"detail":...}}, already scrubbed of the credential. The text has the
 * Docker arm's shape (its {@code InitFailureReport}), so the run worker classifies both arms' clone failures alike.
 *
 * <p>Best effort: the exit code is the fact and this is the explanation, so a line that cannot be read gives empty.
 */
final class FetchFailureReport {

    /** A failure detail is read in a table cell, not scrolled. */
    static final int MAX_REPORT_CHARS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private FetchFailureReport() {
    }

    static String of(List<String> log) {
        String failure = lastFailureLine(log);
        if (failure.isEmpty()) {
            // No structured line: the process died before it wrote one, and its first line names the exception.
            return log.isEmpty() ? "" : oneLine(log.getFirst());
        }
        try {
            JsonNode node = JSON.readTree(failure);
            String cause = node.path("cause").asText("");
            String detail = node.path("detail").asText("");
            if (cause.isEmpty() && detail.isEmpty()) {
                return "";
            }
            return oneLine(cause + (detail.isEmpty() ? "" : ": " + detail));
        } catch (JsonProcessingException unreadable) {
            return "";
        }
    }

    /** One line, bounded, after a colon; empty stays empty. */
    static String oneLine(String report) {
        String line = report.replaceAll("\\R", " ").strip();
        return line.isEmpty() ? "" : ": " + line.substring(0, Math.min(line.length(), MAX_REPORT_CHARS));
    }

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

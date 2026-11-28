package dev.codespire.runtime.k8s;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * A container's log stream, delivered as whole lines. The API server does not align its chunks to lines, so a
 * partial line is carried to the next read. The carry is bounded: the agent writes this stream, so an unterminated
 * line would be an allocation it controls on the worker every run shares. Past {@link #MAX_LINE_CHARS} the line is
 * delivered cut, with a marker, and the rest is dropped up to the next newline, as the Docker arm does.
 */
final class PodLogs {

    static final int MAX_LINE_CHARS = 64 * 1024;

    static final String CUT_MARKER = "[spire: a log line exceeded " + MAX_LINE_CHARS
            + " characters; the remainder was dropped]";

    private static final int READ_CHARS = 8192;

    private PodLogs() {
    }

    static void follow(InputStream log, Consumer<String> lines) {
        Reader reader = new InputStreamReader(log, StandardCharsets.UTF_8);
        StringBuilder carry = new StringBuilder();
        boolean dropping = false;
        char[] buffer = new char[READ_CHARS];
        try {
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    dropping = take(buffer[index], carry, dropping, lines);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading a container's log stream", e);
        }
        if (!dropping) {
            emit(carry, lines);
        }
    }

    /** @return whether the rest of the current line is being dropped */
    private static boolean take(char next, StringBuilder carry, boolean dropping, Consumer<String> lines) {
        if (next == '\n') {
            if (!dropping) {
                emit(carry, lines);
            }
            carry.setLength(0);
            return false;
        }
        if (dropping) {
            return true;
        }
        carry.append(next);
        if (carry.length() <= MAX_LINE_CHARS) {
            return false;
        }
        lines.accept(carry.substring(0, MAX_LINE_CHARS));
        lines.accept(CUT_MARKER);
        carry.setLength(0);
        return true;
    }

    private static void emit(StringBuilder carry, Consumer<String> lines) {
        int end = carry.length();
        if (end > 0 && carry.charAt(end - 1) == '\r') {
            end--;
        }
        if (end > 0) {
            lines.accept(carry.substring(0, end));
        }
        carry.setLength(0);
    }
}

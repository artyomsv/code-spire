package dev.codespire.modelproxy;

import com.sun.net.httpserver.HttpExchange;

import java.io.PrintStream;
import java.net.URI;

/**
 * One request's report line, {@code spire-model <METHOD> <path> -> <outcome>}, written once.
 *
 * <p>The path is the normalized path once the guard allowed it, and otherwise the target as the agent
 * sent it, without userinfo and without the query. Both are reduced to visible ASCII and bounded, so
 * a request cannot forge or flood a report line.
 */
final class RequestLine {

    private static final int MAX_LOGGED_CHARACTERS = 256;

    private final PrintStream log;
    private final String method;
    private String path;
    private boolean written;

    RequestLine(HttpExchange exchange, PrintStream log) {
        this.log = log;
        this.method = printable(exchange.getRequestMethod());
        this.path = shown(exchange.getRequestURI());
    }

    void normalizedPath(String allowed) {
        this.path = allowed;
    }

    String path() {
        return path;
    }

    void write(String outcome) {
        if (written) {
            return;
        }
        written = true;
        log.println("spire-model " + method + " " + path + " -> " + outcome);
        log.flush();
    }

    private static String shown(URI target) {
        StringBuilder shown = new StringBuilder();
        if (target.getScheme() != null) {
            shown.append(target.getScheme()).append(':');
        }
        String authority = target.getRawAuthority();
        if (authority != null) {
            shown.append("//").append(authority.substring(authority.lastIndexOf('@') + 1));
        }
        if (target.getRawPath() != null) {
            shown.append(target.getRawPath());
        }
        return printable(shown.toString());
    }

    private static String printable(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && i < MAX_LOGGED_CHARACTERS; i++) {
            char c = value.charAt(i);
            out.append(c > ' ' && c < 0x7f ? c : '?');
        }
        return out.toString();
    }
}

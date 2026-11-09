package dev.codespire.modelproxy;

import java.io.PrintStream;

/**
 * One request's report line, {@code spire-model <METHOD> <route> -> <outcome>}, written once.
 *
 * <p>The route is never the target the agent sent. For an allowed request it is what the route declares and the guard
 * matched ({@link PathGuard#logged}): an exact path, or a prefix followed by {@code *}. For a refused one it is
 * {@link #REFUSED_TARGET}, whatever the target was: a refused path, an absolute form with userinfo, a CONNECT. The
 * forwarded target is unaffected. The method and the route are reduced to visible ASCII and bounded here, at the one
 * place a line is written, so no request can forge, lengthen or flood a line; the container's log is rotated as well
 * ({@code spire-runtime-docker}'s {@code ModelProxyContainer}).
 */
final class RequestLine {

    /** What a refused request's line names in place of its target. */
    static final String REFUSED_TARGET = "<refused>";

    private static final int MAX_METHOD_CHARACTERS = 16;

    private static final int MAX_ROUTE_CHARACTERS = 256;

    private final PrintStream log;
    private final String method;
    private String route = REFUSED_TARGET;
    private boolean written;

    RequestLine(String method, PrintStream log) {
        this.log = log;
        this.method = method;
    }

    /** The route the guard allowed the request by; until then the line names {@link #REFUSED_TARGET}. */
    void route(String loggedRoute) {
        this.route = loggedRoute;
    }

    void write(String outcome) {
        if (written) {
            return;
        }
        written = true;
        log.println("spire-model " + printable(method, MAX_METHOD_CHARACTERS) + " "
                + printable(route, MAX_ROUTE_CHARACTERS) + " -> " + outcome);
        log.flush();
    }

    private static String printable(String value, int maxCharacters) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && i < maxCharacters; i++) {
            char c = value.charAt(i);
            out.append(c > ' ' && c < 0x7f ? c : '?');
        }
        return out.toString();
    }
}

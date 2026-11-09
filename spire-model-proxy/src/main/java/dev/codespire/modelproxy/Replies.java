package dev.codespire.modelproxy;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** The answers the proxy writes itself, each with its report line written first. */
final class Replies {

    static final String REFUSED = "refused";
    static final String ABORTED = "aborted";
    static final int BAD_GATEWAY = 502;

    static final byte[] REFUSED_REQUEST_BODY = json("spire-model: request refused");
    static final byte[] TOO_LARGE_BODY = json("spire-model: request body too large");
    static final byte[] BUSY_BODY = json("spire-model: too many requests at once");

    private Replies() {
    }

    /** A fixed answer the proxy gives without contacting the vendor, and the outcome its report line names. */
    record Answer(int status, String outcome, byte[] body) {

        /** The agent sent a body over {@link Limits#maxRequestBodyBytes()}. */
        static final Answer TOO_LARGE = new Answer(413, "413", TOO_LARGE_BODY);

        /** {@link Limits#maxConcurrentRequests()} were already in flight. */
        static final Answer BUSY = new Answer(503, "503", BUSY_BODY);

        /** A request the proxy refuses to forward at all, answered with {@code status}. */
        static Answer refused(int status) {
            return new Answer(status, REFUSED, REFUSED_REQUEST_BODY);
        }
    }

    /**
     * A fixed JSON answer, flushed at once: closing the exchange next may wait on the rest of an unread request body,
     * and the agent should not wait with it.
     */
    static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.flush();
    }

    /** A 502 for a request that failed before any of its response was sent. */
    static void badGateway(HttpExchange exchange, RequestLine line) {
        line.write(String.valueOf(BAD_GATEWAY));
        try {
            exchange.getResponseHeaders().clear();
            exchange.sendResponseHeaders(BAD_GATEWAY, -1);
        } catch (IOException agentGone) {
            // The agent hung up; there is no one left to answer, and the line already says 502.
        }
    }

    /**
     * Ends a response whose headers were already sent, without ending it cleanly.
     *
     * <p>Closing the exchange would write the chunked body's terminating chunk, and the agent would read a truncated
     * answer as a complete one. Leaving it unclosed and throwing out of the handler makes the JDK server drop the
     * connection instead, so the agent sees the stream break. The exception carries no message and no stack trace; the
     * server logs it at TRACE only.
     */
    static AbortedResponse abort(RequestLine line) {
        line.write(ABORTED);
        throw new AbortedResponse();
    }

    /** Thrown out of the handler to make the server drop the connection; see {@link #abort}. */
    static final class AbortedResponse extends RuntimeException {

        AbortedResponse() {
            super(null, null, false, false);
        }
    }

    private static byte[] json(String message) {
        return ("{\"error\":{\"message\":\"" + message + "\"}}").getBytes(StandardCharsets.UTF_8);
    }
}

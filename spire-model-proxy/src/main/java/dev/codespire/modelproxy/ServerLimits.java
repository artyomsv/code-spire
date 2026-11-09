package dev.codespire.modelproxy;

import java.util.Map;

/**
 * The agent-facing JDK server's own bounds, which it reads from system properties once, when its first server is
 * created. They cover what happens before the proxy's handler runs, or under it:
 *
 * <ul>
 *   <li>{@code sun.net.httpserver.maxReqTime} 30 s: a request's head and body must have arrived within 30 seconds of
 *       its first byte, or the server closes the connection. An incomplete head, a stalled body and a body trickled
 *       one byte at a time all end there. A review's request body is at most {@link Limits#maxRequestBodyBytes()} on
 *       the unit's own network, which takes milliseconds.</li>
 *   <li>{@code sun.net.httpserver.maxRspTime} 3600 s: a response may take an hour in all, the
 *       {@link Limits#totalResponse()} bound seen from the agent's side, so an agent that stops reading frees its
 *       connection too.</li>
 *   <li>{@code jdk.httpserver.maxConnections} 32: past that the server accepts no connection until one closes; four
 *       times {@link Limits#maxConcurrentRequests()}, so idle keep-alive connections never crowd a request out.</li>
 *   <li>{@code sun.net.httpserver.maxReqHeaders} 100 and {@code sun.net.httpserver.maxReqHeaderSize} 64 KiB: Codex
 *       sends a dozen short headers.</li>
 * </ul>
 *
 * <p>A property already set wins, so a test JVM can shorten the request time (it measures seconds, and the
 * production 30 would make every test wait 30). {@code main} applies these before anything creates a server.
 */
final class ServerLimits {

    static final Map<String, String> PRODUCTION = Map.of(
            "sun.net.httpserver.maxReqTime", "30",
            "sun.net.httpserver.maxRspTime", "3600",
            "jdk.httpserver.maxConnections", "32",
            "sun.net.httpserver.maxReqHeaders", "100",
            "sun.net.httpserver.maxReqHeaderSize", String.valueOf(64 * 1024));

    private ServerLimits() {
    }

    /** Sets every {@link #PRODUCTION} property not already set. */
    static void apply() {
        PRODUCTION.forEach((name, value) -> {
            if (System.getProperty(name) == null) {
                System.setProperty(name, value);
            }
        });
    }
}

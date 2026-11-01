package dev.codespire.runtime;

/**
 * One host a unit's agent asked its egress proxy for, and whether the proxy let it through.
 *
 * <p>Hosts and ports only. The proxy log it is read from is agent-controlled text, so nothing else from it leaves
 * the arm, and a corporate upstream's credentials never appear here.
 */
public record EgressAttempt(String host, int port, boolean allowed) {

    public EgressAttempt {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("an egress attempt names its host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("not a port: " + port);
    }
}

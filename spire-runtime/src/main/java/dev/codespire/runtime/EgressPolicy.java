package dev.codespire.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where a run unit's AGENT may connect: nowhere but through a per-run egress proxy, which records every host it is
 * asked for ({@link Mode#REPORT}) or refuses every host the harness did not declare ({@link Mode#ENFORCE}).
 *
 * <p>Only the agent is behind the proxy. It holds the model credential and runs model output with a shell; init and
 * the publisher run our own code with the forge credential, which the agent never sees, and keep the deployment's
 * own outbound path.
 *
 * <p>{@code hosts} comes from the harness adapter (code), never from the reviewed repository or the ticket. It is a
 * set of bare host names: the allowed ports are one global set, {@link #PORTS}.
 *
 * <p>{@link #OFF} keeps the unit as it was before the proxy existed, which is the deployment's kill switch.
 */
public record EgressPolicy(Mode mode, Set<String> hosts) {

    public enum Mode { OFF, REPORT, ENFORCE }

    public static final EgressPolicy OFF = new EgressPolicy(Mode.OFF, Set.of());

    /**
     * What the agent's {@code HTTP(S)_PROXY} name: the proxy's alias on the unit's own internal network.
     *
     * <p>A constant, because the alias is scoped to that network and each unit has its own, so two units never meet
     * on it; the proxy CONTAINER still has a name derived from the run id, which the daemon needs to be unique.
     */
    public static final String PROXY_ADDRESS = "http://spire-egress:8888";

    /** The ports the proxy forwards to, for every host it lets through. */
    public static final Set<Integer> PORTS = Set.of(443, 80);

    public EgressPolicy {
        Objects.requireNonNull(mode, "mode");
        hosts = Objects.requireNonNull(hosts, "hosts").stream()
                .map(EgressPolicy::bareHost)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isOn() {
        return mode != Mode.OFF;
    }

    private static String bareHost(String host) {
        if (host == null || !host.matches("[A-Za-z0-9.-]{1,253}")) {
            throw new IllegalArgumentException("a declared egress host is a bare host name, was: " + host);
        }
        return host.toLowerCase(Locale.ROOT);
    }
}

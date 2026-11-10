package dev.codespire.modelproxy;

import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;

/**
 * What a model proxy is started with: its environment (the route and the credential), where it listens, where its
 * report lines go, and its {@link Limits}.
 */
record ProxyStart(Map<String, String> env, InetSocketAddress address, PrintStream log, Limits limits) {

    ProxyStart {
        env = Map.copyOf(Objects.requireNonNull(env, "env"));
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(limits, "limits");
    }

    /** A start with the production limits. */
    static ProxyStart of(Map<String, String> env, InetSocketAddress address, PrintStream log) {
        return new ProxyStart(env, address, log, Limits.PRODUCTION);
    }

    /** The same start with other limits; every component is named here once. */
    ProxyStart withLimits(Limits replacement) {
        return new ProxyStart(env, address, log, replacement);
    }

    /** Never prints the environment: it holds the credential. */
    @Override
    public String toString() {
        return "ProxyStart[address=" + address + ", limits=" + limits + ", env=***]";
    }
}

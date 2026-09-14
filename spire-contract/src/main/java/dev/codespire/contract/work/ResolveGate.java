package dev.codespire.contract.work;

import java.util.Objects;
import java.util.UUID;

/** An authenticated channel constructs this command after measuring its own authority. */
public record ResolveGate(UUID gateId, long expectedVersion, String key, boolean approve,
                          String note, String resolver, Channel channel, long generation, String artifact) {
    public enum Channel { DASHBOARD, TRACKER, PR_REVIEW }
    public ResolveGate {
        Objects.requireNonNull(gateId, "A gate is required");
        Objects.requireNonNull(channel, "An answer channel is required");
        if (expectedVersion < 1 || generation < 1 || key == null || key.isBlank()
                || resolver == null || resolver.isBlank())
            throw new IllegalArgumentException("A version, generation and decision identity are required");
    }
    public String channelName() { return channel.name().toLowerCase(java.util.Locale.ROOT); }
}

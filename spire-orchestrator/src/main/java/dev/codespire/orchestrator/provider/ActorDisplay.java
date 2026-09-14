package dev.codespire.orchestrator.provider;

import java.time.Instant;

/** Last observed label. A stale label is never resolved by handle again. */
public record ActorDisplay(String providerUserId, String handle, String displayName,
                           Instant resolvedAt, boolean stale, String effect, long revision) {}

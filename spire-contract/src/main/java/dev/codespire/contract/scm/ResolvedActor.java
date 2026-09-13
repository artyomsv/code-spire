package dev.codespire.contract.scm;

/** Stable provider identity; handle and display name are observations, never authority. */
public record ResolvedActor(String providerUserId, String handle, String displayName) {}

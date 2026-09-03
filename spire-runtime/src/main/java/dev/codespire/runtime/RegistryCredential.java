package dev.codespire.runtime;

import java.util.Objects;

/**
 * How the runtime authenticates a PULL from a private registry (FR-F14).
 *
 * <p>Held by the runtime, never by a {@link RunUnitSpec} and never by a {@link ContainerSpec}. The
 * distinction is the requirement: an operator's agent image commonly lives in a private registry,
 * so the pull needs a credential — but the CONTAINER must not carry one, because
 * {@code docker inspect} prints a container's environment and labels, and the agent process can
 * read its own environment. A credential that only ever reaches the pull command is invisible to
 * both.
 *
 * <p>{@code registry} is the host the credential is for ({@code registry.acme.example}, or
 * {@code registry-1.docker.io} for Docker Hub). It is matched against the image reference so a
 * private credential is not offered to a public registry — an image pulled from Docker Hub must not
 * present a corporate password to it.
 */
public record RegistryCredential(String registry, String username, String secret) {

    public RegistryCredential {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(secret, "secret");
        if (registry.isBlank()) {
            throw new IllegalArgumentException("a registry credential must name the registry it is for");
        }
        if (secret.isBlank()) {
            throw new IllegalArgumentException("a registry credential for " + registry + " has no secret");
        }
    }

    /**
     * Masked, because a record prints every component and {@code log.info("pulling with {}", cred)}
     * is the obvious line to write. The registry and username are kept: they are what an operator
     * needs to see when a pull is refused, and neither is a secret.
     */
    @Override
    public String toString() {
        return "RegistryCredential[registry=" + registry + ", username=" + username + ", secret=***]";
    }
}

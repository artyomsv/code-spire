package dev.codespire.runworker;

import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.docker.DockerRunRuntime;
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Where run units are placed.
 *
 * <p>A composition root, and the only place a runtime IMPLEMENTATION is named. Everything else
 * reads {@link RunRuntime} and {@code RuntimeCapabilities}, so the Kubernetes arm is a producer
 * change rather than an edit to the dispatch path — the same shape {@code ProviderClients} uses for
 * SCM adapters, and the reason the neutrality scan can allowlist composition roots by name.
 *
 * <p>M0 has one arm. Selecting between them by configuration is M5's job, and doing it now would be
 * a switch with one case in it.
 */
@ApplicationScoped
public class WorkerRuntimes {

    /**
     * The private-registry credential is handed to the RUNTIME, never to a unit spec.
     *
     * <p>It authenticates an image pull and nothing else. Everything on a unit spec ends up on
     * a container, where {@code docker inspect} prints it and the agent process can read its
     * own environment -- so a registry password routed through the spec would be readable by
     * the untrusted half of the very unit it was needed to start (FR-F14).
     */
    @Inject
    EnterpriseEnvironmentConfig enterprise;

    @Produces
    @Singleton
    public RunRuntime dockerRuntime() {
        return new DockerRunRuntime(enterprise.registryCredential().orElse(null));
    }
}

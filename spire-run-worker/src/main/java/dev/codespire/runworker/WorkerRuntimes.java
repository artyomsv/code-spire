package dev.codespire.runworker;

import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.docker.DockerRunRuntime;
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

    @Produces
    @Singleton
    public RunRuntime dockerRuntime() {
        return new DockerRunRuntime();
    }
}

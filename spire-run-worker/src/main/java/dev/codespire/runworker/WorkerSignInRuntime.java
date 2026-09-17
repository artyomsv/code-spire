package dev.codespire.runworker;

import dev.codespire.runtime.SignInRuntime;
import dev.codespire.runtime.docker.DockerSignInRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Where a trusted sign-in unit is placed (M3.5 part F).
 *
 * <p>A composition root, separate from {@link WorkerRuntimes} because the two answer different ports,
 * and separate on purpose: the run arm is the path that spends money and holds the push credential, and
 * a screen's mechanism has no business being wired into it.
 *
 * <p>Docker is the only arm. A Kubernetes deployment needs its own and does not have one, so
 * subscription sign-in is unavailable there — stated in the design rather than discovered at runtime.
 */
@ApplicationScoped
public class WorkerSignInRuntime {

    @Produces
    @Singleton
    public SignInRuntime dockerSignInRuntime() {
        return new DockerSignInRuntime();
    }
}

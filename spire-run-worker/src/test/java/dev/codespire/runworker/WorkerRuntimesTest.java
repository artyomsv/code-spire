package dev.codespire.runworker;

import dev.codespire.runtime.RegistryCredential;
import dev.codespire.runtime.RuntimeType;
import dev.codespire.runtime.docker.DockerRunRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The composition root had no test at all, and it is the only place the registry credential is
 * handed to the thing that pulls.
 *
 * <p>Replacing {@code new DockerRunRuntime(enterprise.registryCredential().orElse(null))} with the
 * no-argument constructor makes every private pull anonymous — a private image comes back as NOT
 * FOUND — and nothing anywhere failed. That is the wiring-class shape this repository already
 * records for the dispatcher and the launcher: the logic on each side is tested and the line
 * joining them is not.
 *
 * <p>Docker-free: constructing the runtime opens no socket, and this asserts what it was HANDED
 * rather than what it can reach.
 */
class WorkerRuntimesTest {

    private static WorkerRuntimes with(Optional<RegistryCredential> registry) {
        WorkerRuntimes runtimes = new WorkerRuntimes();
        runtimes.enterprise = RunLauncherTest.corporateFake(
                dev.codespire.runtime.EnterpriseEnvironment.NONE, List.of(), registry);
        return runtimes;
    }

    @Test
    void theConfiguredRegistryCredentialReachesTheRuntimeThatPulls() {
        RegistryCredential configured =
                new RegistryCredential("registry.acme.example", "spire", "TEST-registry-secret");

        DockerRunRuntime runtime = (DockerRunRuntime) with(Optional.of(configured)).dockerRuntime();

        // The credential itself, not what it matches: authFor is package-private to the arm,
        // and DockerRunRuntimeTest already proves the matching and the attachment. What is
        // untested anywhere else is whether this composition root hands it over at all.
        assertEquals(Optional.of(configured), runtime.registryCredential());
    }

    /** The ordinary deployment configures none, and every pull is anonymous. */
    @Test
    void anUnconfiguredWorkerPullsAnonymously() {
        DockerRunRuntime runtime = (DockerRunRuntime) with(Optional.empty()).dockerRuntime();

        assertEquals(Optional.empty(), runtime.registryCredential());
    }

    /** The arm is still the Docker one; selecting between arms is M5's job. */
    @Test
    void theWorkerPlacesRunUnitsOnTheDockerArm() {
        assertEquals(RuntimeType.DOCKER, with(Optional.empty()).dockerRuntime().type());
    }
}

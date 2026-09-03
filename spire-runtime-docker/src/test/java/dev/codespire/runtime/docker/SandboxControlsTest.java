package dev.codespire.runtime.docker;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-039 makes the container the security boundary. These assert that the controls implementing it
 * reach every container of a run unit.
 *
 * <p><b>Written because a repo-wide grep found NOTHING asserting any of them.</b> Not
 * {@code withCapDrop}, not {@code no-new-privileges}, not {@code withPidsLimit}, not
 * {@code withMemory}, in any test in any module. The daemon-driving integration test asserts
 * BEHAVIOURS — a file is readable, an exit code survives, a drain window elapses — and never
 * inspects a {@code HostConfig}, so it is blind to these by construction. A silent removal of any of
 * them would have left every suite green.
 *
 * <p>JVM-level, no daemon: building a {@code HostConfig} opens no socket, which is what makes this
 * cheap enough to run on every commit rather than in the ten-minute tier where the rest of this
 * arm's coverage lives.
 */
class SandboxControlsTest {

    /** What the builder sets today; the point is that removing any of them fails here. */
    private static final long PIDS_LIMIT = 512;

    private static final long MEMORY_BYTES = 4L * 1024 * 1024 * 1024;

    private static final long NANO_CPUS = 2_000_000_000L;

    static final long DISK_BYTES = 64L * 1024 * 1024;

    private final DockerRunRuntime runtime = new DockerRunRuntime();

    private static ContainerSpec container(List<Mount> mounts) {
        return new ContainerSpec("acme/agent:1", List.of("run"), Map.of(), mounts);
    }

    private static RunUnitSpec unit() {
        return new RunUnitSpec("run::github:acme/app:finding-1:1",
                container(List.of(Mount.writable("workspace", "/workspace"))),
                container(List.of(Mount.writable("workspace", "/workspace"),
                        Mount.writable("handoff", "/handoff"))),
                container(List.of(Mount.readOnly("handoff", "/handoff"))),
                EnterpriseEnvironment.NONE, MEMORY_BYTES, NANO_CPUS, DISK_BYTES, Duration.ofMinutes(30));
    }

    /**
     * All three containers, not just the agent.
     *
     * <p>The agent is the one running untrusted model output, but the publisher holds the git write
     * credential and the init container holds the clone credential — a privilege escalation in
     * either is worth as much to an attacker. "Two of three" is the shape a per-container
     * implementation produces, and it is what this loop exists to refuse.
     */
    @Test
    void everyContainerOfAUnitDropsCapabilitiesAndCannotGainPrivileges() {
        RunUnitSpec spec = unit();

        for (ContainerSpec container : List.of(spec.init(), spec.agent(), spec.publisher())) {
            HostConfig host = runtime.hostConfigFor(spec, container);

            assertEquals(List.of(Capability.ALL), Arrays.asList(host.getCapDrop()),
                    "default capabilities include CAP_NET_RAW and CAP_SETUID; this container runs "
                            + "untrusted model output at full shell access");
            assertTrue(host.getSecurityOpts().contains("no-new-privileges"),
                    "without it a setuid binary in the image escalates inside the container");
        }
    }

    /**
     * A fork bomb in an unconfined container exhausts the HOST's pid_max, not just its own.
     *
     * <p>Memory and CPU alone do not bound it — that is the whole reason the pids limit is there.
     */
    @Test
    void everyContainerIsBoundedInProcessesMemoryAndCpu() {
        RunUnitSpec spec = unit();

        for (ContainerSpec container : List.of(spec.init(), spec.agent(), spec.publisher())) {
            HostConfig host = runtime.hostConfigFor(spec, container);

            assertEquals(PIDS_LIMIT, host.getPidsLimit());
            assertEquals(MEMORY_BYTES, host.getMemory());
            assertEquals(NANO_CPUS, host.getNanoCPUs());
        }
    }

    /**
     * Auto-remove stays OFF, because salvage reads an exit code and a removed container has none.
     *
     * <p>Asserted rather than assumed: it is the one setting here whose absence looks like tidiness
     * and costs the run's outcome.
     */
    @Test
    void aContainerIsNotAutoRemovedBecauseSalvageMustReadItsExitCode() {
        HostConfig host = runtime.hostConfigFor(unit(), unit().agent());

        assertEquals(Boolean.FALSE, host.getAutoRemove());
    }

    /**
     * The publisher's handoff mount is read-only, and the agent's is not.
     *
     * <p>This is ADR-039's central invariant, and its only other guard is one method of a
     * ten-minute daemon suite — measured: flipping both binds to read-write leaves the JVM tier
     * green in seven seconds. Cheap enough to assert here as well.
     */
    @Test
    void thePublisherCannotWriteWhatTheAgentCanWrite() {
        RunUnitSpec spec = unit();

        assertTrue(runtime.hostConfigFor(spec, spec.publisher()).getBinds()[0].getAccessMode()
                        .toString().equals("ro"),
                "the publisher holds the push credential; that is safe only while the agent cannot "
                        + "reach anything it writes");
        assertTrue(Arrays.stream(runtime.hostConfigFor(spec, spec.agent()).getBinds())
                        .anyMatch(bind -> bind.getAccessMode().toString().equals("rw")),
                "and the agent must still be able to write its own workspace");
    }

    /**
     * {@code /tmp} is bounded on every container of the unit.
     *
     * <p>Memory, CPU and process count were bounded and disk was not, so one
     * {@code fallocate -l 500G} from an agent at full shell access filled the daemon's disk and
     * took every concurrent run with it — and on a developer machine the databases beside it.
     *
     * <p>{@code /tmp} specifically because it is the one writable path every image has whatever it
     * mounts. Bounding only the unit volumes would leave an agent free to write here instead,
     * which makes the volume caps decoration.
     */
    @Test
    void everyContainerGetsASizeBoundedTmp() {
        RunUnitSpec spec = unit();

        for (ContainerSpec container : List.of(spec.init(), spec.agent(), spec.publisher())) {
            Map<String, String> tmpFs = runtime.hostConfigFor(spec, container).getTmpFs();
            assertNotNull(tmpFs, container.image() + " has no tmpfs, so /tmp is the daemon's disk");
            String options = tmpFs.get("/tmp");
            assertNotNull(options, "this container does not bound /tmp: " + tmpFs);
            assertTrue(options.contains("size=" + DISK_BYTES),
                    "/tmp is bounded by something other than the unit's declared budget: " + options);
        }
    }

    /**
     * The bound is the SPEC's number, not a constant of this arm's own.
     *
     * <p>Two values for one decision is how a limit gets raised in one place and enforced from the
     * other — the shape the drain window was caught by, where a guard that did not read the arm's
     * value kept passing after the arm changed it.
     */
    @Test
    void theTmpBoundComesFromTheSpecRatherThanAConstant() {
        long unusualBudget = 123L * 1024 * 1024;
        RunUnitSpec spec = new RunUnitSpec("run_disk",
                container(List.of(Mount.writable("ws", "/workspace"))),
                container(List.of(Mount.writable("ws", "/workspace"))),
                container(List.of(Mount.readOnly("ho", "/handoff"))),
                EnterpriseEnvironment.NONE, MEMORY_BYTES, NANO_CPUS, unusualBudget,
                Duration.ofMinutes(30));

        HostConfig config = runtime.hostConfigFor(spec, spec.agent());

        assertTrue(config.getTmpFs().get("/tmp").contains("size=" + unusualBudget),
                "the arm must spend the budget the spec declared: " + config.getTmpFs());
    }

    /**
     * A unit with no disk bound is refused before any container exists.
     *
     * <p>The same rule memory and CPU already had — "unlimited is not a limit" — extended to the
     * third resource. A spec that reached an arm with an unbounded disk would be enforced by
     * nothing, and every arm would have to remember to check.
     */
    @Test
    void aUnitWithNoDiskBoundIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RunUnitSpec("run_unbounded",
                container(List.of()), container(List.of()), container(List.of()),
                EnterpriseEnvironment.NONE, MEMORY_BYTES, NANO_CPUS, 0, Duration.ofMinutes(30)));
    }
}

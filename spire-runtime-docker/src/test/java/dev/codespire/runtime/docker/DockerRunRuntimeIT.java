package dev.codespire.runtime.docker;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.exception.NotFoundException;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a real Docker daemon, because the properties under test are properties of the daemon:
 * whether a read-only bind is actually read-only, whether one container can see another's volume,
 * and whether an exit code survives to be read. A fake would assert this adapter's beliefs about
 * Docker, which is the thing most likely to be wrong.
 *
 * <p>Pulls {@code alpine:3.20} on first run.
 */
class DockerRunRuntimeIT {

    private static final String IMAGE = "alpine:3.20";

    private final DockerRunRuntime runtime = new DockerRunRuntime();

    private final List<RunHandle> started = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDownEveryUnit() {
        // Containers are created withAutoRemove(false) so salvage can read an exit code, which
        // means nothing reclaims them but destroy. A failed assertion must not leak a unit.
        started.forEach(runtime::destroy);
    }

    private RunUnitSpec unit(String id, String agentScript, String publisherScript) {
        return unit(id, agentScript, publisherScript, Duration.ofMinutes(2));
    }

    /**
     * init seeds /workspace; the agent writes into it and drops a file in /handoff; the publisher
     * mounts /handoff READ-ONLY and does not mount /workspace at all.
     */
    private RunUnitSpec unit(String id, String agentScript, String publisherScript, Duration wallClock) {
        return new RunUnitSpec(id,
                new ContainerSpec(IMAGE, List.of("sh", "-c", "echo seeded > /workspace/seed.txt"),
                        Map.of(),
                        List.of(Mount.writable("ws", "/workspace"), Mount.writable("ho", "/handoff"))),
                new ContainerSpec(IMAGE, List.of("sh", "-c", agentScript),
                        Map.of(),
                        List.of(Mount.writable("ws", "/workspace"), Mount.writable("ho", "/handoff"))),
                new ContainerSpec(IMAGE, List.of("sh", "-c", publisherScript),
                        Map.of(),
                        List.of(Mount.readOnly("ho", "/handoff"))),
                256L * 1024 * 1024, 1_000_000_000L, wallClock);
    }

    private RunHandle start(RunUnitSpec spec) {
        RunHandle handle = runtime.create(spec);
        started.add(handle);
        return handle;
    }

    @Test
    void pullsAnImageTheDaemonDoesNotHold() {
        // The first CI run failed every case in this class with NotFound: the runner had never seen
        // alpine, and create() assumed the image was local. An operator's agent image never is —
        // it comes from a registry, digest-pinned (FR-F13). A tiny image is removed first so the
        // pull path runs on a developer's machine too, not only on a fresh runner.
        String image = "busybox:1.37.0";
        try {
            runtime.client().removeImageCmd(image).withForce(true).exec();
        } catch (NotFoundException alreadyAbsent) {
            // the state the test wants
        }
        ContainerSpec noop = new ContainerSpec(image, List.of("sh", "-c", "true"), Map.of(), List.of());
        RunHandle handle = start(new RunUnitSpec("pull-1", noop,
                new ContainerSpec(image, List.of("sh", "-c", "echo pulled"), Map.of(), List.of()),
                noop, 64L * 1024 * 1024, 500_000_000L, Duration.ofMinutes(2)));

        Finalization finalization = runtime.salvage(handle);

        assertEquals(0, finalization.exitCode());
        assertTrue(runtime.client().inspectImageCmd(image).exec().getId().startsWith("sha256:"));
    }

    @Test
    void runsInitThenAgentAndPublisherOnSharedVolumes() {
        RunHandle handle = start(unit("run_unit1",
                "cat /workspace/seed.txt; echo handed-over > /handoff/delta; echo agent-done",
                "for i in $(seq 1 30); do [ -f /handoff/delta ] && { cat /handoff/delta; "
                        + "echo publisher-done; exit 0; }; sleep 1; done; exit 1"));

        List<String> agent = new ArrayList<>();
        List<String> publisher = new ArrayList<>();
        runtime.attach(handle, LogChannel.AGENT, agent::add);
        runtime.attach(handle, LogChannel.PUBLISHER, publisher::add);
        Finalization finalization = runtime.salvage(handle);

        assertTrue(agent.contains("seeded"), "the init container's output must be in the workspace");
        assertTrue(agent.contains("agent-done"));
        assertTrue(publisher.contains("handed-over"), "the handoff volume must be shared");
        assertTrue(publisher.contains("publisher-done"));
        assertEquals(0, finalization.exitCode());
        assertTrue(finalization.salvaged());
    }

    @Test
    void thePublisherCannotSeeTheAgentsWorkspace() {
        RunHandle handle = start(unit("run_unit2",
                "echo secret > /workspace/private.txt; echo x > /handoff/delta",
                "for i in $(seq 1 30); do [ -f /handoff/delta ] && break; sleep 1; done; "
                        + "if [ -e /workspace ]; then echo LEAKED; else echo isolated; fi"));

        List<String> publisher = new ArrayList<>();
        runtime.attach(handle, LogChannel.PUBLISHER, publisher::add);
        runtime.salvage(handle);

        // ADR-038: the publisher holds the push credential, so it must never reach anything the
        // agent authored — including a .git/config or a hook.
        assertTrue(publisher.contains("isolated"), "publisher said: " + publisher);
        assertFalse(publisher.contains("LEAKED"));
    }

    @Test
    void handoffIsReadOnlyToThePublisher() {
        RunHandle handle = start(unit("run_unit3",
                "echo x > /handoff/delta; echo agent-done",
                "for i in $(seq 1 30); do [ -f /handoff/delta ] && break; sleep 1; done; "
                        + "if echo y > /handoff/evil 2>/dev/null; then echo WRITABLE; else echo readonly; fi"));

        List<String> publisher = new ArrayList<>();
        runtime.attach(handle, LogChannel.PUBLISHER, publisher::add);
        runtime.salvage(handle);

        // The property the typed Mount.readOnly exists for, asserted against the daemon rather than
        // against this adapter's belief about it.
        assertTrue(publisher.contains("readonly"), "publisher said: " + publisher);
        assertFalse(publisher.contains("WRITABLE"), "publisher said: " + publisher);
    }

    @Test
    void reportsANonZeroAgentExitRatherThanThrowing() {
        RunHandle handle = start(unit("run_unit4", "exit 3", "echo publisher-idle"));

        runtime.attach(handle, LogChannel.AGENT, line -> { });
        Finalization finalization = runtime.salvage(handle);

        assertEquals(3, finalization.exitCode());
        assertTrue(finalization.salvaged(), "a failed run is still a salvaged one — the work survives");
    }

    @Test
    void anAgentThatOutlivesItsWallClockIsNotSalvagedSuccessfully() {
        // The wall clock is validated by RunUnitSpec and enforced HERE. The first version waited on
        // the agent indefinitely, so the limit existed in the type and constrained nothing: a hung
        // run held its memory and CPU reservation until a human noticed.
        RunHandle handle = start(unit("run_unit6", "sleep 120", "sleep 120", Duration.ofSeconds(3)));

        Finalization finalization = runtime.salvage(handle);

        assertFalse(finalization.salvaged(), finalization.detail());
        assertEquals(Finalization.NOT_OBSERVED, finalization.exitCode());
    }

    @Test
    void anInitFailureRefusesTheRunAndLeavesItFindable() {
        RunUnitSpec spec = new RunUnitSpec("run_unit7",
                new ContainerSpec(IMAGE, List.of("sh", "-c", "exit 9"), Map.of(),
                        List.of(Mount.writable("ws", "/workspace"))),
                new ContainerSpec(IMAGE, List.of("sh", "-c", "echo never"), Map.of(),
                        List.of(Mount.writable("ws", "/workspace"))),
                new ContainerSpec(IMAGE, List.of("sh", "-c", "echo never"), Map.of(), List.of()),
                256L * 1024 * 1024, 1_000_000_000L, Duration.ofMinutes(1));

        assertThrows(IllegalStateException.class, () -> runtime.create(spec));
        started.add(new RunHandle("run_unit7", "unknown"));

        // Asserted through discoverOrphans, not by hand-building a handle. The version this
        // replaces did the latter and was therefore vacuous: discoverOrphans filtered on
        // role=agent, and on the init-failure path NO agent container is ever created — so the
        // unit was undiscoverable, and the init container kept the clone token in its environment
        // forever. The test asserted the comment rather than the behaviour.
        assertTrue(runtime.discoverOrphans().stream().anyMatch(h -> h.runId().equals("run_unit7")),
                "the watchdog must be able to reach a unit whose init failed");
    }

    @Test
    void thePublisherGetsItsDrainWindowNotASigtermTheInstantTheAgentExits() throws Exception {
        // The agent exits at once; the publisher still has five seconds of work. salvage() used to
        // issue `docker stop` with the drain window as its TIMEOUT — and stop sends SIGTERM first,
        // waiting the timeout only before SIGKILL — so the publisher died at ~0s with exit 143 and
        // printed nothing, and the run reported nothing pushed and nothing blocked. The reader runs
        // beside salvage, as the launcher's does: attached in sequence before it, the publisher
        // would finish on its own and the old code would pass. The trap matters just as much: a
        // shell as PID 1 IGNORES SIGTERM, so a plain `sleep 5; echo drained` survived the old
        // stop until its 30s SIGKILL and printed "drained" anyway — the mutation passed. The real
        // publisher is a JVM, which dies on SIGTERM; the trap makes the fake die the same way.
        RunHandle handle = start(unit("run_unit9", "exit 0",
                "trap 'exit 143' TERM; sleep 5 & wait $!; echo drained; exit 0"));
        List<String> publisher = new CopyOnWriteArrayList<>();
        Thread reader = Thread.ofVirtual().start(
                () -> runtime.attach(handle, LogChannel.PUBLISHER, publisher::add));

        Finalization finalization = runtime.salvage(handle);
        reader.join(Duration.ofSeconds(30).toMillis());

        assertTrue(finalization.salvaged());
        assertTrue(publisher.contains("drained"), "the publisher was stopped before it could finish: " + publisher);
        Container publisherContainer = runtime.client().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(DockerRunRuntime.RUN_ID_LABEL, "run_unit9",
                        DockerRunRuntime.ROLE_LABEL, "publisher"))
                .exec().getFirst();
        assertEquals(0L, runtime.client().inspectContainerCmd(publisherContainer.getId())
                .exec().getState().getExitCodeLong(), "143 is a SIGTERM the publisher never asked for");
    }

    @Test
    void anAgentThatOutlivesItsWallClockIsActuallyStopped() throws Exception {
        // The clock has to STOP the run, not merely stop waiting for it. Returning salvageFailed
        // and leaving the agent alive was the previous behaviour, so a hung run kept its memory,
        // its CPU reservation and its model credential — the limit bounded one method and nothing
        // else, while the commit message claimed it was enforced.
        RunHandle handle = start(unit("run_unit8", "sleep 300", "sleep 300", Duration.ofSeconds(3)));

        assertFalse(runtime.salvage(handle).salvaged());

        String state = runtime.client().inspectContainerCmd(handle.providerRunId())
                .exec().getState().getStatus();
        assertFalse("running".equals(state), "the agent is still running after its clock expired");
    }

    @Test
    void everyPartOfAUnitIsDestroyedByLabelWithNoMemoryOfIt() {
        // The finding this test exists for: destroy() looked its unit up in an in-memory map, so a
        // RESTARTED worker — which is how RUN-TOPOLOGY says recovery works — would discover an
        // orphan by label, call destroy, find nothing in the map, and return successfully having
        // deleted nothing. A fresh runtime instance stands in for that restart.
        start(unit("run_unit5", "sleep 30", "sleep 30"));

        DockerRunRuntime afterRestart = new DockerRunRuntime();
        assertTrue(afterRestart.discoverOrphans().stream()
                        .anyMatch(h -> h.runId().equals("run_unit5")),
                "a fresh instance must find the unit by label alone");

        RunHandle discovered = afterRestart.discoverOrphans().stream()
                .filter(h -> h.runId().equals("run_unit5"))
                .findFirst()
                .orElseThrow();
        afterRestart.destroy(discovered);

        assertTrue(afterRestart.discoverOrphans().stream()
                        .noneMatch(h -> h.runId().equals("run_unit5")),
                "and destroying it must actually remove it");
        assertTrue(afterRestart.client().listVolumesCmd()
                        .withFilter("label", List.of(DockerRunRuntime.RUN_ID_LABEL + "=run_unit5"))
                        .exec().getVolumes().isEmpty(),
                "including its volumes, which no map remembered either");
    }

    @Test
    void theRuntimeDeclaresWhatDockerCannotDo() {
        // Docker has no pod, so no native sidecar termination, and egress restriction needs a
        // NetworkPolicy this arm cannot express. Declared rather than assumed, so the domain reads
        // capabilities instead of branching on the runtime type.
        assertFalse(runtime.capabilities().nativeSidecar());
        assertFalse(runtime.capabilities().networkPolicy());
        assertTrue(runtime.capabilities().resourceLimits());
    }
}

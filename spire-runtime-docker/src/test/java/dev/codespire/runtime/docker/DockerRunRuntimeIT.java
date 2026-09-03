package dev.codespire.runtime.docker;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.exception.NotFoundException;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.HostMount;
import dev.codespire.runtime.RegistryCredential;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunRuntimeContract;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
 *
 * <p><b>It extends {@link RunRuntimeContract}</b>, which is where the rules every arm must obey
 * live. What stays here is what needs THIS arm's world to state — a shell, a writable host path,
 * a private registry, a {@code HostConfig} to inspect. What moved out is what any arm must satisfy,
 * so a Kubernetes arm inherits the rules instead of being written against three disagreeing fakes.
 */
class DockerRunRuntimeIT extends RunRuntimeContract {

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
        return unit(id, agentScript, publisherScript, wallClock, EnterpriseEnvironment.NONE);
    }

    private RunUnitSpec unit(String id, String agentScript, String publisherScript, Duration wallClock,
                             EnterpriseEnvironment enterprise) {
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
                enterprise, 256L * 1024 * 1024, 1_000_000_000L, 64L * 1024 * 1024, wallClock);
    }

    @Override
    protected RunRuntime runtime() {
        return runtime;
    }

    /**
     * The contract's quiet unit: everything exits 0 promptly.
     *
     * <p>{@code sleep 30} in the publisher, not an immediate exit — several contract rules are
     * about a unit that still EXISTS (discoverable, salvaged but not destroyed, cancelled but not
     * destroyed), and a unit whose containers have all exited is still held by this arm but gives
     * cancel nothing to act on.
     */
    @Override
    protected RunUnitSpec quietUnit(String runId) {
        return unit(runId, "sleep 30", "sleep 30");
    }

    @Override
    protected RunUnitSpec echoingUnit(String runId, String marker) {
        return unit(runId, "echo " + marker, "true");
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
                noop, EnterpriseEnvironment.NONE, 64L * 1024 * 1024, 500_000_000L, 16L * 1024 * 1024,
                Duration.ofMinutes(2)));

        Finalization finalization = runtime.salvage(handle);

        assertEquals(0, finalization.exitCode());
        assertTrue(runtime.client().inspectImageCmd(image).exec().getId().startsWith("sha256:"));
    }

    /**
     * The corporate CA bundle reaches every container, and reaching it is a daemon property.
     *
     * <p>Asserted against a real bind rather than against the spec, because the failure this
     * guards is not "the builder forgot" -- unit-level placement already settles that -- but
     * "the arm bound it read-write, or bound it to one container, or created an empty directory
     * where the file should be". Only the daemon can answer those.
     *
     * <p>The publisher reads it too: its push is the git call most likely to meet the corporate
     * proxy, and the publisher is the container an implementation is most likely to skip because
     * it mounts nothing else from outside the unit.
     */
    @Test
    void aCaBundleIsReadableInEveryContainerAndIsNotWritable(@TempDir Path hostDir) throws Exception {
        Path bundle = Files.writeString(hostDir.resolve("ca.crt"), "TEST-CA-BUNDLE-MARKER");
        EnterpriseEnvironment corporate = new EnterpriseEnvironment(
                List.of(new HostMount(bundle.toAbsolutePath().toString(), "/etc/spire/ca-bundle.crt")),
                Map.of("SSL_CERT_FILE", "/etc/spire/ca-bundle.crt"));

        RunHandle handle = start(unit("run_unit_ca",
                "cat /etc/spire/ca-bundle.crt; echo env=$SSL_CERT_FILE; "
                        + "if echo x >> /etc/spire/ca-bundle.crt 2>/dev/null; then echo WRITABLE; "
                        + "else echo read-only; fi; echo x > /handoff/delta",
                "for i in $(seq 1 30); do [ -f /handoff/delta ] && break; sleep 1; done; "
                        + "cat /etc/spire/ca-bundle.crt",
                Duration.ofMinutes(2), corporate));

        List<String> agent = new ArrayList<>();
        List<String> publisher = new ArrayList<>();
        runtime.attach(handle, LogChannel.AGENT, agent::add);
        runtime.attach(handle, LogChannel.PUBLISHER, publisher::add);
        runtime.salvage(handle);

        // Joined, not line-matched: cat emits no trailing newline, so the bundle body and the
        // next echo arrive as ONE log line. A contains() over the lines would fail on a feature
        // that works, which is what the first version of this assertion did.
        String agentLog = String.join("|", agent);
        assertTrue(agentLog.contains("TEST-CA-BUNDLE-MARKER"), "the agent must read the bundle: " + agent);
        assertTrue(agentLog.contains("env=/etc/spire/ca-bundle.crt"),
                "and the variable that points at it must be set: " + agent);
        assertTrue(agentLog.contains("read-only"),
                "a host bind the agent can write is a host compromise: " + agent);
        assertTrue(String.join("|", publisher).contains("TEST-CA-BUNDLE-MARKER"),
                "the publisher pushes over the same corporate TLS: " + publisher);

        // The init container exits before anything can attach to it, so its share of the same
        // property is read off the created container rather than from a log line.
        Container init = runtime.client().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(DockerRunRuntime.RUN_ID_LABEL, "run_unit_ca",
                        DockerRunRuntime.ROLE_LABEL, "init"))
                .exec().getFirst();
        var initConfig = runtime.client().inspectContainerCmd(init.getId()).exec();
        assertTrue(List.of(initConfig.getConfig().getEnv()).stream()
                        .anyMatch(entry -> entry.equals("SSL_CERT_FILE=/etc/spire/ca-bundle.crt")),
                "without the bundle the clone fails at the forge, which reads like a bad credential");
    }

    /**
     * A private-registry credential authenticates the PULL and is absent from every container.
     *
     * <p>{@code docker inspect} is the assertion because it is the exposure: it prints a
     * container's environment and labels, and the agent process can read its own environment
     * while running untrusted model output. A credential that only ever reaches pullImageCmd is
     * invisible to both.
     */
    @Test
    void aRegistryCredentialIsNotReadableFromAnyContainerOfTheUnit() {
        DockerRunRuntime withRegistry = new DockerRunRuntime(
                new RegistryCredential("registry.acme.example", "spire", "TEST-registry-secret"));

        RunHandle handle = withRegistry.create(unit("run_unit_reg", "echo x > /handoff/delta",
                "sleep 1", Duration.ofMinutes(1), EnterpriseEnvironment.NONE));
        started.add(handle);

        List<Container> containers = withRegistry.client().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(DockerRunRuntime.RUN_ID_LABEL, "run_unit_reg"))
                .exec();
        // An empty list is ZERO assertions and a green test -- the vacuity hole this repository
        // already paid for once in the contract snapshot. A renamed label or a changed filter API
        // must fail here rather than silently check nothing.
        assertEquals(3, containers.size(), "the unit this test created must be what was read");
        for (Container container : containers) {
            var config = withRegistry.client().inspectContainerCmd(container.getId()).exec();
            for (String entry : config.getConfig().getEnv()) {
                assertFalse(entry.contains("TEST-registry-secret"), "in the environment: " + entry);
            }
            assertFalse(String.valueOf(config.getConfig().getLabels()).contains("TEST-registry-secret"),
                    "in a label, which docker inspect prints to anyone who can reach the daemon");
        }
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

        // ADR-039: the publisher holds the push credential, so it must never reach anything the
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
        // The arm must say WHICH failure this was, and only a real container can prove it. Nothing
        // asserted this: reverting both call sites in the runtime left this module fully green while
        // the feature went inert, because the launcher test sets the outcome on a fake. That is the
        // seam lesson this repository has already paid for once.
        assertTrue(finalization.overran(),
                "the wall clock ran out, which is the agent's doing — reporting it as a daemon fault "
                        + "sends the operator hunting for broken infrastructure: " + finalization.detail());
    }

    @Test
    void anInitFailureRefusesTheRunAndLeavesItFindable() {
        RunUnitSpec spec = new RunUnitSpec("run_unit7",
                new ContainerSpec(IMAGE, List.of("sh", "-c", "exit 9"), Map.of(),
                        List.of(Mount.writable("ws", "/workspace"))),
                new ContainerSpec(IMAGE, List.of("sh", "-c", "echo never"), Map.of(),
                        List.of(Mount.writable("ws", "/workspace"))),
                new ContainerSpec(IMAGE, List.of("sh", "-c", "echo never"), Map.of(), List.of()),
                EnterpriseEnvironment.NONE, 256L * 1024 * 1024, 1_000_000_000L, 64L * 1024 * 1024,
                Duration.ofMinutes(1));

        assertThrows(IllegalStateException.class, () -> runtime.create(spec));
        started.add(new RunHandle("run_unit7", "unknown"));

        // Asserted through discoverUnits, not by hand-building a handle. The version this
        // replaces did the latter and was therefore vacuous: discoverUnits filtered on
        // role=agent, and on the init-failure path NO agent container is ever created — so the
        // unit was undiscoverable, and the init container kept the clone token in its environment
        // forever. The test asserted the comment rather than the behaviour.
        assertTrue(runtime.discoverUnits().stream().anyMatch(h -> h.runId().equals("run_unit7")),
                "the watchdog must be able to reach a unit whose init failed");
    }

    @Test
    void anAgentOverrunStillGivesThePublisherItsDrainWindow() throws Exception {
        // The wall-clock path used to call cancel(), which SIGKILLs BOTH roles, one line before the
        // drain window it then waited on — so every overrun lost its last checkpoint's report. The
        // agent overruns a 4s clock; the publisher is still working for 8s and must finish.
        RunHandle handle = start(unit("run_unit10", "sleep 60",
                "trap 'exit 143' TERM; sleep 8 & wait $!; echo drained; exit 0", Duration.ofSeconds(4)));
        List<String> publisher = new CopyOnWriteArrayList<>();
        Thread reader = Thread.ofVirtual().start(
                () -> runtime.attach(handle, LogChannel.PUBLISHER, publisher::add));

        Finalization finalization = runtime.salvage(handle);
        reader.join(Duration.ofSeconds(60).toMillis());

        assertFalse(finalization.salvaged(), "the agent overran its clock");
        assertTrue(publisher.contains("drained"), "the publisher was killed with the agent: " + publisher);
    }

    @Test
    void aLogLineWithNoNewlineIsClippedNotBufferedWithoutBound() throws Exception {
        // The agent writes to this stream unconfined, so an unterminated line was an allocation it
        // controlled on the shared worker. Past the cap the line is delivered clipped with a marker,
        // the remainder dropped up to the next newline, and lines after it still arrive.
        RunHandle handle = start(unit("run_unit11",
                "head -c 200000 /dev/zero | tr '\\0' a; echo; echo tail-line", "true"));
        List<String> agent = new ArrayList<>();
        runtime.attach(handle, LogChannel.AGENT, agent::add);
        runtime.salvage(handle);

        int longest = agent.stream().mapToInt(String::length).max().orElse(0);
        assertEquals(DockerRunRuntime.MAX_LINE_CHARS, longest, "the clipped line is exactly the cap");
        assertTrue(agent.stream().anyMatch(l -> l.startsWith("[spire: a log line exceeded")), agent.toString());
        assertTrue(agent.contains("tail-line"), "dropping ends at the newline: " + agent.size() + " lines");
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
        assertTrue(afterRestart.discoverUnits().stream()
                        .anyMatch(h -> h.runId().equals("run_unit5")),
                "a fresh instance must find the unit by label alone");

        RunHandle discovered = afterRestart.discoverUnits().stream()
                .filter(h -> h.runId().equals("run_unit5"))
                .findFirst()
                .orElseThrow();
        afterRestart.destroy(discovered);

        assertTrue(afterRestart.discoverUnits().stream()
                        .noneMatch(h -> h.runId().equals("run_unit5")),
                "and destroying it must actually remove it");
        assertTrue(afterRestart.client().listVolumesCmd()
                        .withFilter("label", List.of(DockerRunRuntime.RUN_ID_LABEL + "=run_unit5"))
                        .exec().getVolumes().isEmpty(),
                "including its volumes, which no map remembered either");
    }

    /**
     * The disk bound is a REAL bound, asserted against the kernel rather than against a HostConfig.
     *
     * <p>{@code SandboxControlsTest} asserts the option is set, which is the half a JVM can see. It
     * cannot see whether the daemon honours it, and "the flag is present" is exactly the shape of
     * assertion this milestone has been caught by three times — the CA bundle test proved the file
     * was mounted and not that anything trusted it.
     *
     * <p>The unit declares 64 MiB, so a 128 MiB write must fail. {@code dd} reports how much it
     * actually copied, so a bound that silently did nothing shows up as the full write succeeding.
     *
     * <p>Written as the AGENT, which is the only container this bound applies to: the publisher
     * clones into {@code java.io.tmpdir}, so bounding its {@code /tmp} would fail a large
     * repository after the model had already been paid. See {@code DockerRunRuntime.tmpFsFor}.
     */
    @Test
    void aWritePastTheDiskBoundFailsRatherThanReachingTheHost() {
        RunHandle handle = start(unit("run_disk_bound",
                "dd if=/dev/zero of=/tmp/fill bs=1M count=128 2>&1; echo AGENT-DONE", "true"));

        List<String> lines = new ArrayList<>();
        runtime.attach(handle, LogChannel.AGENT, lines::add);
        String output = String.join(System.lineSeparator(), lines);

        assertTrue(output.contains("AGENT-DONE"), "the agent did not run at all: " + output);
        assertTrue(output.contains("No space left on device"),
                "a 128 MiB write into a 64 MiB /tmp must be refused by the kernel. Without the "
                        + "bound this succeeds and the same command with count=500000 fills the "
                        + "daemon's disk. Saw: " + output);
    }

    /**
     * Cancel actually stops the containers — the half {@link RunRuntimeContract} cannot state.
     *
     * <p>The contract asserts only that a cancelled unit is not DESTROYED, because
     * {@code discoverUnits} answers the same for a running and a stopped container on this arm, so
     * a {@code cancel} implemented as {@code {}} satisfies it — the contract contains its own
     * proof of that, since {@code salvageNeverDestroys} makes the identical assertion after a call
     * that stops nothing. Deciding whether a process really stopped needs this arm's own
     * vocabulary, which is why it lives here.
     *
     * <p>Asserted on the DAEMON's view rather than on a log line: an agent that happened to exit on
     * its own would produce the same output and prove nothing.
     */
    @Test
    void cancelActuallyStopsTheContainers() {
        RunHandle handle = start(unit("run_cancel_stops", "sleep 300", "sleep 300"));

        runtime.cancel(handle);

        for (String role : List.of("agent", "publisher")) {
            String containerId = runtime.client().listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of(DockerRunRuntime.RUN_ID_LABEL, "run_cancel_stops",
                            DockerRunRuntime.ROLE_LABEL, role))
                    .exec().getFirst().getId();
            Boolean running = runtime.client().inspectContainerCmd(containerId).exec()
                    .getState().getRunning();
            assertEquals(Boolean.FALSE, running,
                    "the " + role + " container is still running after cancel; an operator who "
                            + "stopped this run is still being charged for it");
        }
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

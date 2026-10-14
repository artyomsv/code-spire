package dev.codespire.runtime.docker;

import dev.codespire.runtime.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify units against a real daemon. The prepare container is an alpine stand-in for the publisher mode,
 * which has its own tests: it copies {@code /handoff/src} into the verify volume and reports a head.
 */
class DockerVerifyIT {
    static final PublicationKey KEY = new PublicationKey("a".repeat(64));
    static final String HEAD = "b".repeat(40);
    static final String IMAGE = "alpine:3.20";
    final DockerRunRuntime runtime = new DockerRunRuntime();
    final List<String> ids = new ArrayList<>();
    final UUID attempt = UUID.randomUUID();

    @AfterEach void cleanup() {
        for (String id : ids) clear(id);
    }

    @Test void checksRunInOrderOnTheVerifyVolumeAndTheFirstFailureStops() {
        RunHandle held = held("order", "mkdir -p /handoff/src && echo TEST > /handoff/src/marker");
        VerifyRun run = runtime.verifyHeld(held, KEY, verify(held, Duration.ofSeconds(60), "test -f /workspace/marker", "exit 4", "echo never"), () -> true);
        assertEquals(VerifyRun.Prepare.PREPARED, run.prepare());
        assertEquals(HEAD, run.preparedHead());
        assertEquals(List.of(0, 4), run.checks().stream().map(VerifyRun.Check::exitCode).toList());
        assertFalse(run.timedOut());
    }

    @Test void aCheckRunsInTheVerifyWorkspaceWhateverTheImagesWorkdir() {
        RunHandle held = held("workdir", "mkdir -p /handoff/src && echo TEST > /handoff/src/marker");
        VerifyRun run = runtime.verifyHeld(held, KEY, verify(held, Duration.ofSeconds(60), "test -f marker && pwd"), () -> true);
        assertEquals(0, run.checks().getFirst().exitCode(), run.checks().getFirst().tail().toString());
        assertTrue(run.checks().getFirst().tail().contains("/workspace"));
    }

    @Test void aShellCommandRunsAsTyped() {
        RunHandle held = held("shell", "true");
        VerifyRun run = runtime.verifyHeld(held, KEY,
                verify(held, Duration.ofSeconds(60), "A='TEST a b'; [ \"$A\" = 'TEST a b' ] && echo ok-$((1+1))"), () -> true);
        assertEquals(0, run.checks().getFirst().exitCode());
        assertTrue(run.checks().getFirst().tail().contains("ok-2"), run.checks().getFirst().tail().toString());
    }

    @Test void anEndlessLineIsClippedAndTheTailStaysBounded() {
        RunHandle held = held("tail", "true");
        VerifyRun run = runtime.verifyHeld(held, KEY,
                verify(held, Duration.ofSeconds(60), "seq 1 5000; head -c 300000 /dev/zero | tr '\\0' x"), () -> true);
        List<String> tail = run.checks().getFirst().tail();
        assertTrue(tail.size() <= DockerRunRuntime.VERIFY_TAIL_LINES, "lines: " + tail.size());
        assertTrue(tail.stream().allMatch(line -> line.length() <= DockerRunRuntime.VERIFY_LINE_CHARS + 16));
        assertTrue(String.join("\n", tail).length() <= DockerRunRuntime.VERIFY_TAIL_CHARS);
        assertFalse(tail.contains("1"), "only the last lines are kept");
    }

    @Test void theTimeLimitKillsAndReportsTimedOut() {
        RunHandle held = held("timeout", "true");
        VerifyRun run = runtime.verifyHeld(held, KEY, verify(held, Duration.ofSeconds(5), "sleep 60"), () -> true);
        assertTrue(run.timedOut());
        assertNull(run.checks().getFirst().exitCode());
    }

    @Test void aCheckSeesNoHandoffAndNoUncommittedAgentFile() {
        RunHandle held = held("contained", "echo TEST-stray > /workspace/stray; echo TEST > /handoff/marker");
        VerifyRun run = runtime.verifyHeld(held, KEY,
                verify(held, Duration.ofSeconds(60), "[ ! -e /handoff/marker ] && [ ! -e /workspace/stray ]"), () -> true);
        assertEquals(0, run.checks().getFirst().exitCode());
    }

    @Test void aMissingCheckpointIsReportedFromThePrepareExitCode() {
        RunHandle held = held("missing", "true");
        VerifyRun run = runtime.verifyHeld(held, KEY, verifyWithPrepare(held, "exit 3", "true"), () -> true);
        assertEquals(VerifyRun.Prepare.CHECKPOINT_MISSING, run.prepare());
        assertTrue(run.checks().isEmpty());
    }

    @Test void verifyResourcesKeepTheUnitHeldAndAreNeverItsHandle() {
        RunHandle held = held("handle", "true");
        runtime.verifyHeld(held, KEY, verify(held, Duration.ofSeconds(60), "true"), () -> true);
        assertTrue(runtime.publicationHeld(held));
        assertFalse(verifyResources(held.runId()).isEmpty());
        for (RunHandle unit : runtime.discoverUnits()) {
            if (unit.runId().equals(held.runId())) assertNotEquals(DockerRunRuntime.VERIFY, role(unit.providerRunId()));
        }
        runtime.removeVerify(held, attempt);
        runtime.removeVerify(held, attempt);
        assertTrue(verifyResources(held.runId()).isEmpty());
        assertTrue(volumes(held.runId()).stream().noneMatch(volume -> volume.getName().contains(attempt.toString())));
        runtime.destroyHeld(held, KEY);
    }

    @Test void cancelStopsARunningCheck() throws Exception {
        RunHandle held = held("cancel", "true");
        CompletableFuture<VerifyRun> running = CompletableFuture.supplyAsync(
                () -> runtime.verifyHeld(held, KEY, verify(held, Duration.ofSeconds(120), "sleep 60"), () -> true));
        awaitRunningCheck(held.runId());
        runtime.cancel(held);
        VerifyRun run = running.get(60, TimeUnit.SECONDS);
        assertNotEquals(Integer.valueOf(0), run.checks().getFirst().exitCode());
    }

    private RunHandle held(String suffix, String agent) {
        String id = "TEST-verify-" + suffix;
        clear(id);
        ids.add(id);
        RunUnitSpec spec = new RunUnitSpec(id,
                new ContainerSpec(IMAGE, List.of("sh", "-c", "true"), Map.of(),
                        List.of(Mount.writable("ws", "/workspace"), Mount.writable("handoff", "/handoff"))),
                new ContainerSpec(IMAGE, List.of("sh", "-c", agent), Map.of(),
                        List.of(Mount.writable("ws", "/workspace"), Mount.writable("handoff", "/handoff"))),
                new ContainerSpec(IMAGE, List.of("sh", "-c", "true"), Map.of(), List.of(Mount.readOnly("handoff", "/handoff"))),
                EnterpriseEnvironment.NONE, 64L * 1024 * 1024, 500_000_000L, 16L * 1024 * 1024, Duration.ofSeconds(15));
        RunHandle handle = runtime.createHeld(spec, KEY);
        runtime.salvage(handle);
        return handle;
    }

    private VerifyUnitSpec verify(RunHandle held, Duration timeout, String... commands) {
        return spec(held, "cp -r /handoff/src/. /workspace/ 2>/dev/null; echo '{\"event\":\"prepared\",\"head\":\"" + HEAD + "\"}'",
                timeout, commands);
    }

    private VerifyUnitSpec verifyWithPrepare(RunHandle held, String prepare, String... commands) {
        return spec(held, prepare, Duration.ofSeconds(60), commands);
    }

    private VerifyUnitSpec spec(RunHandle held, String prepare, Duration timeout, String... commands) {
        String volume = VerifyUnitSpec.VOLUME_PREFIX + attempt;
        ContainerSpec prepareSpec = new ContainerSpec(IMAGE, List.of("sh", "-c", prepare), Map.of(),
                List.of(Mount.readOnly("handoff", "/handoff"), Mount.writable(volume, "/workspace")));
        List<ContainerSpec> checks = new ArrayList<>();
        for (String command : commands) {
            checks.add(new ContainerSpec(IMAGE, List.of(command), Map.of(), List.of(Mount.writable(volume, "/workspace")),
                    List.of("/bin/sh", "-c")));
        }
        return new VerifyUnitSpec(held.runId(), attempt, prepareSpec, checks, EnterpriseEnvironment.NONE,
                64L * 1024 * 1024, 500_000_000L, 16L * 1024 * 1024, timeout);
    }

    private void awaitRunningCheck(String id) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            boolean running = verifyResources(id).stream().anyMatch(container -> "running".equals(container.getState()));
            if (running) return;
            Thread.sleep(250);
        }
        fail("no verify check started for " + id);
    }

    private String role(String containerId) {
        return runtime.client().inspectContainerCmd(containerId).exec().getConfig().getLabels().get(DockerRunRuntime.ROLE_LABEL);
    }

    private List<com.github.dockerjava.api.model.Container> verifyResources(String id) {
        return runtime.client().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(DockerRunRuntime.RUN_ID_LABEL, id, DockerRunRuntime.VERIFY_ATTEMPT_LABEL, attempt.toString())).exec();
    }

    private List<com.github.dockerjava.api.model.Container> containers(String id) {
        return runtime.client().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(DockerRunRuntime.RUN_ID_LABEL, id)).exec();
    }

    private List<com.github.dockerjava.api.command.InspectVolumeResponse> volumes(String id) {
        return runtime.client().listVolumesCmd().withFilter("label", List.of(DockerRunRuntime.RUN_ID_LABEL + "=" + id))
                .exec().getVolumes();
    }

    private void clear(String id) {
        for (var container : containers(id)) runtime.client().removeContainerCmd(container.getId()).withForce(true).exec();
        for (var volume : volumes(id)) runtime.client().removeVolumeCmd(volume.getName()).exec();
    }
}

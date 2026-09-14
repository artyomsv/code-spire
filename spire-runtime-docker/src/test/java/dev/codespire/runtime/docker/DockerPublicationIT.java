package dev.codespire.runtime.docker;

import dev.codespire.runtime.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Daemon-level retention and publisher resumption. Real Git publication is covered in the worker tier. */
class DockerPublicationIT {
    static final PublicationKey KEY = new PublicationKey("a".repeat(64));
    static final PublicationKey OTHER = new PublicationKey("b".repeat(64));
    final DockerRunRuntime runtime = new DockerRunRuntime();
    final List<String> ids = new ArrayList<>();

    @AfterEach void cleanup() {
        // Exact TEST run labels, before AND after each case. This remains independent of the
        // production hold/release guards so a killed mutation cannot leave masking artifacts.
        for (String id : ids) clear(id);
    }

    @Test void holdSurvivesRestartAndOrdinaryTeardown() {
        RunUnitSpec spec = unit("retention", "echo TEST-retained > /handoff/result", "true");
        RunHandle handle = runtime.createHeld(spec, KEY);
        assertEquals(0, runtime.salvage(handle).exitCode());
        DockerRunRuntime restarted = new DockerRunRuntime();
        assertTrue(restarted.publicationHeld(handle));
        assertThrows(IllegalStateException.class, () -> restarted.destroy(handle));
        assertEquals(3, containers(handle.runId()).size());
        for (var container : containers(handle.runId())) {
            assertEquals(KEY.value(), container.getLabels().get(DockerRunRuntime.PUBLICATION_HOLD_LABEL));
            assertFalse(runtime.client().inspectContainerCmd(container.getId()).exec().getState().getRunning());
        }
        for (var volume : volumes(handle.runId())) {
            assertEquals(KEY.value(), volume.getLabels().get(DockerRunRuntime.PUBLICATION_HOLD_LABEL));
        }
        assertEquals(2, volumes(handle.runId()).size());
        restarted.destroyHeld(handle, KEY);
        assertEquals(0, containers(handle.runId()).size());
        assertEquals(0, volumes(handle.runId()).size());
    }

    @Test void repeatedPermitObservesOnePublisherWithoutRestartingTheBuild() {
        RunUnitSpec spec = unit("resume", "echo TEST-retained > /handoff/result", "true");
        RunHandle handle = runtime.createHeld(spec, KEY);
        assertEquals(0, runtime.salvage(handle).exitCode());
        var agent = runtime.client().inspectContainerCmd(handle.providerRunId()).exec();
        String started = agent.getState().getStartedAt();
        RunUnitSpec publication = publisher(spec, "test ! -e /workspace/seed && test -f /handoff/result "
                + "&& ! touch /handoff/write-probe && cat /handoff/result", spec.publisher().mounts());
        UUID permit = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            List<String> lines = new ArrayList<>();
            Finalization result = new DockerRunRuntime().publishHeld(handle, KEY, permit, publication, lines::add);
            assertTrue(result.salvaged(), result.detail());
            assertEquals(0, result.exitCode(), lines.toString());
            assertTrue(lines.contains("TEST-retained"), lines.toString());
            assertEquals(4, containers(handle.runId()).size(), "one init, one build, one held publisher, one delivery publisher");
            assertEquals(started, runtime.client().inspectContainerCmd(handle.providerRunId()).exec().getState().getStartedAt());
            assertTrue(runtime.publicationHeld(handle), "publication observation does not discard work before the durable result");
        }
    }

    @Test void anotherBindingCannotPublishOrDiscardTheWorkspace() {
        RunUnitSpec spec = unit("binding", "true", "true");
        RunHandle handle = runtime.createHeld(spec, KEY);
        runtime.salvage(handle);
        assertThrows(IllegalStateException.class,
                () -> runtime.publishHeld(handle, OTHER, UUID.randomUUID(), spec, line -> {}));
        assertThrows(IllegalStateException.class, () -> runtime.destroyHeld(handle, OTHER));
        assertEquals(3, containers(handle.runId()).size());
        assertEquals(2, volumes(handle.runId()).size());
    }

    @Test void publicationCannotNameAnotherRun() {
        RunUnitSpec spec = unit("run-binding", "true", "true");
        RunHandle handle = runtime.createHeld(spec, KEY);
        runtime.salvage(handle);
        RunUnitSpec other = unit("other-run", "true", "true");
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> runtime.publishHeld(handle, KEY, UUID.randomUUID(), other, line -> {}));
        assertEquals("A publication must name its retained run", refusal.getMessage(),
                "identify the wrong run before deriving its volume names; a later mount mismatch is a different refusal");
        assertEquals(3, containers(handle.runId()).size());
    }

    @Test void publicationCannotAddTheAgentWorkspaceToItsMounts() {
        RunUnitSpec spec = unit("mounts", "true", "true");
        RunHandle handle = runtime.createHeld(spec, KEY);
        runtime.salvage(handle);
        RunUnitSpec changed = publisher(spec, "cat /workspace/seed",
                List.of(Mount.readOnly("ho", "/handoff"), Mount.readOnly("ws", "/workspace")));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.publishHeld(handle, KEY, UUID.randomUUID(), changed, line -> {}));
        assertEquals(3, containers(handle.runId()).size());
    }

    @Test void aRunningBuildCannotBePublished() {
        RunUnitSpec spec = unit("running-agent", "sleep 60", "true");
        RunHandle handle = runtime.createHeld(spec, KEY);
        assertTrue(runtime.client().inspectContainerCmd(handle.providerRunId()).exec().getState().getRunning());
        assertThrows(IllegalStateException.class,
                () -> runtime.publishHeld(handle, KEY, UUID.randomUUID(), spec, line -> {}));
    }

    @Test void aRunningHeldPublisherCannotBePublished() {
        RunUnitSpec spec = unit("running-publisher", "true", "sleep 60");
        RunHandle handle = runtime.createHeld(spec, KEY);
        runtime.client().waitContainerCmd(handle.providerRunId())
                .exec(new com.github.dockerjava.core.command.WaitContainerResultCallback()).awaitStatusCode();
        assertThrows(IllegalStateException.class,
                () -> runtime.publishHeld(handle, KEY, UUID.randomUUID(),
                        publisher(spec, "true", spec.publisher().mounts()), line -> {}));
    }

    @Test void aNamedPublisherMustStillMatchItsPermit() {
        RunUnitSpec spec = unit("permit-instance", "true", "true");
        RunHandle handle = runtime.createHeld(spec, KEY);
        runtime.salvage(handle);
        UUID permit = UUID.randomUUID();
        runtime.client().createContainerCmd("alpine:3.20")
                .withName(DockerRunRuntime.volumeName(spec.runId(), "publish-" + permit))
                .withCmd("sh", "-c", "true")
                .withLabels(Map.of(DockerRunRuntime.RUN_ID_LABEL, spec.runId(),
                        DockerRunRuntime.PUBLICATION_HOLD_LABEL, KEY.value(),
                        DockerRunRuntime.PUBLICATION_PERMIT_LABEL, UUID.randomUUID().toString())).exec();
        assertThrows(IllegalStateException.class,
                () -> runtime.publishHeld(handle, KEY, permit, spec, line -> {}));
    }

    @Test void cancelStopsTheDeliveryPublisherAndRetainsItsHandoff() throws Exception {
        RunUnitSpec spec = unit("cancel-delivery", "true", "true");
        RunHandle handle = runtime.createHeld(spec, KEY);
        runtime.salvage(handle);
        RunUnitSpec publication = publisher(spec, "sleep 60", spec.publisher().mounts());
        UUID permit = UUID.randomUUID();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> runtime.publishHeld(handle, KEY, permit, publication, line -> {}));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            String publisherId = null;
            while (System.nanoTime() < deadline) {
                for (var container : containers(spec.runId())) {
                    if (permit.toString().equals(container.getLabels().get(DockerRunRuntime.PUBLICATION_PERMIT_LABEL))
                            && Boolean.TRUE.equals(runtime.client().inspectContainerCmd(container.getId()).exec().getState().getRunning())) {
                        publisherId = container.getId();
                    }
                }
                if (publisherId != null) break;
                Thread.sleep(25);
            }
            assertNotNull(publisherId, "the delivery publisher must actually be running before cancel");
            runtime.cancel(handle);
            boolean stillRunning = runtime.client().inspectContainerCmd(publisherId).exec().getState().getRunning();
            // Keep mutation cleanup prompt without letting cleanup supply the observation above.
            if (stillRunning) runtime.client().killContainerCmd(publisherId).exec();
            assertFalse(stillRunning);
            assertTrue(runtime.publicationHeld(handle));
            assertNotEquals(0, result.get(10, java.util.concurrent.TimeUnit.SECONDS).exitCode());
        }
    }

    @Test void initFailureIsHeldBeforeAnyBuildStarts() {
        RunUnitSpec original = unit("init-failure", "true", "true");
        RunUnitSpec spec = new RunUnitSpec(original.runId(),
                new ContainerSpec("alpine:3.20", List.of("sh", "-c", "exit 17"), Map.of(), original.init().mounts()),
                original.agent(), original.publisher(), original.enterprise(), original.memoryBytes(),
                original.nanoCpus(), original.diskBytes(), original.wallClock());
        assertThrows(IllegalStateException.class, () -> runtime.createHeld(spec, KEY));
        assertEquals(1, containers(spec.runId()).size());
        assertEquals(KEY.value(), containers(spec.runId()).getFirst().getLabels().get(DockerRunRuntime.PUBLICATION_HOLD_LABEL));
        RunHandle partial = new RunHandle(spec.runId(), containers(spec.runId()).getFirst().getId());
        assertThrows(IllegalStateException.class, () -> new DockerRunRuntime().destroy(partial));
        assertEquals(2, volumes(spec.runId()).size());
    }

    @Test void survivingVolumesKeepTheHoldAfterContainersAreLost() {
        RunUnitSpec spec = unit("volumes", "true", "true");
        RunHandle handle = runtime.createHeld(spec, KEY);
        runtime.salvage(handle);
        for (var container : containers(handle.runId())) runtime.client().removeContainerCmd(container.getId()).withForce(true).exec();
        assertEquals(0, containers(handle.runId()).size());
        assertEquals(2, volumes(handle.runId()).size());
        assertTrue(new DockerRunRuntime().publicationHeld(handle));
        assertThrows(IllegalStateException.class, () -> runtime.destroy(handle));
        runtime.destroyHeld(handle, KEY);
        assertEquals(0, volumes(handle.runId()).size());
    }

    @Test void ordinaryCreateCannotOverwriteAHeldRun() {
        RunUnitSpec spec = unit("overwrite", "true", "true");
        runtime.createHeld(spec, KEY);
        assertThrows(IllegalStateException.class, () -> runtime.create(spec));
        assertThrows(IllegalStateException.class, () -> runtime.createHeld(spec, OTHER));
        assertEquals(3, containers(spec.runId()).size());
    }

    @Test void cancellationBeforePublisherCreationLeavesNoNewProcess() {
        RunUnitSpec spec=unit("cancel-before-create","true","true");
        RunHandle handle=runtime.createHeld(spec,KEY);runtime.salvage(handle);
        List<String> lines=new ArrayList<>();
        var result=runtime.publishHeld(handle,KEY,UUID.randomUUID(),spec,lines::add,()->false);
        assertEquals(3,containers(spec.runId()).size());
        assertFalse(result.salvaged());
        assertTrue(lines.stream().anyMatch(line->line.contains("PUBLICATION_CANCELLED")));
    }

    @Test void cancellationDuringCreationPreventsPublisherStart() {
        RunUnitSpec spec=unit("cancel-created","true","true");
        RunHandle handle=runtime.createHeld(spec,KEY);runtime.salvage(handle);
        UUID permit=UUID.randomUUID();var checks=new java.util.concurrent.atomic.AtomicInteger();
        List<String> lines=new ArrayList<>();
        runtime.publishHeld(handle,KEY,permit,spec,lines::add,()->checks.incrementAndGet()==1);
        var publisher=runtime.client().inspectContainerCmd(DockerRunRuntime.volumeName(spec.runId(),"publish-"+permit)).exec();
        assertEquals("created",publisher.getState().getStatus(),"a cancellation during creation must prevent any publisher execution");
        assertTrue(lines.stream().anyMatch(line->line.contains("PUBLICATION_CANCELLED")));
    }

    @Test void cancellationAtStartStopsTheNewPublisher() {
        RunUnitSpec spec=unit("cancel-at-start","true","true");
        RunHandle handle=runtime.createHeld(spec,KEY);runtime.salvage(handle);
        UUID permit=UUID.randomUUID();var checks=new java.util.concurrent.atomic.AtomicInteger();
        var publication=publisher(spec,"sleep 20",spec.publisher().mounts());
        var result=runtime.publishHeld(handle,KEY,permit,publication,line->{},()->checks.incrementAndGet()<3);
        assertNotEquals(0,result.exitCode(),"the cancel at start must kill the process rather than wait for its successful exit");
        assertFalse(runtime.client().inspectContainerCmd(DockerRunRuntime.volumeName(spec.runId(),"publish-"+permit)).exec().getState().getRunning());
    }

    @Test void recoveryReadsAnExitedPublisherEvenWhenCancellationArrivedLater() {
        RunUnitSpec spec=unit("late-cancel","true","true");
        RunHandle handle=runtime.createHeld(spec,KEY);runtime.salvage(handle);
        UUID permit=UUID.randomUUID();var publication=publisher(spec,"echo TEST-publication-observed",spec.publisher().mounts());
        assertEquals(0,runtime.publishHeld(handle,KEY,permit,publication,line->{},()->true).exitCode());
        List<String> lines=new ArrayList<>();
        assertEquals(0,new DockerRunRuntime().publishHeld(handle,KEY,permit,publication,lines::add,()->false).exitCode());
        assertTrue(lines.contains("TEST-publication-observed"),"late cancellation must not hide a publication that already happened");
        assertEquals(4,containers(spec.runId()).size());
    }

    private RunUnitSpec unit(String suffix, String agent, String publisher) {
        String id = "TEST-publication-" + suffix;
        clear(id);
        ids.add(id);
        return new RunUnitSpec(id,
                new ContainerSpec("alpine:3.20", List.of("sh", "-c", "echo TEST-seed > /workspace/seed"), Map.of(),
                        List.of(Mount.writable("ws", "/workspace"), Mount.writable("ho", "/handoff"))),
                new ContainerSpec("alpine:3.20", List.of("sh", "-c", agent), Map.of(),
                        List.of(Mount.writable("ws", "/workspace"), Mount.writable("ho", "/handoff"))),
                new ContainerSpec("alpine:3.20", List.of("sh", "-c", publisher), Map.of(), List.of(Mount.readOnly("ho", "/handoff"))),
                EnterpriseEnvironment.NONE, 64L*1024*1024, 500_000_000L, 16L*1024*1024, Duration.ofSeconds(15));
    }

    private RunUnitSpec publisher(RunUnitSpec spec, String script, List<Mount> mounts) {
        return new RunUnitSpec(spec.runId(), spec.init(), spec.agent(),
                new ContainerSpec("alpine:3.20", List.of("sh", "-c", script), Map.of(), mounts),
                spec.enterprise(), spec.memoryBytes(), spec.nanoCpus(), spec.diskBytes(), spec.wallClock());
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

package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.RuntimeCapabilities;
import dev.codespire.runtime.RuntimeType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A run unit as three Docker containers over shared named volumes.
 *
 * <p>Docker has no pods, so the ordering Kubernetes gives for free is done here: the init container
 * runs to completion, then the publisher and the agent start concurrently on the same volumes.
 *
 * <p><b>Every lookup goes through container LABELS, never through an in-memory map.</b> That is not
 * a style choice. The worker is stateless and a restarted one recovers by discovery
 * (RUN-TOPOLOGY §7) — so a map would be empty exactly when recovery needs it, and
 * {@code destroy(handle)} on a discovered orphan would find nothing and return successfully having
 * deleted nothing. The watchdog would report a tidy fleet while every container and volume from
 * before the restart stayed behind forever.
 *
 * <p><b>Socket access is root-equivalent on the host.</b> Stated in SECURITY.md rather than
 * mitigated away; the Kubernetes arm removes it.
 *
 * <p><b>Codex's own sandbox is NOT used</b> (ADR-038): it is bubblewrap-based, cannot initialize
 * under Docker's default seccomp profile, and does not fail fast when it cannot. The container is
 * the boundary, so the default seccomp profile is KEPT and never relaxed here.
 */
public final class DockerRunRuntime implements RunRuntime {

    static final String RUN_ID_LABEL = "dev.codespire.runId";

    static final String ROLE_LABEL = "dev.codespire.role";

    /** The unit wall clock, recorded so a restarted worker can enforce it with no memory. */
    static final String WALL_CLOCK_LABEL = "dev.codespire.wallClockSeconds";

    private static final String INIT = "init";

    private static final String AGENT = "agent";

    private static final String PUBLISHER = "publisher";

    /** How long the publisher is given to drain after the agent exits, before it is stopped. */
    private static final int PUBLISHER_DRAIN_SECONDS = 30;

    private final DockerClient client;

    public DockerRunRuntime() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        this.client = DockerClientImpl.getInstance(config, http);
    }

    DockerClient client() {
        return client;
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.DOCKER;
    }

    @Override
    public RuntimeCapabilities capabilities() {
        // No native sidecar semantics in Docker: this adapter sequences the parts itself. No
        // network policy either — egress restriction needs a Kubernetes NetworkPolicy or an
        // explicit user-defined network, so the model-provider allowlist is advisory on this arm.
        return new RuntimeCapabilities(false, true, false, true, true, false);
    }

    @Override
    public RunHandle create(RunUnitSpec spec) {
        for (String volume : volumeNamesOf(spec)) {
            client.createVolumeCmd()
                    .withName(volume)
                    .withLabels(Map.of(RUN_ID_LABEL, spec.runId()))
                    .exec();
        }

        String initId = createContainer(spec, spec.init(), INIT);
        client.startContainerCmd(initId).exec();
        int initExit = client.waitContainerCmd(initId)
                .exec(new WaitContainerResultCallback())
                .awaitStatusCode();
        if (initExit != 0) {
            // The unit stays behind on purpose: its containers and volumes carry the run id, so the
            // orphan watchdog can reach them, and an operator can read why the clone failed.
            throw new IllegalStateException("init container failed with exit " + initExit
                    + " for run " + spec.runId());
        }

        String publisherId = createContainer(spec, spec.publisher(), PUBLISHER);
        String agentId = createContainer(spec, spec.agent(), AGENT);
        client.startContainerCmd(publisherId).exec();   // sidecar first, so it misses nothing
        client.startContainerCmd(agentId).exec();

        return new RunHandle(spec.runId(), agentId);
    }

    /** Volume names are derived from the run id so a discovered orphan's volumes are findable. */
    private static List<String> volumeNamesOf(RunUnitSpec spec) {
        List<String> names = new ArrayList<>();
        for (ContainerSpec container : List.of(spec.init(), spec.agent(), spec.publisher())) {
            for (Mount mount : container.mounts()) {
                String full = volumeName(spec.runId(), mount.volume());
                if (!names.contains(full)) {
                    names.add(full);
                }
            }
        }
        return names;
    }

    private static String volumeName(String runId, String volume) {
        return "spire-" + runId + "-" + volume;
    }

    private String createContainer(RunUnitSpec spec, ContainerSpec container, String role) {
        List<Bind> binds = new ArrayList<>();
        for (Mount mount : container.mounts()) {
            // AccessMode, NOT the boolean overload. Bind(String, Volume, Boolean) is noCopy — an
            // unrelated flag — so passing readOnly there compiled, read correctly at every glance,
            // and produced a WRITABLE mount. The publisher holds the push credential and must not
            // be able to write to a volume the agent controls (ADR-038), so that made the whole
            // property decorative. Caught only by asking a real daemon: the publisher wrote to
            // /handoff and said so.
            binds.add(new Bind(volumeName(spec.runId(), mount.volume()),
                    new Volume(mount.path()),
                    mount.readOnly() ? AccessMode.ro : AccessMode.rw));
        }

        List<String> env = new ArrayList<>();
        container.environment().forEach((name, value) -> env.add(name + "=" + value));

        HostConfig host = HostConfig.newHostConfig()
                .withBinds(binds)
                .withMemory(spec.memoryBytes())
                .withNanoCPUs(spec.nanoCpus())
                // salvage() must be able to read the exit code, and an auto-removed container has
                // none to read. Teardown is destroy()'s job and nothing else's.
                .withAutoRemove(false);

        return client.createContainerCmd(container.image())
                .withCmd(container.argv())
                .withEnv(env)             // credentials live HERE, never in a label
                .withLabels(labelsFor(spec, role))
                .withHostConfig(host)
                .exec()
                .getId();
    }

    @Override
    public void attach(RunHandle handle, LogChannel channel, Consumer<String> lines) {
        String role = channel == LogChannel.AGENT ? AGENT : PUBLISHER;
        String containerId = containerOf(handle.runId(), role)
                .orElseThrow(() -> new IllegalStateException(
                        "no " + role + " container for run " + handle.runId()));
        try {
            client.logContainerCmd(containerId)
                    .withStdOut(true).withStdErr(true).withFollowStream(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            String chunk = new String(frame.getPayload(), StandardCharsets.UTF_8);
                            for (String line : chunk.split("\\R")) {
                                if (!line.isEmpty()) {
                                    lines.accept(line);
                                }
                            }
                        }
                    })
                    .awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void cancel(RunHandle handle) {
        for (String role : List.of(AGENT, PUBLISHER)) {
            containerOf(handle.runId(), role).ifPresent(this::killQuietly);
        }
    }

    /**
     * Waits for the agent within the unit's wall clock, then drains the publisher.
     *
     * <p><b>The wall clock is enforced here, and nowhere else.</b> {@link RunUnitSpec} refuses to be
     * constructed without one, which made it look bounded — but the first version of this method
     * waited on the agent indefinitely, so the limit existed in the type and constrained nothing. A
     * run that hung held its memory and CPU reservation until someone noticed.
     */
    @Override
    public Finalization salvage(RunHandle handle) {
        Optional<String> agent = containerOf(handle.runId(), AGENT);
        if (agent.isEmpty()) {
            return Finalization.salvageFailed("no agent container for run " + handle.runId());
        }
        try {
            Integer exit = client.waitContainerCmd(agent.get())
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(wallClockOf(handle).toSeconds(), TimeUnit.SECONDS);
            drainPublisher(handle);
            if (exit == null) {
                return Finalization.salvageFailed("agent did not exit within the run's wall clock");
            }
            return Finalization.salvaged(exit, "agent exited " + exit);
        } catch (RuntimeException e) {
            drainPublisher(handle);
            // A timeout arrives as a RuntimeException from the callback. Either way the unit is
            // PRESERVED: destroy() is a separate call, so nothing is thrown away here.
            return Finalization.salvageFailed("could not read the agent's exit code: " + e.getMessage());
        }
    }

    /**
     * The wall clock recorded on the unit's containers at creation.
     *
     * <p>Read back from a label rather than held in memory, for the same reason every other lookup
     * is: a restarted worker salvaging a discovered orphan has no memory of the spec.
     */
    private Duration wallClockOf(RunHandle handle) {
        return containerRecordOf(handle.runId(), AGENT)
                .map(container -> container.getLabels().get(WALL_CLOCK_LABEL))
                .filter(value -> value != null && !value.isBlank())
                .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                .orElse(Duration.ofHours(1));
    }

    private void drainPublisher(RunHandle handle) {
        containerOf(handle.runId(), PUBLISHER).ifPresent(id -> {
            try {
                // It has no work left once the agent is gone and the last bundle has been read.
                client.stopContainerCmd(id).withTimeout(PUBLISHER_DRAIN_SECONDS).exec();
            } catch (RuntimeException e) {
                // already exited
            }
        });
    }

    @Override
    public void destroy(RunHandle handle) {
        for (Container container : containersOf(handle.runId())) {
            try {
                client.removeContainerCmd(container.getId()).withForce(true).exec();
            } catch (RuntimeException e) {
                // already gone
            }
        }
        client.listVolumesCmd()
                .withFilter("label", List.of(RUN_ID_LABEL + "=" + handle.runId()))
                .exec()
                .getVolumes()
                .forEach(volume -> {
                    try {
                        client.removeVolumeCmd(volume.getName()).exec();
                    } catch (RuntimeException e) {
                        // already gone
                    }
                });
    }

    /**
     * Every agent container this daemon holds, live or exited.
     *
     * <p>Named for the caller's question, not this method's answer: a runtime cannot know which
     * units a lease still claims, so it returns all of them and the caller filters. Erring toward
     * returning too many is the safe direction — a missed orphan is a container and its volumes
     * held forever, while an over-reported one is filtered out by a lease the caller does know
     * about.
     */
    @Override
    public List<RunHandle> discoverOrphans() {
        List<RunHandle> handles = new ArrayList<>();
        for (Container container : client.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(ROLE_LABEL, AGENT))
                .exec()) {
            String runId = container.getLabels().get(RUN_ID_LABEL);
            if (runId != null) {
                handles.add(new RunHandle(runId, container.getId()));
            }
        }
        return handles;
    }

    private void killQuietly(String containerId) {
        try {
            client.killContainerCmd(containerId).exec();
        } catch (RuntimeException e) {
            // already stopped
        }
    }

    private Optional<String> containerOf(String runId, String role) {
        return containerRecordOf(runId, role).map(Container::getId);
    }

    private Optional<Container> containerRecordOf(String runId, String role) {
        return client.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(RUN_ID_LABEL, runId, ROLE_LABEL, role))
                .exec()
                .stream()
                .findFirst();
    }

    private List<Container> containersOf(String runId) {
        return client.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(RUN_ID_LABEL, runId))
                .exec();
    }

    /** Labels a container carries so a restarted worker can act on it without any memory. */
    private static Map<String, String> labelsFor(RunUnitSpec spec, String role) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(RUN_ID_LABEL, spec.runId());
        labels.put(ROLE_LABEL, role);
        labels.put(WALL_CLOCK_LABEL, Long.toString(spec.wallClock().toSeconds()));
        return labels;
    }
}

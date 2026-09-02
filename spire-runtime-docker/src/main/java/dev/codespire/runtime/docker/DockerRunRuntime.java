package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.NameParser;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p><b>Codex's own sandbox is NOT used</b> (ADR-039): it is bubblewrap-based, cannot initialize
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
    /**
     * How long the publisher gets after the agent is gone. This is the window for its FINAL
     * bundle: fetch (up to the size cap), diff, gate, and a push to a forge that may be slow. Thirty
     * seconds cut a large last checkpoint off mid-push and reported the previous one as the result.
     */
    private static final int PUBLISHER_DRAIN_SECONDS = 300;

    /** The same window, for the worker's ack budget: a handler holds the channel for the wall clock PLUS this. */
    public static final Duration PUBLISHER_DRAIN = Duration.ofSeconds(PUBLISHER_DRAIN_SECONDS);

    /** A log line longer than this is clipped and its remainder dropped; see {@link #attach}. */
    static final int MAX_LINE_CHARS = 64 * 1024;

    /** Grace between SIGTERM and SIGKILL when the drain window elapses. */
    private static final int STOP_GRACE_SECONDS = 5;

    /** A fork bomb in an unconfined container exhausts the HOST pid_max, not just its own. */
    private static final long PIDS_LIMIT = 512;

    /** A registry pull of an agent image with a toolchain in it; a stalled one must not hang a run. */
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(10);

    /** A clone of a large repository from a forge; a stalled one must not hold the dispatch slot for ever. */
    private static final Duration INIT_TIMEOUT = Duration.ofMinutes(15);

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
        int initExit;
        try {
            // Bounded: the init container clones from a forge, and an unbounded wait on a stalled
            // clone would hold the dispatcher's one ordered slot for ever with no run to show for it.
            initExit = client.waitContainerCmd(initId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode((int) INIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (RuntimeException notFinished) {
            stopQuietly(initId);
            throw new IllegalStateException("init container did not finish within " + INIT_TIMEOUT
                    + " for run " + spec.runId(), notFinished);
        }
        if (initExit != 0) {
            // The unit stays behind on purpose: its containers and volumes carry the run id, so the
            // orphan watchdog can reach them, and an operator can read why the clone failed.
            throw new IllegalStateException("init container failed with exit " + initExit
                    + " for run " + spec.runId());
        }

        String publisherId = createContainer(spec, spec.publisher(), PUBLISHER);
        String agentId = createContainer(spec, spec.agent(), AGENT);
        // Publisher first, but ONLY as a defensive habit — nothing depends on it. The handoff is a
        // FILE, so it persists: a publisher that started late still sees every bundle already
        // written, which HandoffWatcher covers directly. A mutation swapping these two lines was
        // caught by no test, and that is correct rather than a gap — pinning the order would pin an
        // implementation detail. It would become load-bearing only if the handoff were ever a
        // stream instead of a directory.
        client.startContainerCmd(publisherId).exec();
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

    /**
     * A daemon-legal name derived from the run id, never the id itself.
     *
     * <p>A run id is {@code run::github:acme/app:finding-1:1}, and a local volume name may contain
     * only {@code [a-zA-Z0-9][a-zA-Z0-9_.-]}. Naming the volume after the id verbatim failed at
     * {@code create} for every real run — reported as {@code SANDBOX_UNREACHABLE}, retryable, and
     * invisible to the integration tests, whose ids are synthetic and slash-free. The id itself
     * stays on {@link #RUN_ID_LABEL}, which is what {@link #destroy} and {@link #discoverOrphans}
     * already look volumes up by; the name only has to be unique and legal.
     */
    static String volumeName(String runId, String volume) {
        return "spire-" + digestOf(runId) + "-" + volume;
    }

    private static String digestOf(String runId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(runId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    /**
     * Pulls the image when the daemon does not hold it. An operator's agent image lives in a
     * registry and a digest-pinned reference (FR-F13) is the normal case, not a local tag — the
     * first CI run proved it, failing every unit with NotFound on a runner that had never seen
     * alpine. A digest reference is passed whole; a name:tag pair is split, because docker-java's
     * tag-less pull fetches every tag of the repository.
     */
    private void ensureImage(String image) {
        try {
            client.inspectImageCmd(image).exec();
            return;
        } catch (NotFoundException absent) {
            // not held locally: pull it
        }
        PullImageCmd pull = client.pullImageCmd(image);
        if (!image.contains("@")) {
            NameParser.ReposTag parsed = NameParser.parseRepositoryTag(image);
            pull = client.pullImageCmd(parsed.repos).withTag(parsed.tag.isEmpty() ? "latest" : parsed.tag);
        }
        try {
            if (!pull.exec(new PullImageResultCallback()).awaitCompletion(PULL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Pulling image " + image + " did not complete within " + PULL_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted pulling image " + image, e);
        }
    }

    private String createContainer(RunUnitSpec spec, ContainerSpec container, String role) {
        List<Bind> binds = new ArrayList<>();
        for (Mount mount : container.mounts()) {
            // AccessMode, NOT the boolean overload. Bind(String, Volume, Boolean) is noCopy — an
            // unrelated flag — so passing readOnly there compiled, read correctly at every glance,
            // and produced a WRITABLE mount. The publisher holds the push credential and must not
            // be able to write to a volume the agent controls (ADR-039), so that made the whole
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
                // Memory and CPU alone do not bound an unconfined agent. A fork bomb exhausts the
                // host pid_max; a privilege escalation or a raw socket is available by default.
                .withPidsLimit(PIDS_LIMIT)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withCapDrop(Capability.ALL)
                // salvage() must be able to read the exit code, and an auto-removed container has
                // none to read. Teardown is destroy()'s job and nothing else's.
                .withAutoRemove(false);

        ensureImage(container.image());
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
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                        // The daemon does not align frames to lines: one JSON report line can
                        // arrive across two frames. Splitting each frame on its own delivered both
                        // halves as separate, unparseable lines, and PublisherOutcome skips a line it
                        // cannot parse -- so a split "pushed" report made a real push invisible and
                        // the run reported no push. The trailing partial line is carried over and
                        // flushed when the stream ends.
                        private final StringBuilder carry = new StringBuilder();

                        // The carry is bounded. The agent writes to this stream at
                        // danger-full-access, so an unterminated line is an allocation it controls
                        // on the worker every run shares: past MAX_LINE_CHARS the line is delivered
                        // clipped, with a marker, and the rest is dropped up to the next newline.
                        private boolean dropping;

                        @Override
                        public void onNext(Frame frame) {
                            carry.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            int newline;
                            while ((newline = indexOfLineEnd(carry)) >= 0) {
                                String line = carry.substring(0, newline);
                                carry.delete(0, newline + 1);
                                if (dropping) {
                                    dropping = false;
                                    continue;
                                }
                                if (line.endsWith("\r")) {
                                    line = line.substring(0, line.length() - 1);
                                }
                                if (!line.isEmpty()) {
                                    lines.accept(line);
                                }
                            }
                            if (dropping) {
                                carry.setLength(0);
                            } else if (carry.length() > MAX_LINE_CHARS) {
                                lines.accept(carry.substring(0, MAX_LINE_CHARS));
                                lines.accept("[spire: a log line exceeded " + MAX_LINE_CHARS
                                        + " characters; the remainder was dropped]");
                                carry.setLength(0);
                                dropping = true;
                            }
                        }

                        @Override
                        public void onComplete() {
                            if (!carry.isEmpty()) {
                                lines.accept(carry.toString());
                                carry.setLength(0);
                            }
                            super.onComplete();
                        }
                    };
        // try-with-resources on the callback: an interrupt (the launcher ending a reader after a
        // failed salvage) then CLOSES the follow stream and releases the daemon connection. Before,
        // only the thread returned; the stream and the connection lived on with the container.
        try (ResultCallback.Adapter<Frame> stream = client.logContainerCmd(containerId)
                .withStdOut(true).withStdErr(true).withFollowStream(true)
                .exec(callback)) {
            stream.awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the log stream of " + containerId, e);
        }
    }

    /** An operator's cancel ends the whole unit; the salvage path's overrun ends the agent alone. */
    @Override
    public void cancel(RunHandle handle) {
        for (String role : List.of(AGENT, PUBLISHER)) {
            containerOf(handle.runId(), role).ifPresent(this::killQuietly);
        }
    }

    private void killAgent(RunHandle handle) {
        containerOf(handle.runId(), AGENT).ifPresent(this::killQuietly);
    }

    /**
     * Waits for the agent within the unit's wall clock, stops it if it overran, then drains the
     * publisher.
     *
     * <p><b>The clock has to STOP the run, not merely stop waiting for it.</b> Two versions of this
     * method got that wrong in different ways. The first waited on the agent indefinitely, so the
     * limit existed in {@link RunUnitSpec} and constrained nothing. The second bounded the wait and
     * returned — but a timeout arrives as an EXCEPTION from the callback rather than a null status,
     * so the cancel sat in a branch that never ran, and a hung run kept its memory, its CPU
     * reservation and its model credential exactly as before. The commit message claimed it was
     * enforced; only a test that inspected the container's state afterwards disagreed.
     *
     * <p>Cancelling is not destroying. The unit is preserved either way — {@link #destroy} is a
     * separate call — so an operator can still read what the agent was doing when its time ran out.
     */
    @Override
    public Finalization salvage(RunHandle handle) {
        Optional<String> agent = containerOf(handle.runId(), AGENT);
        if (agent.isEmpty()) {
            return Finalization.salvageFailed("no agent container for run " + handle.runId());
        }
        Integer exit;
        try {
            exit = client.waitContainerCmd(agent.orElseThrow())
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(wallClockOf(handle).toSeconds(), TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            // Includes the timeout. Stop the AGENT before reporting, whatever went wrong: an
            // unreadable exit code does not mean the process is gone. Only the agent — cancel()
            // kills both roles, and killing the publisher here took away, one line before it, the
            // drain window it is given next: every overrun lost its last pushed checkpoint's report.
            killAgent(handle);
            drainPublisher(handle);
            return Finalization.salvageFailed("agent did not exit within the run's wall clock, or its "
                    + "status could not be read (" + e.getClass().getSimpleName() + "); cancelled");
        }
        if (exit == null) {
            killAgent(handle);
            drainPublisher(handle);
            return Finalization.salvageFailed("agent did not exit within the run's wall clock; cancelled");
        }
        drainPublisher(handle);
        return Finalization.salvaged(exit, "agent exited " + exit);
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

    /**
     * Lets the publisher finish, and stops it only if it does not.
     *
     * <p>The publisher ends itself once it has read the last bundle and seen the agent's DONE marker.
     * The version this replaces issued {@code docker stop} with the drain window as its timeout — and
     * {@code stop} sends SIGTERM <em>immediately</em>, waiting the timeout only before SIGKILL. Salvage
     * calls this the instant the agent exits, so the publisher JVM, typically still fetching the final
     * bundle, died with exit 143 before it could gate or push, and printed nothing: every such run was
     * reported finished with nothing pushed and nothing blocked. The comment beside that call described
     * a wait that did not exist. A cancelled agent never writes DONE, so on the wall-clock path the
     * publisher polls until the window elapses and is then stopped — bounded, with whatever it already
     * pushed on the remote.
     */
    private void drainPublisher(RunHandle handle) {
        containerOf(handle.runId(), PUBLISHER).ifPresent(id -> {
            try {
                client.waitContainerCmd(id).exec(new WaitContainerResultCallback())
                        .awaitStatusCode(PUBLISHER_DRAIN_SECONDS, TimeUnit.SECONDS);
            } catch (RuntimeException notFinished) {
                try {
                    client.stopContainerCmd(id).withTimeout(STOP_GRACE_SECONDS).exec();
                } catch (RuntimeException alreadyGone) {
                    // exited between the wait and the stop
                }
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
        Set<String> seen = new LinkedHashSet<>();
        for (Container container : client.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(List.of(RUN_ID_LABEL))
                .exec()) {
            String runId = container.getLabels().get(RUN_ID_LABEL);
            if (runId != null && seen.add(runId)) {
                handles.add(new RunHandle(runId, container.getId()));
            }
        }
        return handles;
    }

    /** The first line terminator in the buffer, or -1. Both \n and \r\n count; a CR alone does not. */
    private static int indexOfLineEnd(StringBuilder buffer) {
        return buffer.indexOf("\n");
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

    /** Stops a container that outlived its wait; nothing to do if it exited in between. */
    private void stopQuietly(String id) {
        try {
            client.stopContainerCmd(id).withTimeout(STOP_GRACE_SECONDS).exec();
        } catch (RuntimeException alreadyGone) {
            // exited between the wait and the stop
        }
    }
}

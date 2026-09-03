package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.AuthConfig;
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
import dev.codespire.runtime.HostMount;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RegistryCredential;
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
import java.util.Locale;
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

    /**
     * The JDK's own logger, not a framework one.
     *
     * <p>This module is Apache-2.0 and carries exactly two dependencies — the runtime SPI and the
     * Docker client. Pulling a logging framework in for one warning would widen that for every
     * consumer of a reference adapter, and {@code System.Logger} routes into whichever backend the
     * host service already has.
     */
    private static final System.Logger LOG = System.getLogger(DockerRunRuntime.class.getName());

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

    /** The same window as a {@link Duration}, answered through {@link #drainWindow()}. */
    private static final Duration PUBLISHER_DRAIN = Duration.ofSeconds(PUBLISHER_DRAIN_SECONDS);

    /** A log line longer than this is clipped and its remainder dropped; see {@link #attach}. */
    static final int MAX_LINE_CHARS = 64 * 1024;

    /** Grace between SIGTERM and SIGKILL when the drain window elapses. */
    private static final int STOP_GRACE_SECONDS = 5;

    /** A fork bomb in an unconfined container exhausts the HOST pid_max, not just its own. */
    private static final long PIDS_LIMIT = 512;

    /**
     * The one spelling every Docker Hub reference is normalised to before matching.
     *
     * <p>An internal key, not a claim about what the registry protocol expects -- docker-java
     * itself declares {@code AuthConfig.DEFAULT_SERVER_ADDRESS} as
     * {@code https://index.docker.io/v1/}, so an earlier version of this comment was simply wrong.
     * What matters here is that both sides of the comparison go through
     * {@link #registryHostOf}, so the value is arbitrary as long as it is used consistently.
     */
    static final String DOCKER_HUB = "registry-1.docker.io";

    /**
     * Every spelling of Docker Hub a reference or an operator can carry.
     *
     * <p>Without this, {@code acme/private} and {@code docker.io/acme/private} -- the same image --
     * matched different registries, so an operator writing the fully qualified form (which is what
     * registry-agnostic tooling emits, and what a digest pin looks like) got a silent ANONYMOUS
     * pull and a not-found. Configuring {@code docker.io}, which is what {@code docker login}
     * accepts, broke the bare form instead. Both are the failure this matching exists to prevent.
     */
    private static final java.util.Set<String> DOCKER_HUB_ALIASES =
            java.util.Set.of("docker.io", "index.docker.io", "registry-1.docker.io");

    /** A registry pull of an agent image with a toolchain in it; a stalled one must not hang a run. */
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(10);

    /** A clone of a large repository from a forge; a stalled one must not hold the dispatch slot for ever. */
    private static final Duration INIT_TIMEOUT = Duration.ofMinutes(15);

    private final DockerClient client;

    /**
     * How a private registry is authenticated, or null for an anonymous pull.
     *
     * <p>Held by the runtime and never by a spec, so it reaches {@code pullImageCmd} and
     * nothing else. A container is created with no knowledge of it, which is what makes it
     * absent from {@code docker inspect} and unreadable by the agent process (FR-F14).
     */
    private final RegistryCredential registry;

    public DockerRunRuntime() {
        this(null);
    }

    public DockerRunRuntime(RegistryCredential registry) {
        this.registry = registry;
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
    public Duration drainWindow() {
        return PUBLISHER_DRAIN;
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
     * stays on {@link #RUN_ID_LABEL}, which is what {@link #destroy} and {@link #discoverUnits}
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
        PullImageCmd pull = pullCommandFor(image);
        try {
            if (!pull.exec(new PullImageResultCallback()).awaitCompletion(PULL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Pulling image " + image + " did not complete within " + PULL_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted pulling image " + image, e);
        }
    }

    /**
     * The pull command for this image, authenticated when a credential was issued for its registry.
     *
     * <p>Extracted so the ATTACHMENT is assertable without a daemon -- building a command opens no
     * socket. Deleting the {@code withAuthConfig} line used to break no test at all: {@code authFor}
     * could keep answering correctly for ever while nothing carried its answer to the pull, and the
     * only thing that would notice was a live private-registry pull nobody performs. The auth line
     * also sits after BOTH branches here, so "authenticated in one branch only" is inexpressible.
     */
    PullImageCmd pullCommandFor(String image) {
        PullImageCmd pull = client.pullImageCmd(image);
        if (!image.contains("@")) {
            NameParser.ReposTag parsed = NameParser.parseRepositoryTag(image);
            pull = client.pullImageCmd(parsed.repos).withTag(parsed.tag.isEmpty() ? "latest" : parsed.tag);
        }
        authFor(image).ifPresent(pull::withAuthConfig);
        return pull;
    }

    /** The credential this runtime pulls with, or empty. Exposed so the worker wiring is assertable. */
    public Optional<RegistryCredential> registryCredential() {
        return Optional.ofNullable(registry);
    }

    /**
     * The credential for this image, or empty.
     *
     * <p>Matched on the registry HOST parsed from the reference, so a corporate password is
     * never presented to a registry it was not issued for. An unqualified reference
     * ({@code alpine:3.20}) is Docker Hub, so it matches only a credential configured for Hub.
     * Offering the credential to every pull would be simpler and would send it to whichever
     * public registry an operator happened to reference.
     */
    Optional<AuthConfig> authFor(String image) {
        if (registry == null) {
            return Optional.empty();
        }
        // Both sides through the same function, so a credential configured as "docker.io" and an
        // image written "index.docker.io/..." are the one registry they actually are. A raw
        // string compare here is how the two spellings drifted apart in the first place.
        String configured = registryHostOf(registry.registry() + "/x");
        if (!configured.equalsIgnoreCase(registryHostOf(image))) {
            return Optional.empty();
        }
        return Optional.of(new AuthConfig()
                .withRegistryAddress(registry.registry())
                .withUsername(registry.username())
                .withPassword(registry.secret()));
    }

    /**
     * The registry host of an image reference, by the rule the daemon itself uses: the first
     * path segment is a registry only when it carries a dot or a colon, or is "localhost".
     * Without that rule {@code acme/app} would parse as the registry {@code acme}, and a
     * credential for a real host would never match anything.
     */
    static String registryHostOf(String image) {
        int slash = image.indexOf("/");
        if (slash < 0) {
            return DOCKER_HUB;
        }
        String first = image.substring(0, slash);
        boolean looksLikeHost = first.indexOf(".") >= 0 || first.indexOf(":") >= 0
                || first.equals("localhost");
        if (!looksLikeHost || DOCKER_HUB_ALIASES.contains(first.toLowerCase(Locale.ROOT))) {
            return DOCKER_HUB;
        }
        return first;
    }

    /**
     * The sandbox controls, extracted so a JVM test can assert them.
     *
     * <p>ADR-039 makes the container the security boundary, and until this was extracted NO test
     * anywhere referenced {@code withCapDrop}, {@code no-new-privileges}, {@code withPidsLimit} or
     * {@code withMemory} — the daemon-driving IT asserts BEHAVIOURS (a file is readable, an exit
     * code survives) and never inspects a HostConfig, so a silent removal of any of these would
     * have left every suite green.
     */
    HostConfig hostConfigFor(RunUnitSpec spec, ContainerSpec container) {
        return HostConfig.newHostConfig()
                .withBinds(bindsFor(spec, container))
                .withMemory(spec.memoryBytes())
                .withNanoCPUs(spec.nanoCpus())
                // Memory and CPU alone do not bound an unconfined agent. A fork bomb exhausts the
                // host pid_max; a privilege escalation or a raw socket is available by default.
                .withPidsLimit(PIDS_LIMIT)
                // The one writable path every image has whatever it mounts, and the only part of
                // the unit this arm can bound. A tmpfs size= is a kernel bound -- a write past it
                // gets ENOSPC -- and it behaves identically on Docker Desktop for Windows and
                // macOS (both run a real Linux VM), on native Linux and under rootless Docker.
                //
                // The SHARED VOLUMES are deliberately NOT tmpfs, and the reason is measured rather
                // than assumed. A tmpfs-backed local volume is dropped when the last container
                // using it stops, so two containers that overlap share it and two that do not lose
                // it: `docker run A` writing a file, then `docker run B` reading it, sees an empty
                // directory. This unit runs init TO COMPLETION and only then starts the agent, so a
                // tmpfs /workspace would wipe the clone between the two -- a broken run in place of
                // an unbounded one. --storage-opt size= is no help either: it needs xfs with pquota,
                // and Docker Desktop is overlay2 on ext4, so it fails at container creation on the
                // machines most developers use.
                //
                // So /workspace stays unbounded on THIS arm; RUN-TOPOLOGY §9 carries it as a
                // deployment requirement and techdebt records the two candidate designs. The
                // Kubernetes arm has no such gap: emptyDir is a POD volume, so it survives an init
                // container exiting, and medium: Memory + sizeLimit bounds the whole unit. Which is
                // why diskBytes belongs on the spec even though this arm can only spend part of it.
                .withTmpFs(Map.of("/tmp", "rw,nosuid,nodev,size=" + spec.diskBytes()))
                .withSecurityOpts(List.of("no-new-privileges"))
                .withCapDrop(Capability.ALL)
                // salvage() must be able to read the exit code, and an auto-removed container has
                // none to read. Teardown is destroy()'s job and nothing else's.
                .withAutoRemove(false);
    }

    private String createContainer(RunUnitSpec spec, ContainerSpec container, String role) {
        HostConfig host = hostConfigFor(spec, container);

        List<String> env = new ArrayList<>();
        // Through the spec, never container.environment(): the deployment CA and proxy live at
        // unit level so that no arm can apply them to two containers out of three.
        spec.environmentFor(container).forEach((name, value) -> env.add(name + "=" + value));

        ensureImage(container.image());
        return client.createContainerCmd(container.image())
                .withCmd(container.argv())
                .withEnv(env)             // credentials live HERE, never in a label
                .withLabels(labelsFor(spec, role))
                .withHostConfig(host)
                .exec()
                .getId();
    }

    /**
     * Every volume and host path this container mounts.
     *
     * <p>Both loops in one place because they answer one question and get opposite read-only
     * treatment for opposite reasons: a unit volume may legitimately be writable, a HOST bind
     * never may.
     */
    private List<Bind> bindsFor(RunUnitSpec spec, ContainerSpec container) {
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
        for (HostMount mount : spec.hostMounts()) {
            // AccessMode.ro is not a preference here: this bind reaches the worker HOST, and the
            // agent container runs untrusted model output at full shell access. HostMount cannot
            // express a writable one, and this is the line that honours that.
            binds.add(new Bind(mount.hostPath(), new Volume(mount.path()), AccessMode.ro));
        }
        return binds;
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
     * Not supported by this arm, and saying so is the implementation.
     *
     * <p>Reaching a running agent's input needs the container created with an open stdin and an
     * attach held for the run's life. This arm creates neither, because the prompt is delivered
     * once at start from a file outside the tree — and opening a stream on EVERY run to serve a
     * capability no shipped harness declares would change the shape of every run for a path
     * nothing exercises.
     *
     * <p>Throwing rather than returning quietly is the point. The caller is expected to have
     * refused already on the harness's declared capability; if it did not, an operator must learn
     * that their instruction went nowhere rather than watch it vanish.
     */
    @Override
    public void steer(RunHandle handle, String instruction) {
        throw new UnsupportedOperationException("run " + handle.runId() + ": this runtime cannot"
                + " deliver an instruction to a running agent — its containers are created without"
                + " an open input stream, and no shipped harness declares the steer capability");
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
            return Finalization.faulted("no agent container for run " + handle.runId());
        }
        Duration wallClock = wallClockOf(handle);
        long startedAt = System.nanoTime();
        Integer exit;
        try {
            exit = client.waitContainerCmd(agent.orElseThrow())
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(wallClock.toSeconds(), TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            // Stop the AGENT before reporting, whatever went wrong: an unreadable exit code does not
            // mean the process is gone. Only the agent — cancel() kills both roles, and killing the
            // publisher here would take away, one line before it, the drain window it is given next.
            killAgent(handle);
            drainPublisher(handle);
            // WHICH failure this was is our fact, not the library's. awaitStatusCode reports the
            // timeout, an interrupt, a response with no status, and any stream fault as the same
            // exception type — so trusting it to mean "overran" labelled a dropped daemon connection
            // as the agent's doing, which is the confusion this split exists to remove, running the
            // other way. Matching on its message would couple us to a string upstream can change.
            return elapsed(startedAt).compareTo(wallClock) >= 0
                    ? Finalization.overran("agent did not exit within the run's wall clock; cancelled")
                    : Finalization.faulted("the agent's exit status could not be read ("
                            + e.getClass().getSimpleName() + "); the agent was stopped");
        }
        if (exit == null) {
            // A response arrived carrying no status code. The clock is not what failed here.
            killAgent(handle);
            drainPublisher(handle);
            return Finalization.faulted("the agent exited but the daemon reported no status code");
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
    public List<RunHandle> discoverUnits() {
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

    /** How long the wait actually took, so the clock is measured rather than inferred. */
    private static Duration elapsed(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    /**
     * Kill a container that may already be gone.
     *
     * <p>Quiet about the expected case ONLY. The comment here used to read "already stopped" over a
     * catch that swallowed everything, so a kill the daemon actually refused left a live agent
     * running with no log line anywhere — and the caller had just decided the run was over.
     * {@code NotFoundException} and {@code NotModifiedException} are the two the library raises for
     * a container that is gone or already stopped; anything else is a real failure and is said out
     * loud, without changing the outcome, because a best-effort kill that throws would lose the
     * terminal result and leave the run 'running' forever.
     */
    private void killQuietly(String containerId) {
        try {
            client.killContainerCmd(containerId).exec();
        } catch (NotFoundException | NotModifiedException expected) {
            // Gone, or already stopped. Both are the outcome this call wanted.
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "container " + containerId + " could not be killed (" + e.getClass().getSimpleName()
                            + "); it may still be running", e);
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

package dev.codespire.agentimage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Reaches an image through a real Docker daemon.
 *
 * <p>Every probe container and volume is labelled and destroyed in a {@code finally}, because a
 * conformance check is something an operator runs repeatedly and a checker that leaks one per run is
 * one they stop running. The label is what makes a leak findable when the JVM is killed mid-probe:
 * {@code docker ps -a --filter label=dev.codespire.conformance}.
 *
 * <p><b>The probe containers get no credential, no host mount, no network and no capability.</b>
 * They exist to ask an image about itself, and every question is local: {@code git init}, {@code
 * cat}, {@code ls}, {@code find}. An earlier version claimed this in prose while granting the
 * default bridge network and full default capabilities to code the image controls — on a CI runner
 * that reaches the runner's internal network. {@code verify} exists to check an image BEFORE
 * trusting it, so "they are about to run it anyway" is not an argument that holds here.
 *
 * <p>The runtime arm cannot drop the network — its agent has a model API to call — and this can,
 * which is why the two differ.
 */
final class DockerImageProbe implements ImageProbe {

    /** Labels every container and volume, so a leak from a killed JVM is findable. */
    static final String LABEL = "dev.codespire.conformance";

    /** A conformance probe is a few shell commands; anything slower is a hung image. */
    private static final int PROBE_TIMEOUT_SECONDS = 120;

    /** The agent probe commits and bundles, so it gets longer — but not unbounded. */
    private static final int AGENT_PROBE_TIMEOUT_SECONDS = 300;

    /**
     * The most container output kept.
     *
     * <p>Every probe prints a dozen {@code key=value} lines; an image that prints hundreds of
     * megabytes is hostile or broken, and an unbounded {@code StringBuilder} answers it with an
     * {@code OutOfMemoryError} — which is not a {@code RuntimeException}, so no catch on this path
     * would see it and the command would die without a report.
     */
    private static final int MAX_OUTPUT_CHARS = 1 << 20;

    private static final long MEMORY_BYTES = 512L * 1024 * 1024;

    private static final long NANO_CPUS = 2_000_000_000L;

    private static final long PIDS_LIMIT = 256L;

    /** What the seed container prints its commit on. */
    private static final String BASE_PREFIX = "base=";

    /** Printed by the listing probe when there is no DONE to compare against. */
    private static final String NO_DONE = "NO-DONE";

    private final DockerClient client;

    DockerImageProbe(DockerClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public InspectImageResponse inspect(String image) {
        return client.inspectImageCmd(image).exec();
    }

    @Override
    public Result run(String image, List<String> argv) {
        // Entrypoint overridden to an empty list: this probe asks about the IMAGE, and running it
        // through the entrypoint would run an agent instead of answering a question.
        String stamp = UUID.randomUUID().toString();
        String id = create(image, argv, List.of(), true, List.of(), stamp);
        try {
            return Result.of(runAndCollect(id, PROBE_TIMEOUT_SECONDS));
        } finally {
            destroy(id);
        }
    }

    /**
     * Runs the image the way a RUN does: its own entrypoint, over shared volumes, from a real base
     * commit.
     *
     * <p><b>The base commit has to be real.</b> The entrypoint bundles {@code $BASE..HEAD}, so a
     * made-up sha makes {@code git bundle create} fail and the handoff clause reports a conforming
     * image as broken — a false accusation, worse from a checker than a miss. So the probe seeds the
     * workspace in a first container, reads the sha it actually created, and hands that to the
     * second. That is also what a run does: init clones, then the agent starts.
     */
    @Override
    public Result runAgent(String image, List<String> harnessArgv, String prompt) {
        String stamp = UUID.randomUUID().toString();
        String workspace = "spire-conformance-" + stamp + "-ws";
        String handoff = "spire-conformance-" + stamp + "-ho";
        try {
            // Inside the try, both of them: an earlier version created the second volume outside it,
            // so a failure there leaked the first with nothing to clean it up.
            createVolume(workspace, stamp);
            createVolume(handoff, stamp);

            String base = seedRepository(image, workspace, handoff, stamp);
            if (base == null) {
                // The image could not be prepared. Reported as NOT started, so the three handoff
                // clauses read NOT CHECKED rather than naming three defects the image does not
                // have. Measured on the real reference image: a workspace owned by a different user
                // than the one the image runs as makes git refuse it as dubiously owned, the seed
                // prints a blank commit, the entrypoint aborts on its required variable, and every
                // downstream clause blamed the entrypoint.
                return Result.neverStarted("could not create a probe repository in " + image
                        + " — most often the workspace directory is not owned by the user the image "
                        + "runs as, which git refuses as dubious ownership; the mount-points clause "
                        + "says whether that is it");
            }
            String id = create(image, harnessArgv, agentEnvironment(prompt, base), false,
                    binds(workspace, handoff), stamp);
            try {
                String output = runAndCollect(id, AGENT_PROBE_TIMEOUT_SECONDS);
                return new Result(output, true,
                        listing(image, handoff, stamp, "ls -1 " + AgentImageVerifier.HANDOFF),
                        doneOn(image, handoff, stamp));
            } finally {
                destroy(id);
            }
        } finally {
            removeVolume(workspace);
            removeVolume(handoff);
        }
    }

    private static List<String> agentEnvironment(String prompt, String base) {
        return List.of(
                "SPIRE_PROMPT=" + prompt,
                "SPIRE_WORKSPACE=" + AgentImageVerifier.WORKSPACE,
                "SPIRE_HANDOFF=" + AgentImageVerifier.HANDOFF,
                // High, so only the FINAL checkpoint runs. The handoff clauses are about what the
                // entrypoint does when the harness exits; an autosave firing mid-probe would add a
                // bundle and prove nothing extra.
                "SPIRE_AUTOSAVE_SECONDS=3600",
                "SPIRE_BASE_COMMIT=" + base);
    }

    /**
     * Creates the repository the entrypoint will bundle from, or null when it could not.
     *
     * <p>Run in the image under test rather than in a helper, so it uses the same git and the same
     * user — a repository created by a different user is one git refuses as dubiously owned.
     *
     * <p><b>A blank answer is a failure, not a commit.</b> {@code echo base=$(git rev-parse HEAD)}
     * prints the literal {@code base=} when git fails, so a {@code startsWith} check accepted an
     * empty string, the entrypoint's {@code ${SPIRE_BASE_COMMIT:?}} then aborted, and the checker
     * blamed the image for three clauses it had not tested.
     */
    private String seedRepository(String image, String workspace, String handoff, String stamp) {
        String script = String.join("; ",
                "cd " + AgentImageVerifier.WORKSPACE,
                "git init -q .",
                "git config user.email conformance@factory.invalid",
                "git config user.name conformance",
                "git commit -q --allow-empty -m base",
                "echo " + BASE_PREFIX + "$(git rev-parse HEAD 2>/dev/null)");
        String id = create(image, List.of("sh", "-c", script), List.of(), true,
                binds(workspace, handoff), stamp);
        try {
            for (String line : runAndCollect(id, PROBE_TIMEOUT_SECONDS).split("\\R")) {
                if (line.startsWith(BASE_PREFIX)) {
                    String base = line.substring(BASE_PREFIX.length()).trim();
                    if (!base.isBlank()) {
                        return base;
                    }
                }
            }
            return null;
        } catch (RuntimeException unreachable) {
            // Same answer as a blank commit: the probe could not prepare the image. The caller
            // turns it into NOT CHECKED rather than into three accusations.
            return null;
        } finally {
            destroy(id);
        }
    }

    private String create(String image, List<String> argv, List<String> env,
                          boolean overrideEntrypoint, List<Bind> binds, String stamp) {
        var command = client.createContainerCmd(image)
                .withCmd(argv)
                .withEnv(env)
                .withLabels(Map.of(LABEL, stamp))
                .withHostConfig(bounded().withBinds(binds));
        if (overrideEntrypoint) {
            command = command.withEntrypoint(List.of());
        }
        return command.exec().getId();
    }

    private void createVolume(String name, String stamp) {
        client.createVolumeCmd().withName(name).withLabels(Map.of(LABEL, stamp)).exec();
    }

    private static List<Bind> binds(String workspace, String handoff) {
        return List.of(
                new Bind(workspace, new Volume(AgentImageVerifier.WORKSPACE), AccessMode.rw),
                new Bind(handoff, new Volume(AgentImageVerifier.HANDOFF), AccessMode.rw));
    }

    private static HostConfig bounded() {
        return HostConfig.newHostConfig()
                .withMemory(MEMORY_BYTES)
                .withNanoCPUs(NANO_CPUS)
                .withPidsLimit(PIDS_LIMIT)
                // A probe asks an image about itself. Every question it asks is local, so nothing
                // here needs a network, a capability, or a way to gain one. The runtime arm sets the
                // last two and cannot set the first: its agent has a model API to call.
                .withNetworkMode("none")
                .withSecurityOpts(List.of("no-new-privileges"))
                .withCapDrop(Capability.ALL)
                .withAutoRemove(false);
    }

    /**
     * Runs a container to completion and returns what it printed.
     *
     * <p>The log callback is closed on every path. Left open, a timeout leaves the follow stream's
     * thread and its daemon connection alive for the life of the JVM — the M0 lesson about readers
     * blocking on a preserved container's stream, met again.
     *
     * <p>A timeout is reported as an IMAGE fact. An earlier version ignored the wait's own boolean,
     * fell through to a second full wait, and surfaced the resulting exception as a checker problem
     * — so an image whose entrypoint blocks read as a busy daemon, after twice the advertised wait.
     */
    private String runAndCollect(String id, int timeoutSeconds) {
        StringBuilder output = new StringBuilder();
        client.startContainerCmd(id).exec();
        try (LogContainerResultCallback logs = client.logContainerCmd(id)
                .withStdOut(true).withStdErr(true).withFollowStream(true)
                .exec(new LogContainerResultCallback() {
                    @Override
                    public void onNext(Frame frame) {
                        // Past the cap the frames are still consumed, so the stream drains and the
                        // wait ends; only the retention is bounded.
                        if (output.length() < MAX_OUTPUT_CHARS) {
                            output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }
                })) {
            if (!logs.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the probe in this image did not exit within "
                        + timeoutSeconds + "s");
            }
            client.waitContainerCmd(id).exec(new WaitContainerResultCallback())
                    .awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted probing the image", interrupted);
        } catch (java.io.IOException closeFailed) {
            throw new IllegalStateException("could not close the probe's log stream", closeFailed);
        }
        return output.toString();
    }

    /**
     * What became of {@code DONE}, in one listing.
     *
     * <p>{@code -newer} is STRICTLY newer, so a same-second tie is not a violation while a bundle
     * written seconds after DONE is. An earlier version asked whether DONE was the newest file,
     * which mtime granularity cannot answer: a conforming entrypoint writes its last bundle and DONE
     * inside the same second, and the sort broke the tie arbitrarily — so a correct image failed
     * about half the time.
     */
    private Done doneOn(String image, String handoff, String stamp) {
        List<String> lines = listing(image, handoff, stamp,
                "[ -f " + AgentImageVerifier.HANDOFF + "/DONE ] "
                        + "&& find " + AgentImageVerifier.HANDOFF + " -name '*.bundle' -newer "
                        + AgentImageVerifier.HANDOFF + "/DONE "
                        + "|| echo " + NO_DONE);
        if (lines.contains(NO_DONE)) {
            return Done.NEVER_WRITTEN;
        }
        return lines.isEmpty() ? Done.WRITTEN_LAST : Done.BUNDLE_AFTER_DONE;
    }

    /**
     * Reads the handoff volume with a THROWAWAY container built from the image under test.
     *
     * <p>Not a third-party image: docker-java does not pull on create, so a {@code busybox} tag the
     * daemon does not hold answers 404 — after the expensive probe has already run — and three
     * clauses would report a checker fault on a cold machine. The image under test has a shell,
     * which the git clause has already established, and using it removes the pull question entirely.
     *
     * <p>Not from inside the probe, which would need it to report on itself after exiting, and not
     * from the host, which would need a host bind — something this checker never takes.
     *
     * <p>The redirect wraps the WHOLE command. Appended bare it binds to the last branch only, so
     * {@code find}'s stderr survived, merged into the output, and became a phantom file name that
     * flipped the clause.
     */
    private List<String> listing(String image, String handoffVolume, String stamp, String command) {
        String probeId = create(image,
                List.of("sh", "-c", "{ " + command + " ; } 2>/dev/null"), List.of(), true,
                List.of(new Bind(handoffVolume, new Volume(AgentImageVerifier.HANDOFF), AccessMode.ro)),
                stamp);
        try {
            List<String> names = new ArrayList<>();
            for (String line : runAndCollect(probeId, PROBE_TIMEOUT_SECONDS).split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                names.add(NO_DONE.equals(trimmed) ? trimmed
                        : trimmed.substring(trimmed.lastIndexOf('/') + 1));
            }
            return names;
        } finally {
            destroy(probeId);
        }
    }

    private void destroy(String id) {
        try {
            // withRemoveVolumes: an image's own VOLUME instruction creates an anonymous volume that
            // a force-remove otherwise leaves behind — measured — and /workspace is a common one.
            client.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
        } catch (RuntimeException alreadyGone) {
            // The container is what this was trying to remove; nothing further to do, and throwing
            // from a finally block would replace the probe's real answer with a cleanup failure.
            // A leak stays findable: docker ps -a --filter label=dev.codespire.conformance
        }
    }

    private void removeVolume(String name) {
        try {
            client.removeVolumeCmd(name).exec();
        } catch (RuntimeException alreadyGone) {
            // Removing it is all this was for; throwing here would replace the probe's answer.
        }
    }
}

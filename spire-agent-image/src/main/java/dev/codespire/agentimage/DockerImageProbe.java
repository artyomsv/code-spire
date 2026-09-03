package dev.codespire.agentimage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Reaches an image through a real Docker daemon.
 *
 * <p>Every probe container is created with a bounded lifetime and destroyed in a {@code finally},
 * because a conformance check is something an operator runs repeatedly and a checker that leaks a
 * container per run is one they stop running.
 *
 * <p><b>The probe containers get NO credential, no network need and no host mount.</b> They exist to
 * ask an image about itself; anything more would make the checker a way to run arbitrary work under
 * the operator's daemon.
 */
final class DockerImageProbe implements ImageProbe {

    /** A conformance probe is a few shell commands; anything slower is a hung image. */
    private static final int PROBE_TIMEOUT_SECONDS = 120;

    /** The agent probe commits and bundles, so it gets longer — but not unbounded. */
    private static final int AGENT_PROBE_TIMEOUT_SECONDS = 300;

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
        String id = client.createContainerCmd(image)
                .withEntrypoint(List.of())
                .withCmd(argv)
                .withHostConfig(bounded())
                .exec()
                .getId();
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
     * made-up sha makes {@code git bundle create} fail and the handoff clause report a conforming
     * image as broken — a false accusation, which is worse from a conformance checker than a miss.
     * So the probe seeds the workspace in a first container, reads the sha it actually created, and
     * hands that to the second. That is also what a run does: init clones, then the agent starts.
     *
     * <p>Named volumes rather than {@code volumesFrom}, because the mount points are ordinary
     * directories in the image and sharing them needs a volume — which is exactly how the runtime
     * arm builds a unit, so the probe exercises the ownership behaviour a real run meets.
     */
    @Override
    public Result runAgent(String image, List<String> harnessArgv, String prompt) {
        String stamp = Long.toHexString(System.nanoTime());
        String workspace = "spire-conformance-" + stamp + "-ws";
        String handoff = "spire-conformance-" + stamp + "-ho";
        client.createVolumeCmd().withName(workspace).exec();
        client.createVolumeCmd().withName(handoff).exec();
        try {
            String base = seedRepository(image, workspace, handoff);
            String id = client.createContainerCmd(image)
                    .withCmd(harnessArgv)
                    .withEnv(List.of(
                            "SPIRE_PROMPT=" + prompt,
                            "SPIRE_WORKSPACE=" + AgentImageVerifier.WORKSPACE,
                            "SPIRE_HANDOFF=" + AgentImageVerifier.HANDOFF,
                            // High, so only the FINAL checkpoint runs. The handoff clauses are
                            // about what the entrypoint does when the harness exits; an autosave
                            // firing mid-probe would add a bundle and prove nothing extra.
                            "SPIRE_AUTOSAVE_SECONDS=3600",
                            "SPIRE_BASE_COMMIT=" + base))
                    .withHostConfig(bounded().withBinds(binds(workspace, handoff)))
                    .exec()
                    .getId();
            try {
                String output = runAndCollect(id, AGENT_PROBE_TIMEOUT_SECONDS);
                return new Result(output, listing(handoff, "ls -1 " + AgentImageVerifier.HANDOFF),
                        doneIsNewest(handoff));
            } finally {
                destroy(id);
            }
        } finally {
            removeVolume(workspace);
            removeVolume(handoff);
        }
    }

    /**
     * Creates the repository the entrypoint will bundle from, and returns its commit.
     *
     * <p>Run in the image under test rather than in a helper, so it uses the same git and the same
     * user — a repository created by root would be unwritable by the agent and would fail the very
     * clause the mount-point check exists to catch, in the wrong place.
     */
    private String seedRepository(String image, String workspace, String handoff) {
        String script = String.join("; ",
                "cd " + AgentImageVerifier.WORKSPACE,
                "git init -q .",
                "git config user.email conformance@factory.invalid",
                "git config user.name conformance",
                "git commit -q --allow-empty -m base",
                "echo base=$(git rev-parse HEAD)");
        String id = client.createContainerCmd(image)
                .withEntrypoint(List.of())
                .withCmd(List.of("sh", "-c", script))
                .withHostConfig(bounded().withBinds(binds(workspace, handoff)))
                .exec()
                .getId();
        try {
            for (String line : runAndCollect(id, PROBE_TIMEOUT_SECONDS).split("\\R")) {
                if (line.startsWith("base=")) {
                    return line.substring("base=".length()).trim();
                }
            }
            throw new IllegalStateException("could not create a probe repository in " + image
                    + "; the git clause will say whether that is why");
        } finally {
            destroy(id);
        }
    }

    private static List<com.github.dockerjava.api.model.Bind> binds(String workspace, String handoff) {
        return List.of(
                new com.github.dockerjava.api.model.Bind(workspace,
                        new com.github.dockerjava.api.model.Volume(AgentImageVerifier.WORKSPACE)),
                new com.github.dockerjava.api.model.Bind(handoff,
                        new com.github.dockerjava.api.model.Volume(AgentImageVerifier.HANDOFF)));
    }

    private void removeVolume(String name) {
        try {
            client.removeVolumeCmd(name).exec();
        } catch (RuntimeException alreadyGone) {
            // Removing it is all this was for; throwing here would replace the probe's answer.
        }
    }

    private static HostConfig bounded() {
        return HostConfig.newHostConfig()
                .withMemory(512L * 1024 * 1024)
                .withPidsLimit(256L)
                .withAutoRemove(false);
    }

    private String runAndCollect(String id, int timeoutSeconds) {
        StringBuilder output = new StringBuilder();
        client.startContainerCmd(id).exec();
        try {
            client.logContainerCmd(id).withStdOut(true).withStdErr(true).withFollowStream(true)
                    .exec(new LogContainerResultCallback() {
                        @Override
                        public void onNext(Frame frame) {
                            output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
            client.waitContainerCmd(id).exec(new WaitContainerResultCallback())
                    .awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted probing the image", interrupted);
        }
        return output.toString();
    }

    /**
     * Whether any bundle was written AFTER DONE.
     *
     * <p>Not "is DONE the newest file", which was the first version and was wrong: mtime
     * granularity is one second on the filesystems these volumes live on, and a conforming
     * entrypoint writes its last bundle and DONE within the same second. Sorting by time then
     * breaks the tie arbitrarily, so a correct image failed about half the time -- a conformance
     * checker accusing a good image, which is worse than missing a bad one.
     *
     * <p>{@code -newer} is STRICTLY newer, so a same-second tie is not a violation while a bundle
     * written seconds after DONE is. That is also the property the publisher actually depends on:
     * DONE means everything is here, and a bundle arriving after it is the truncation.
     */
    private boolean doneIsNewest(String handoff) {
        List<String> after = listing(handoff, "[ -f " + AgentImageVerifier.HANDOFF + "/DONE ] "
                + "&& find " + AgentImageVerifier.HANDOFF + " -name \"*.bundle\" -newer "
                + AgentImageVerifier.HANDOFF + "/DONE || echo NO-DONE");
        return after.isEmpty();
    }

    /**
     * Reads the handoff volume with a THROWAWAY container.
     *
     * <p>Not from inside the probe, which would need it to report on itself after exiting, and not
     * from the host, which would need a host bind — something this checker deliberately never
     * takes, since a conformance tool that mounts host paths is a way to run work under the
     * operator's daemon.
     */
    private List<String> listing(String handoffVolume, String command) {
        String probeId = client.createContainerCmd("busybox:1.37.0")
                .withEntrypoint(List.of())
                .withCmd(List.of("sh", "-c", command + " 2>/dev/null"))
                .withHostConfig(bounded().withBinds(List.of(
                        new com.github.dockerjava.api.model.Bind(handoffVolume,
                                new com.github.dockerjava.api.model.Volume(
                                        AgentImageVerifier.HANDOFF)))))
                .exec()
                .getId();
        try {
            List<String> names = new ArrayList<>();
            for (String line : runAndCollect(probeId, PROBE_TIMEOUT_SECONDS).split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            }
            return names;
        } finally {
            destroy(probeId);
        }
    }

    private void destroy(String id) {
        try {
            client.removeContainerCmd(id).withForce(true).exec();
        } catch (RuntimeException alreadyGone) {
            // The container is what this was trying to remove; nothing further to do, and throwing
            // from a finally block would replace the probe's real answer with a cleanup failure.
        }
    }
}

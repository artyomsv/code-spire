package dev.codespire.runtime.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.PublicationKey;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.VerifyRun;
import dev.codespire.runtime.VerifyUnitSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs verify units against held builds on the Docker daemon (M4, spec §3.1). Separate from
 * {@link DockerRunRuntime} because it is one responsibility with its own failure modes; it shares that
 * class's labels, names and hardening so a hold, a cancel and {@code destroyHeld} reach every resource here.
 */
final class DockerVerifyUnits {
    private static final System.Logger LOG = System.getLogger(DockerVerifyUnits.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PREPARE_CHECKPOINT_MISSING = 3;
    private static final String CLIPPED = " [clipped]";

    private final DockerRunRuntime runtime;
    private final DockerClient client;

    DockerVerifyUnits(DockerRunRuntime runtime, DockerClient client) {
        this.runtime = runtime;
        this.client = client;
    }

    /** One wait's outcome: the exit code, or null with whether the time limit (rather than a start failure) ended it. */
    private record Exit(Integer code, boolean timedOut) {}

    VerifyRun run(RunHandle handle, PublicationKey binding, VerifyUnitSpec spec, BooleanSupplier mayContinue) {
        if (!handle.runId().equals(spec.runId())) throw new IllegalArgumentException("A verify must name its retained run");
        runtime.requireHeldAndStopped(handle, binding, "Verify");
        long deadline = System.nanoTime() + spec.timeout().toNanos();
        Map<String, String> labels = labels(spec, binding);
        createVolume(spec, labels);

        String prepareId = container(spec, spec.prepare(), labels, "prepare", false);
        Exit prepared = runToExit(prepareId, deadline);
        if (prepared.code() == null) return new VerifyRun(null, VerifyRun.Prepare.FAILED, List.of(), prepared.timedOut());
        if (prepared.code() == PREPARE_CHECKPOINT_MISSING) return new VerifyRun(null, VerifyRun.Prepare.CHECKPOINT_MISSING, List.of(), false);
        String head = preparedHead(prepareId);
        if (prepared.code() != 0 || head == null) return new VerifyRun(null, VerifyRun.Prepare.FAILED, List.of(), false);
        return runChecks(spec, labels, head, deadline, mayContinue);
    }

    private VerifyRun runChecks(VerifyUnitSpec spec, Map<String, String> labels, String head, long deadline, BooleanSupplier mayContinue) {
        List<VerifyRun.Check> checks = new ArrayList<>();
        for (int index = 0; index < spec.checks().size() && mayContinue.getAsBoolean(); index++) {
            long started = System.nanoTime();
            String id;
            try {
                id = container(spec, spec.checks().get(index), labels, "check-" + index, true);
            } catch (RuntimeException notCreated) {
                LOG.log(System.Logger.Level.WARNING, "verify check " + index + " of " + spec.runId() + " could not be created", notCreated);
                checks.add(new VerifyRun.Check(null, 0, List.of()));
                break;
            }
            Exit exit = runToExit(id, deadline);
            checks.add(new VerifyRun.Check(exit.code(), Duration.ofNanos(System.nanoTime() - started).toMillis(), tailOf(id)));
            if (exit.timedOut()) return new VerifyRun(head, VerifyRun.Prepare.PREPARED, checks, true);
            if (exit.code() == null || exit.code() != 0) break;
        }
        return new VerifyRun(head, VerifyRun.Prepare.PREPARED, checks, false);
    }

    void remove(RunHandle handle, UUID attemptId) {
        for (Container container : client.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(DockerRunRuntime.RUN_ID_LABEL, handle.runId(),
                        DockerRunRuntime.VERIFY_ATTEMPT_LABEL, attemptId.toString())).exec()) {
            try { client.removeContainerCmd(container.getId()).withForce(true).exec(); }
            catch (NotFoundException gone) { /* idempotent removal */ }
        }
        try { client.removeVolumeCmd(DockerRunRuntime.volumeName(handle.runId(), VerifyUnitSpec.VOLUME_PREFIX + attemptId)).exec(); }
        catch (NotFoundException gone) { /* idempotent removal */ }
    }

    private static Map<String, String> labels(VerifyUnitSpec spec, PublicationKey binding) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(DockerRunRuntime.RUN_ID_LABEL, spec.runId());
        // The hold label is what keeps requireHeld, publishHeld and destroyHeld accepting this run's resources.
        labels.put(DockerRunRuntime.PUBLICATION_HOLD_LABEL, binding.value());
        labels.put(DockerRunRuntime.ROLE_LABEL, DockerRunRuntime.VERIFY);
        labels.put(DockerRunRuntime.VERIFY_ATTEMPT_LABEL, spec.attemptId().toString());
        return labels;
    }

    private void createVolume(VerifyUnitSpec spec, Map<String, String> labels) {
        String name = DockerRunRuntime.volumeName(spec.runId(), spec.volume());
        try { client.inspectVolumeCmd(name).exec(); }
        catch (NotFoundException absent) { client.createVolumeCmd().withName(name).withLabels(labels).exec(); }
    }

    /** Named per attempt and role, so a second observer or a restart finds the same container instead of a twin. */
    private String container(VerifyUnitSpec spec, ContainerSpec container, Map<String, String> labels, String suffix, boolean check) {
        String name = DockerRunRuntime.volumeName(spec.runId(), spec.volume() + "-" + suffix);
        try { return client.inspectContainerCmd(name).exec().getId(); }
        catch (NotFoundException absent) { /* created below */ }
        runtime.ensureImage(container.image());
        List<String> env = new ArrayList<>();
        container.environment().forEach((key, value) -> env.add(key + "=" + value));
        spec.enterprise().environment().forEach((key, value) -> env.add(key + "=" + value));
        HostConfig host = HostConfig.newHostConfig()
                .withBinds(DockerRunRuntime.bindsOf(spec.runId(), spec.enterprise().mounts(), container))
                .withMemory(spec.memoryBytes())
                .withNanoCPUs(spec.nanoCpus())
                .withPidsLimit(DockerRunRuntime.PIDS_LIMIT)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withCapDrop(Capability.ALL)
                .withAutoRemove(false);
        // A check runs code the agent wrote, so it gets the agent's bounded /tmp; prepare runs this project's code.
        if (check) host.withTmpFs(Map.of(DockerRunRuntime.TMPFS_MOUNT, DockerRunRuntime.TMPFS_OPTIONS + spec.diskBytes()));
        // The verify volume is the working directory, whatever the image's WORKDIR: "./gradlew check" means this tree.
        var create = client.createContainerCmd(container.image()).withName(name).withCmd(container.argv())
                /* MUTANT: no working directory */
                .withEnv(env).withLabels(labels).withHostConfig(host);
        if (container.entrypoint() != null) create.withEntrypoint(container.entrypoint());
        try { return create.exec().getId(); }
        catch (ConflictException raced) { return client.inspectContainerCmd(name).exec().getId(); }
    }

    private Exit runToExit(String id, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return new Exit(null, true);
        try { client.startContainerCmd(id).exec(); }
        catch (NotModifiedException alreadyStarted) { /* a second observer */ }
        catch (RuntimeException notStarted) {
            LOG.log(System.Logger.Level.WARNING, "verify container " + id + " could not start", notStarted);
            return new Exit(null, false);
        }
        try {
            // Rounded UP: a wait that ends a fraction early would read as "not timed out" while the check still runs.
            long seconds = Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L);
            return new Exit(client.waitContainerCmd(id).exec(new WaitContainerResultCallback()).awaitStatusCode(seconds, TimeUnit.SECONDS), false);
        } catch (RuntimeException notFinished) {
            runtime.killQuietly(id);
            return new Exit(null, System.nanoTime() >= deadline);
        }
    }

    /** The head of the last {@code prepared} line, only when it is a full sha. */
    private String preparedHead(String id) {
        String head = null;
        try {
            for (String line : runtime.logLinesOf(id)) {
                if (!line.startsWith("{")) continue;
                JsonNode node = JSON.readTree(line);
                if ("prepared".equals(node.path("event").asText())) head = node.path("head").asText("");
            }
        } catch (IOException unreadable) {
            return null;
        }
        return head != null && head.matches("[0-9a-f]{40}") ? head : null;
    }

    /** The last lines of a check's output, bounded in lines, line length and total size: the agent controls it. */
    List<String> tailOf(String id) {
        StringBuilder log = new StringBuilder();
        int budget = DockerRunRuntime.VERIFY_TAIL_CHARS + DockerRunRuntime.VERIFY_LINE_CHARS;
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override public void onNext(Frame frame) {
                if (log.length() < budget) log.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        };
        try (ResultCallback.Adapter<Frame> stream = client.logContainerCmd(id).withStdOut(true).withStdErr(true)
                .withTail(DockerRunRuntime.VERIFY_TAIL_LINES).exec(callback)) {
            stream.awaitCompletion();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException unreadable) {
            return List.of("[output could not be read]");
        }
        return bounded(log.toString());
    }

    static List<String> bounded(String log) {
        Deque<String> kept = new ArrayDeque<>();
        int total = 0;
        String[] lines = log.split("\\R");
        for (int i = lines.length - 1; i >= 0 && kept.size() < DockerRunRuntime.VERIFY_TAIL_LINES; i--) {
            String line = lines[i].length() > DockerRunRuntime.VERIFY_LINE_CHARS
                    ? lines[i].substring(0, DockerRunRuntime.VERIFY_LINE_CHARS) + CLIPPED : lines[i];
            if (total + line.length() + 1 > DockerRunRuntime.VERIFY_TAIL_CHARS) break;
            kept.addFirst(line);
            total += line.length() + 1;
        }
        return List.copyOf(kept);
    }
}

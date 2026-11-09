package dev.codespire.runtime.docker;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Network;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.EgressAttempt;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunUnitSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The Docker integration tests' shared world: a runtime, the units and stand-in containers a test started (each torn
 * down by {@link #tearDown}), the unit shapes they build, and the reads that inspect what the daemon made of them.
 *
 * <p>The egress and model proxy images are built here, once per test JVM, so every class that needs them shares one
 * build.
 */
final class DockerUnits {

    static final String IMAGE = "alpine:3.20";

    /** The model proxy under a tag of these tests' own, so a deployment's {@code latest} is never replaced. */
    static final String MODEL_PROXY_IMAGE = "spire-model-proxy:TEST-it";

    /** Bounds the proxy log a failure message quotes. */
    private static final int DIAGNOSIS_CHARS = 4096;

    private static boolean egressProxyBuilt;
    private static boolean modelProxyBuilt;

    private final DockerRunRuntime runtime = new DockerRunRuntime();
    private final List<RunHandle> started = new CopyOnWriteArrayList<>();
    private final List<String> targets = new CopyOnWriteArrayList<>();

    DockerRunRuntime runtime() {
        return runtime;
    }

    /** Every unit a test started, so teardown destroys it; a test that creates one by hand adds it here. */
    List<RunHandle> started() {
        return started;
    }

    /** Every stand-in container a test started, removed by teardown. */
    List<String> targets() {
        return targets;
    }

    /**
     * The egress proxy image, built from deploy/egress under the tag the runtime names by default, so the proxy these
     * tests meet is the one a deployment runs and CI needs no registry for it.
     */
    static synchronized void buildTheEgressProxyImage() {
        if (egressProxyBuilt) {
            return;
        }
        new DockerRunRuntime().client()
                .buildImageCmd(Path.of(repoRoot(), "deploy", "egress").toFile())
                .withTags(Set.of(DockerRunRuntime.DEFAULT_EGRESS_IMAGE))
                .exec(new BuildImageResultCallback())
                .awaitImageId();
        egressProxyBuilt = true;
    }

    /**
     * The model proxy image, built from spire-model-proxy's Dockerfile and its installed distribution (the test task
     * depends on {@code :spire-model-proxy:installDist}). Through the docker CLI rather than docker-java, because the
     * module's .dockerignore admits the distribution by a negated pattern, and the CLI is what applies it as a
     * deployment's build does.
     */
    static synchronized void buildTheModelProxyImage() throws IOException, InterruptedException {
        if (modelProxyBuilt) {
            return;
        }
        Process build = new ProcessBuilder("docker", "build", "-q", "-t", MODEL_PROXY_IMAGE,
                Path.of(repoRoot(), "spire-model-proxy").toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(build.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!build.waitFor(10, TimeUnit.MINUTES) || build.exitValue() != 0) {
            throw new IllegalStateException("building " + MODEL_PROXY_IMAGE + " failed: " + output);
        }
        modelProxyBuilt = true;
    }

    private static String repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — see spire-runtime-docker/build.gradle.kts");
        }
        return root;
    }

    void tearDown() {
        // Containers are created withAutoRemove(false) so salvage can read an exit code, which
        // means nothing reclaims them but destroy. A failed assertion must not leak a unit.
        //
        // Guarded per unit: a test that destroys its own unit makes the second destroy throw, and an
        // unguarded forEach would then skip every LATER unit in the list -- each one a sandbox
        // holding a model credential that nothing reclaims. RunRuntimeContract's own teardown says
        // exactly this, and this one did not.
        for (RunHandle handle : started) {
            try {
                runtime.destroy(handle);
            } catch (RuntimeException alreadyGone) {
                // The ordinary case for a test that destroyed its own unit.
            }
            removeNetworksOf(handle.runId());
        }
        for (String target : targets) {
            try {
                runtime.client().removeContainerCmd(target).withForce(true).exec();
            } catch (RuntimeException alreadyGone) {
                // never started
            }
        }
    }

    /**
     * Independent of destroy, so a destroy that leaves a network behind fails its own test and not every later run on
     * this daemon.
     */
    private void removeNetworksOf(String runId) {
        for (Network network : labelledNetworks(runId)) {
            try {
                runtime.client().removeNetworkCmd(network.getId()).exec();
            } catch (RuntimeException alreadyGone) {
                // removed by destroy, or by a test
            }
        }
    }

    RunUnitSpec unit(String id, String agentScript, String publisherScript) {
        return unit(id, agentScript, publisherScript, Duration.ofMinutes(2));
    }

    /**
     * init seeds /workspace; the agent writes into it and drops a file in /handoff; the publisher
     * mounts /handoff READ-ONLY and does not mount /workspace at all.
     */
    RunUnitSpec unit(String id, String agentScript, String publisherScript, Duration wallClock) {
        return unit(id, agentScript, publisherScript, wallClock, EnterpriseEnvironment.NONE);
    }

    RunUnitSpec unit(String id, String agentScript, String publisherScript, Duration wallClock,
                     EnterpriseEnvironment enterprise) {
        return unit(id, agentScript, publisherScript, wallClock, enterprise, EgressPolicy.OFF);
    }

    RunUnitSpec unit(String id, String agentScript, String publisherScript, Duration wallClock,
                     EnterpriseEnvironment enterprise, EgressPolicy egress) {
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
                enterprise, egress, 256L * 1024 * 1024, 1_000_000_000L, 64L * 1024 * 1024, wallClock);
    }

    /** A unit whose agent runs {@code agentScript} behind an egress proxy, with a publisher that has nothing to do. */
    RunUnitSpec egressUnit(String id, String agentScript, EgressPolicy egress) {
        return unit(id, agentScript, "true", Duration.ofMinutes(2), EnterpriseEnvironment.NONE, egress);
    }

    RunHandle start(RunUnitSpec spec) {
        RunHandle handle = runtime.create(spec);
        started.add(handle);
        return handle;
    }

    List<String> agentLog(RunHandle handle) {
        List<String> lines = new ArrayList<>();
        runtime.attach(handle, LogChannel.AGENT, lines::add);
        return lines;
    }

    List<Network> labelledNetworks(String runId) {
        return runtime.client().listNetworksCmd()
                .withFilter("label", List.of(DockerRunRuntime.RUN_ID_LABEL + "=" + runId))
                .exec();
    }

    /** The first container of {@code role} in the unit, stopped or running. */
    Container containerOf(String runId, String role) {
        return runtime.client().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(DockerRunRuntime.RUN_ID_LABEL, runId, DockerRunRuntime.ROLE_LABEL, role))
                .exec().getFirst();
    }

    Map<String, ContainerNetwork> networksOf(String runId, String role) {
        Container container = containerOf(runId, role);
        return runtime.client().inspectContainerCmd(container.getId()).exec().getNetworkSettings().getNetworks();
    }

    List<Container> proxyOf(String runId) {
        return runtime.client().listContainersCmd().withShowAll(true)
                .withNameFilter(List.of(DockerRunNetworks.proxyName(runId)))
                .exec();
    }

    /** Starts a stand-in container to be removed at teardown, and returns its address on the default bridge. */
    String startOnTheBridge(String image, List<String> env, String... command) {
        runtime.ensureImage(image);
        String id = runtime.client().createContainerCmd(image).withEnv(env).withCmd(command).exec().getId();
        targets.add(id);
        runtime.client().startContainerCmd(id).exec();
        return runtime.client().inspectContainerCmd(id).exec()
                .getNetworkSettings().getNetworks().get("bridge").getIpAddress();
    }

    /**
     * What was parsed beside what the proxy actually wrote, so a failure shows the real log format rather than an empty
     * list. Read straight from the daemon, not through the code under test.
     */
    String diagnosis(List<EgressAttempt> parsed, String runId) {
        StringBuilder log = new StringBuilder();
        for (Container proxy : proxyOf(runId)) {
            appendLog(proxy, log);
        }
        String raw = log.length() > DIAGNOSIS_CHARS ? log.substring(0, DIAGNOSIS_CHARS) + "[...]" : log.toString();
        return "parsed=" + parsed + "; proxy log (" + proxyOf(runId).size() + " proxy containers):\n" + raw;
    }

    private void appendLog(Container proxy, StringBuilder log) {
        try (var stream = runtime.client().logContainerCmd(proxy.getId()).withStdOut(true).withStdErr(true)
                .withTailAll().exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (log.length() < DIAGNOSIS_CHARS) {
                            log.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }
                })) {
            stream.awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException | IOException | RuntimeException unreadable) {
            log.append("[proxy log unreadable: ").append(unreadable).append("]");
        }
    }
}

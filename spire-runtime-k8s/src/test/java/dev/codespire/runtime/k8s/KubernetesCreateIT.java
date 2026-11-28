package dev.codespire.runtime.k8s;

import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code create} and {@code attach} on a kind cluster whose CNI enforces network policy (Calico). Each test gets its
 * own namespace with default-deny, and deletes it at the end so no proxy pod holds the node's capacity.
 */
@Tag("kind")
class KubernetesCreateIT {

    private static final int BIG_INPUT_BYTES = 224 * 1024;

    private String namespace;

    @AfterEach
    void removeTheNamespace() {
        ClusterUnits.deleteNamespace(namespace);
    }

    private KubernetesRunRuntime runtimeIn(String prefix) {
        namespace = KindCluster.newNamespace(prefix);
        return ClusterUnits.runtime(ClusterUnits.config(namespace));
    }

    @Test
    void aUnitStartsAndItsAgentLineReachesAttach() {
        KubernetesRunRuntime runtime = runtimeIn("test-create");
        String runId = ClusterUnits.runId();

        RunHandle handle = runtime.create(ClusterUnits.unit(runId, "echo TEST-agent-line"));

        assertEquals(new RunHandle(runId, UnitNames.anchor(runId)), handle);
        assertTrue(ClusterUnits.attach(runtime, handle, LogChannel.AGENT).contains("TEST-agent-line"));
    }

    @Test
    void thePublishersOutcomeReachesAttach() {
        KubernetesRunRuntime runtime = runtimeIn("test-publish");
        RunHandle handle = runtime.create(ClusterUnits.unit(ClusterUnits.runId(), handingOff("TEST artifact")));

        List<String> lines = ClusterUnits.attach(runtime, handle, LogChannel.PUBLISHER);

        assertEquals(1, artifactLines(lines).size(), "publisher lines: " + lines);
        assertTrue(artifactLines(lines).getFirst().contains("TEST artifact"), lines.toString());
    }

    @Test
    void anInputFileIsReadableAtInputsInTheAgentOnly() {
        KubernetesRunRuntime runtime = runtimeIn("test-inputs");
        String runId = ClusterUnits.runId();
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("context.md", "TEST context");
        String big = "x".repeat(BIG_INPUT_BYTES);
        inputs.put("big.md", big);
        String script = "cat /inputs/context.md; echo; sha256sum /inputs/big.md";
        RunHandle handle = runtime.create(ClusterUnits.unit(runId, script, ClusterUnits.CLOSING_FETCH, inputs));

        List<String> lines = ClusterUnits.attach(runtime, handle, LogChannel.AGENT);

        assertTrue(lines.contains("TEST context"), lines.toString());
        assertTrue(lines.contains(sha256(big) + "  /inputs/big.md"), lines.toString());
        Pod unit = ClusterUnits.pod(namespace, UnitNames.unitPod(runId));
        assertEquals(List.of(UnitPod.AGENT), containersMountingTheInputsSecret(unit));
    }

    @Test
    void noPodSpecHoldsAnEnvValue() {
        KubernetesRunRuntime runtime = runtimeIn("test-env");
        String runId = ClusterUnits.runId();
        runtime.create(ClusterUnits.unit(runId, "echo TEST-agent-line"));

        for (String name : List.of(UnitNames.proxyPod(runId), UnitNames.unitPod(runId))) {
            Pod pod = ClusterUnits.pod(namespace, name);
            assertNotNull(pod, name);
            for (Container container : PodFixtures.allContainers(pod)) {
                assertTrue(container.getEnv() == null || container.getEnv().isEmpty(),
                        name + "/" + container.getName() + " carries env: " + container.getEnv());
            }
        }
    }

    /** M5-AC-3: from the agent, the model proxy answers and nothing else does. */
    @Test
    void fromTheAgentOnlyTheModelProxyAnswers() {
        KubernetesRunRuntime runtime = runtimeIn("test-reach");
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("canary", KindCluster.canaryTarget());
        targets.put("api", ClusterUnits.apiAddress());
        targets.put("dns", KindCluster.dnsAddress());
        targets.put("public", "1.1.1.1:443");
        targets.put("egress", RunUnitSpec.MODEL_PROXY_ALIAS + ":8888");
        targets.put("fetch", RunUnitSpec.FETCH_PROXY_ALIAS + ":" + RunUnitSpec.FETCH_PROXY_PORT);
        targets.put("model", RunUnitSpec.MODEL_PROXY_ALIAS + ":" + RunUnitSpec.MODEL_PROXY_PORT);
        RunHandle handle = runtime.create(ClusterUnits.unit(ClusterUnits.runId(), probes(targets)));

        List<String> lines = ClusterUnits.attach(runtime, handle, LogChannel.AGENT);

        for (String name : targets.keySet()) {
            String expected = "TEST-" + name + ("model".equals(name) ? " OPEN" : " BLOCKED");
            assertTrue(lines.contains(expected), "expected " + expected + " in " + lines);
        }
    }

    /** M5-AC-23: a fetch proxy that ignores the close fails the run before any agent runs. */
    @Test
    void aFetchProxyThatIgnoresTheCloseFailsTheRun() {
        namespace = KindCluster.newNamespace("test-stubborn");
        KubernetesRuntimeConfig config = ClusterUnits.config(namespace, ClusterUnits.STUBBORN_EGRESS_IMAGE,
                List.of(KindCluster.canaryTarget()), ClusterUnits.apiAddress());
        String runId = ClusterUnits.runId();

        UnitNotStarted failed = assertThrows(UnitNotStarted.class,
                () -> ClusterUnits.runtime(config).create(ClusterUnits.unit(runId, "echo TEST-agent-line")));

        assertTrue(failed.detail().startsWith(UnitStart.FETCH_PROXY_NOT_CLOSED), failed.detail());
        assertTrue(ClusterUnits.agentNeverStarted(ClusterUnits.pod(namespace, UnitNames.unitPod(runId))),
                "the agent container started");
    }

    @Test
    void aFailedFetchFailsAsTheDockerArmsInitDoes() {
        KubernetesRunRuntime runtime = runtimeIn("test-fetch-fails");
        String runId = ClusterUnits.runId();
        String fetch = "echo '{\"event\":\"failed\",\"cause\":\"CLONE_FAILED\",\"detail\":\"TEST-detail\"}'; exit 1";

        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> runtime.create(
                ClusterUnits.unit(runId, "echo TEST-agent-line", fetch, Map.of("context.md", "TEST context"))));

        assertEquals("init container failed with exit 1 for run " + runId + ": CLONE_FAILED: TEST-detail",
                failed.getMessage());
        assertTrue(ClusterUnits.agentNeverStarted(ClusterUnits.pod(namespace, UnitNames.unitPod(runId))));
    }

    /** M5-AC-28: the publisher sidecar is never restarted and prints its artifact once. */
    @Test
    void thePublisherIsNeverRestartedAndPrintsTheArtifactOnce() {
        KubernetesRunRuntime runtime = runtimeIn("test-sidecar");
        String runId = ClusterUnits.runId();
        RunHandle handle = runtime.create(ClusterUnits.unit(runId, handingOff("TEST once") + "; sleep 5"));

        List<String> lines = ClusterUnits.attach(runtime, handle, LogChannel.PUBLISHER);
        Pod ended = ClusterUnits.awaitEnded(namespace, UnitNames.unitPod(runId));

        assertEquals(1, artifactLines(lines).size(), "publisher lines: " + lines);
        ContainerStatus publisher = UnitStartFailure.statuses(ended).stream()
                .filter(status -> UnitPod.PUBLISHER.equals(status.getName())).findFirst().orElseThrow();
        assertEquals(0, publisher.getRestartCount(), "the publisher was restarted");
    }

    /** The agent writes its artifact and then DONE, as the agent image's entrypoint does. */
    private static String handingOff(String artifact) {
        return "printf '%s\\n' '" + artifact + "' > /handoff/artifact.md && touch /handoff/DONE";
    }

    private static List<String> artifactLines(List<String> lines) {
        return lines.stream().filter(line -> line.contains("\"event\":\"artifact\"")).toList();
    }

    /** One line per target: {@code TEST-<name> OPEN} on a connection within two seconds, else BLOCKED. */
    private static String probes(Map<String, String> targets) {
        StringBuilder script = new StringBuilder();
        for (Map.Entry<String, String> target : targets.entrySet()) {
            String address = target.getValue();
            int colon = address.lastIndexOf(':');
            script.append("if nc -w 2 ").append(address, 0, colon).append(' ').append(address.substring(colon + 1))
                    .append(" </dev/null >/dev/null 2>&1; then echo TEST-").append(target.getKey())
                    .append(" OPEN; else echo TEST-").append(target.getKey()).append(" BLOCKED; fi; ");
        }
        return script.toString();
    }

    private static List<String> containersMountingTheInputsSecret(Pod pod) {
        String secretVolume = null;
        for (Volume volume : pod.getSpec().getVolumes()) {
            if (volume.getSecret() != null) {
                secretVolume = volume.getName();
            }
        }
        List<String> mounting = new ArrayList<>();
        for (Container container : PodFixtures.allContainers(pod)) {
            for (VolumeMount mount : container.getVolumeMounts()) {
                if (mount.getName().equals(secretVolume)) {
                    mounting.add(container.getName());
                }
            }
        }
        return mounting;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always present", e);
        }
    }
}

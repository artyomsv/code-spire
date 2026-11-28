package dev.codespire.runtime.k8s;

import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.UnitInputs;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Review units that really run on the kind cluster. The agent, the fetch and the model proxy are shell stand-ins in
 * the test unit image; the seal, fetch-closed and publisher are the real publisher image, and the egress and fetch
 * proxies the real egress image. Placeholders only: TEST names, :TEST tags, no credential.
 */
final class ClusterUnits {

    static final String PUBLISHER_IMAGE = "spire-publisher:TEST";

    static final String EGRESS_IMAGE = "spire-egress-proxy:TEST";

    /** The egress image with a fetch proxy that accepts the close and keeps answering anyway. */
    static final String STUBBORN_EGRESS_IMAGE = "spire-test-stubborn-egress:TEST";

    static final List<String> SHELL = List.of("/bin/sh", "-c");

    /** Touches the fetch proxy's close port, as the real fetch does as its last act. */
    static final String CLOSING_FETCH = "nc -w 2 " + RunUnitSpec.FETCH_PROXY_ALIAS + " " + RunUnitSpec.FETCH_CLOSE_PORT
            + " </dev/null; echo TEST-fetched";

    /** A model proxy stand-in: it accepts every connection on the model proxy's port and says nothing. */
    static final String LISTENING_MODEL_PROXY = "nc -ll -p " + RunUnitSpec.MODEL_PROXY_PORT + " -e /bin/true";

    private ClusterUnits() {
    }

    static String runId() {
        return "TEST-run-" + UUID.randomUUID();
    }

    /** The cluster's API service address, the unit pod's second canary. */
    static String apiAddress() {
        Service api = KindCluster.client().services().inNamespace("default").withName("kubernetes").get();
        return api.getSpec().getClusterIP() + ":" + api.getSpec().getPorts().getFirst().getPort();
    }

    static KubernetesRuntimeConfig config(String namespace) {
        return config(namespace, EGRESS_IMAGE, List.of(KindCluster.canaryTarget()), apiAddress());
    }

    static KubernetesRuntimeConfig config(String namespace, String egressImage, List<String> canaries,
                                          String apiCanary) {
        return new KubernetesRuntimeConfig(namespace, PUBLISHER_IMAGE, egressImage, canaries, apiCanary,
                KindCluster.dnsAddress(), "kube-system", Map.of("k8s-app", "kube-dns"), List.of(), List.of(), 5,
                Duration.ofMillis(500), Duration.ofSeconds(60), Duration.ofSeconds(180), Duration.ofSeconds(120),
                "50m", "64Mi");
    }

    static KubernetesRunRuntime runtime(KubernetesRuntimeConfig config) {
        return new KubernetesRunRuntime(KindCluster.client(), config,
                new RegistryImageReader(HttpClient.newHttpClient(), null));
    }

    static RunUnitSpec unit(String runId, String agentScript) {
        return unit(runId, agentScript, CLOSING_FETCH, Map.of("context.md", "TEST context"));
    }

    static RunUnitSpec unit(String runId, String agentScript, String fetchScript, Map<String, String> inputs) {
        List<Mount> initMounts = List.of(Mount.writable("work", "/work"),
                Mount.writable(TestUnits.INPUTS_VOLUME, TestUnits.INPUTS_PATH));
        List<Mount> agentMounts = List.of(Mount.readOnly("work", "/work"),
                Mount.readOnly(TestUnits.INPUTS_VOLUME, TestUnits.INPUTS_PATH), Mount.writable("handoff", "/handoff"));
        ContainerSpec init = new ContainerSpec(KindCluster.UNIT_IMAGE, List.of(fetchScript),
                Map.of("TEST_FETCH", "TEST-value"), initMounts, SHELL);
        ContainerSpec agent = new ContainerSpec(KindCluster.UNIT_IMAGE, List.of(agentScript),
                Map.of("TEST_AGENT", "TEST-value"), agentMounts, SHELL);
        ContainerSpec publisher = new ContainerSpec(PUBLISHER_IMAGE, List.of(), Map.of("TEST_PUBLISHER", "TEST-value"),
                List.of(Mount.readOnly("handoff", "/handoff")), List.of("spire-publish-none"));
        ContainerSpec modelProxy = new ContainerSpec(KindCluster.UNIT_IMAGE, List.of(LISTENING_MODEL_PROXY),
                Map.of("TEST_MODEL", "TEST-value"), List.of(), SHELL);
        EgressPolicy egress = new EgressPolicy(EgressPolicy.Mode.ENFORCE, Set.of("api.test-vendor.example"));
        RunUnitSpec spec = new RunUnitSpec(runId, init, agent, publisher, EnterpriseEnvironment.NONE, egress,
                256L << 20, 500_000_000L, 256L << 20, Duration.ofMinutes(5));
        return spec.withModelProxy(modelProxy)
                .withInputs(new UnitInputs(TestUnits.INPUTS_VOLUME, TestUnits.INPUTS_PATH, new LinkedHashMap<>(inputs)))
                .withFetchHosts(Set.of("test-forge.example"));
    }

    static List<String> attach(KubernetesRunRuntime runtime, RunHandle handle, LogChannel channel) {
        List<String> lines = new ArrayList<>();
        runtime.attach(handle, channel, lines::add);
        return lines;
    }

    static Pod pod(String namespace, String name) {
        return KindCluster.client().pods().inNamespace(namespace).withName(name).get();
    }

    static Pod awaitEnded(String namespace, String name) {
        return KindCluster.client().pods().inNamespace(namespace).withName(name)
                .waitUntilCondition(p -> p != null && p.getStatus() != null
                        && ("Succeeded".equals(p.getStatus().getPhase()) || "Failed".equals(p.getStatus().getPhase())),
                        120, TimeUnit.SECONDS);
    }

    /** True when the pod is absent, or its agent container has never been running or finished. */
    static boolean agentNeverStarted(Pod unitPod) {
        if (unitPod == null) {
            return true;
        }
        for (ContainerStatus status : UnitStartFailure.statuses(unitPod)) {
            if (!UnitPod.AGENT.equals(status.getName())) {
                continue;
            }
            boolean now = status.getState() != null
                    && (status.getState().getRunning() != null || status.getState().getTerminated() != null);
            boolean before = status.getLastState() != null
                    && (status.getLastState().getRunning() != null || status.getLastState().getTerminated() != null);
            return !now && !before;
        }
        return true;
    }

    static void deleteNamespace(String namespace) {
        if (namespace != null) {
            KindCluster.client().namespaces().withName(namespace).delete();
        }
    }
}

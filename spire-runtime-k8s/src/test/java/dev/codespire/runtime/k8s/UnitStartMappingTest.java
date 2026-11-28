package dev.codespire.runtime.k8s;

import dev.codespire.runtime.RunHandle;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@code create} reads the unit pod's own start, against the fabric8 mock API server. No kubelet runs here, so
 * the test writes the pod statuses a kubelet would: the proxies started, then an init container of the unit pod
 * ended. The mock server answers a log read with the pod itself, not a container log, so only the cause is checked.
 */
@EnableKubernetesMockClient(crud = true)
class UnitStartMappingTest {

    private static final String NAMESPACE = PodFixtures.CONFIG.namespace();

    private static final Duration WAIT_FOR_POD = Duration.ofSeconds(20);

    KubernetesClient client;

    /** Short waits: 2 s for the proxies, and for the agent 2 + 1 + 1 s. */
    private KubernetesRunRuntime runtime() {
        KubernetesRuntimeConfig base = PodFixtures.CONFIG;
        KubernetesRuntimeConfig config = new KubernetesRuntimeConfig(base.namespace(), base.publisherImage(),
                base.egressImage(), base.sealCanaries(), base.apiCanary(), base.dnsAddress(), base.dnsNamespace(),
                base.dnsLabels(), base.extraAllowedCidrs(), base.extraDeniedCidrs(), base.sealRounds(),
                base.sealTimeout(), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1),
                base.agentRequestCpu(), base.agentRequestMemory());
        return new KubernetesRunRuntime(client, config, new RegistryImageReader(HttpClient.newHttpClient(), null));
    }

    private CompletableFuture<RunHandle> createInBackground(String runId) {
        KubernetesRunRuntime runtime = runtime();
        return CompletableFuture.supplyAsync(() -> runtime.create(TestUnits.proxiedReview(runId)));
    }

    private UnitNotStarted notStarted(CompletableFuture<RunHandle> creating) {
        ExecutionException failed = assertThrows(ExecutionException.class, () -> creating.get(30, TimeUnit.SECONDS));
        return assertInstanceOf(UnitNotStarted.class, failed.getCause());
    }

    private void awaitPod(String name) {
        long deadline = System.nanoTime() + WAIT_FOR_POD.toNanos();
        while (client.pods().inNamespace(NAMESPACE).withName(name).get() == null) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the pod " + name + " was never created");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
    }

    private void setStatus(String name, String phase, List<ContainerStatus> inits, List<ContainerStatus> containers) {
        awaitPod(name);
        client.pods().inNamespace(NAMESPACE).withName(name).editStatus(pod -> new PodBuilder(pod)
                .withNewStatus()
                .withPhase(phase)
                .withPodIP(PodFixtures.PROXY_IP)
                .withInitContainerStatuses(inits)
                .withContainerStatuses(containers)
                .endStatus()
                .build());
    }

    private void markProxiesStarted(String runId) {
        setStatus(UnitNames.proxyPod(runId), "Running", List.of(ended(ProxyPod.SEAL, 0)),
                List.of(started(ProxyPod.MODEL_PROXY), started(ProxyPod.EGRESS_PROXY)));
    }

    private static ContainerStatus started(String name) {
        return new ContainerStatusBuilder().withName(name).withStarted(true)
                .withNewState().withNewRunning().endRunning().endState().build();
    }

    private static ContainerStatus ended(String name, int exit) {
        return new ContainerStatusBuilder().withName(name)
                .withNewState().withNewTerminated().withExitCode(exit).endTerminated().endState().build();
    }

    private static ContainerStatus initializing(String name) {
        return new ContainerStatusBuilder().withName(name)
                .withNewState().withNewWaiting().withReason("PodInitializing").endWaiting().endState().build();
    }

    private List<Pod> unitPods(String runId) {
        return client.pods().inNamespace(NAMESPACE).withLabel(UnitNames.UNIT_LABEL, UnitNames.digest(runId))
                .list().getItems();
    }

    @Test
    void theUnitPodsOwnSealFailureIsNetworkPolicyNotEnforced() {
        String runId = "TEST-run-unit-seal";
        CompletableFuture<RunHandle> creating = createInBackground(runId);
        markProxiesStarted(runId);
        setStatus(UnitNames.unitPod(runId), "Failed", List.of(ended(UnitPod.SEAL, UnitStart.SEAL_NOT_SEALED)),
                List.of(initializing(UnitPod.AGENT)));

        UnitNotStarted failed = notStarted(creating);

        assertTrue(failed.detail().startsWith(UnitStart.POLICY_NOT_ENFORCED), failed.detail());
    }

    @Test
    void aFetchProxyStillOpenIsFetchProxyNotClosed() {
        String runId = "TEST-run-fetch-open";
        CompletableFuture<RunHandle> creating = createInBackground(runId);
        markProxiesStarted(runId);
        setStatus(UnitNames.unitPod(runId), "Failed", List.of(ended(UnitPod.SEAL, 0), ended(UnitPod.FETCH, 0),
                ended(UnitPod.FETCH_CLOSED, UnitStart.FETCH_NOT_CLOSED)), List.of(initializing(UnitPod.AGENT)));

        UnitNotStarted failed = notStarted(creating);

        assertTrue(failed.detail().startsWith(UnitStart.FETCH_PROXY_NOT_CLOSED), failed.detail());
    }

    @Test
    void anAgentThatDoesNotStartInTimeLeavesNoPodButKeepsTheAnchor() {
        String runId = "TEST-run-agent-late";
        CompletableFuture<RunHandle> creating = createInBackground(runId);
        markProxiesStarted(runId);
        awaitPod(UnitNames.unitPod(runId));

        UnitNotStarted failed = notStarted(creating);

        assertTrue(failed.detail().contains("did not start within"), failed.detail());
        assertEquals(List.of(), unitPods(runId), "a pod that could still start its agent was left behind");
        List<ConfigMap> anchors = client.configMaps().inNamespace(NAMESPACE)
                .withLabel(UnitNames.UNIT_LABEL, UnitNames.digest(runId)).list().getItems();
        assertEquals(1, anchors.size(), "the anchor keeps the unit discoverable");
        assertEquals(UnitNames.secretNames(runId).size(),
                client.secrets().inNamespace(NAMESPACE).list().getItems().size(), "the Secrets stay for destroy");
    }
}

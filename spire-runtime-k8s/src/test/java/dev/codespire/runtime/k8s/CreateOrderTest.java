package dev.codespire.runtime.k8s;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order in which {@code create} makes a unit, against the fabric8 mock API server. No kubelet runs here, so a
 * pod never starts: these tests cover what happens before and around the waits, not what a pod does.
 */
@EnableKubernetesMockClient(crud = true)
class CreateOrderTest {

    private static final String NAMESPACE = PodFixtures.CONFIG.namespace();

    private static final Duration SHORT_START = Duration.ofSeconds(2);

    KubernetesMockServer server;

    KubernetesClient client;

    private KubernetesRunRuntime runtime(Duration unitStartTimeout) {
        KubernetesRuntimeConfig base = PodFixtures.CONFIG;
        KubernetesRuntimeConfig config = new KubernetesRuntimeConfig(base.namespace(), base.publisherImage(),
                base.egressImage(), base.sealCanaries(), base.apiCanary(), base.dnsAddress(), base.dnsNamespace(),
                base.dnsLabels(), base.extraAllowedCidrs(), base.extraDeniedCidrs(), base.sealRounds(),
                base.sealTimeout(), base.sealDeadline(), unitStartTimeout, base.initTimeout(),
                base.agentRequestCpu(), base.agentRequestMemory());
        return new KubernetesRunRuntime(client, config, new RegistryImageReader(HttpClient.newHttpClient(), null));
    }

    private int objectCount() {
        return client.configMaps().inNamespace(NAMESPACE).list().getItems().size()
                + client.secrets().inNamespace(NAMESPACE).list().getItems().size()
                + client.network().v1().networkPolicies().inNamespace(NAMESPACE).list().getItems().size()
                + client.pods().inNamespace(NAMESPACE).list().getItems().size();
    }

    @Test
    void theAnchorExistsBeforeAnySecret() {
        server.expect().post().withPath("/api/v1/namespaces/" + NAMESPACE + "/secrets")
                .andReturn(500, new StatusBuilder().withCode(500).withMessage("TEST refused").build()).once();
        String runId = "TEST-run-anchor-first";

        assertThrows(KubernetesClientException.class,
                () -> runtime(SHORT_START).create(TestUnits.proxiedReview(runId)));

        assertNotNull(client.configMaps().inNamespace(NAMESPACE).withName(UnitNames.anchor(runId)).get(),
                "the anchor must exist before the first Secret is attempted, so a unit that fails there is found");
        assertEquals(0, client.secrets().inNamespace(NAMESPACE).list().getItems().size());
    }

    @Test
    void aRefusedUnitCreatesNothing() {
        UnitRefused refused = assertThrows(UnitRefused.class,
                () -> runtime(SHORT_START).create(TestUnits.withoutModelProxy("TEST-run-refused")));

        assertEquals("kubernetes_requires_credential_proxy", refused.detail());
        assertEquals(0, objectCount(), "a refused unit leaves no object behind");
    }

    @Test
    void aSecondCreateForTheSameRunIdFailsAndAdoptsNothing() {
        String runId = "TEST-run-twice";
        ConfigMap existing = new ConfigMapBuilder()
                .withNewMetadata().withName(UnitNames.anchor(runId)).withNamespace(NAMESPACE)
                .withLabels(UnitNames.labels(runId, KubernetesRunRuntime.ANCHOR_ROLE)).endMetadata()
                .build();
        client.configMaps().inNamespace(NAMESPACE).resource(existing).create();

        IllegalStateException twice = assertThrows(IllegalStateException.class,
                () -> runtime(SHORT_START).create(TestUnits.proxiedReview(runId)));

        assertTrue(twice.getMessage().contains("already exists"), twice.getMessage());
        assertEquals(1, objectCount(), "only the anchor that was there before; nothing was added to it");
    }

    @Test
    void aProxyPodThatNeverStartsFailsWithinTheStartTimeoutAndLeavesTheUnitDiscoverable() {
        String runId = "TEST-run-never-starts";
        long started = System.nanoTime();

        UnitNotStarted notStarted = assertThrows(UnitNotStarted.class,
                () -> runtime(SHORT_START).create(TestUnits.proxiedReview(runId)));

        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(took.compareTo(SHORT_START.plusSeconds(10)) < 0, "the wait was not bounded: " + took);
        assertTrue(notStarted.detail().contains("did not start within " + SHORT_START), notStarted.detail());
        List<ConfigMap> anchors = client.configMaps().inNamespace(NAMESPACE)
                .withLabel(UnitNames.UNIT_LABEL, UnitNames.digest(runId)).list().getItems();
        assertEquals(1, anchors.size(), "the anchor is still listed by the unit label");
        assertEquals(runId, anchors.getFirst().getMetadata().getAnnotations().get(UnitNames.RUN_ID_ANNOTATION));
        assertEquals(String.valueOf(TestUnits.proxiedReview(runId).wallClock().toSeconds()),
                anchors.getFirst().getMetadata().getAnnotations().get(KubernetesRunRuntime.WALL_CLOCK_ANNOTATION));
        assertEquals(0, client.pods().inNamespace(NAMESPACE).withName(UnitNames.unitPod(runId)).list()
                .getItems().size(), "no unit pod before the proxies start");
    }
}

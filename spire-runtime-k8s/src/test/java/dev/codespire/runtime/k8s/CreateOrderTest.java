package dev.codespire.runtime.k8s;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
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
        // The crud mock answers every request itself, so the first Secret create is made to fail the way a real API
        // server would: a Secret of that name is already there, and the create meets 409.
        String runId = "TEST-run-anchor-first";
        String firstSecret = UnitNames.secretNames(runId).getFirst();
        client.secrets().inNamespace(NAMESPACE).resource(new SecretBuilder()
                .withNewMetadata().withName(firstSecret).withNamespace(NAMESPACE).endMetadata().build()).create();

        KubernetesClientException refused = assertThrows(KubernetesClientException.class,
                () -> runtime(SHORT_START).create(TestUnits.proxiedReview(runId)));

        assertEquals(409, refused.getCode(), refused.getMessage());
        assertNotNull(client.configMaps().inNamespace(NAMESPACE).withName(UnitNames.anchor(runId)).get(),
                "the anchor must exist before the first Secret is attempted, so a unit that fails there is found");
        assertEquals(List.of(firstSecret), client.secrets().inNamespace(NAMESPACE).list().getItems().stream()
                .map(secret -> secret.getMetadata().getName()).toList(), "no Secret after the failed one");
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
        assertEquals(null, client.pods().inNamespace(NAMESPACE).withName(UnitNames.unitPod(runId)).get(),
                "no unit pod before the proxies start");
    }
}

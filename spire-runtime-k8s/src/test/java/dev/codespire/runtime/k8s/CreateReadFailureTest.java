package dev.codespire.runtime.k8s;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An API server that refuses a read while {@code create} waits: the failure is a unit that did not start (the
 * runtime is unavailable), never a raw client error. The mock server answers only the requests set up here. The
 * refusal is a 403 (RBAC), not a 5xx, because the client retries a 5xx with backoff.
 */
@EnableKubernetesMockClient
class CreateReadFailureTest {

    private static final String NAMESPACE = PodFixtures.CONFIG.namespace();

    private static final String RUN = "TEST-run-read-fails";

    KubernetesMockServer server;

    KubernetesClient client;

    private String path(String kind) {
        return "/api/v1/namespaces/" + NAMESPACE + "/" + kind;
    }

    @Test
    void aRefusedPodReadDuringTheWaitIsUnitNotStarted() {
        server.expect().post().withPath(path("configmaps")).andReturn(201, new ConfigMapBuilder()
                .withNewMetadata().withName(UnitNames.anchor(RUN)).withUid("TEST-uid").endMetadata().build())
                .always();
        server.expect().post().withPath(path("secrets"))
                .andReturn(201, new SecretBuilder().withNewMetadata().withName("TEST").endMetadata().build())
                .always();
        server.expect().post().withPath("/apis/networking.k8s.io/v1/namespaces/" + NAMESPACE + "/networkpolicies")
                .andReturn(201, new NetworkPolicyBuilder().withNewMetadata().withName("TEST").endMetadata().build())
                .always();
        server.expect().post().withPath(path("pods"))
                .andReturn(201, new PodBuilder().withNewMetadata().withName("TEST").endMetadata().build())
                .always();
        server.expect().get().withPath(path("pods") + "/" + UnitNames.proxyPod(RUN))
                .andReturn(403, new StatusBuilder().withCode(403).withMessage("TEST forbidden").build())
                .always();
        KubernetesRunRuntime runtime = new KubernetesRunRuntime(client, PodFixtures.CONFIG,
                new RegistryImageReader(HttpClient.newHttpClient(), null));

        UnitNotStarted failed = assertThrows(UnitNotStarted.class, () -> runtime.create(TestUnits.proxiedReview(RUN)));

        assertTrue(failed.detail().startsWith("could not read the pod " + UnitNames.proxyPod(RUN)), failed.detail());
    }
}

package dev.codespire.runtime.k8s;

import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code salvage}, {@code cancel}, {@code destroy} and {@code discoverUnits} against the fabric8 mock API server. No
 * kubelet runs here: a unit is seeded with the arm's own builders, and the test writes the pod statuses a kubelet
 * would.
 */
@EnableKubernetesMockClient(crud = true)
class LifecycleTest {

    private static final String NAMESPACE = PodFixtures.CONFIG.namespace();

    private static final Duration WAIT_FOR_POD = Duration.ofSeconds(20);

    KubernetesClient client;

    /** Short waits, as in UnitStartMappingTest: 2 s for the proxies, and for the agent 2 + 1 + 1 s. */
    private KubernetesRunRuntime runtime() {
        KubernetesRuntimeConfig base = PodFixtures.CONFIG;
        KubernetesRuntimeConfig config = new KubernetesRuntimeConfig(base.namespace(), base.publisherImage(),
                base.egressImage(), base.sealCanaries(), base.apiCanary(), base.dnsAddress(), base.dnsNamespace(),
                base.dnsLabels(), base.extraAllowedCidrs(), base.extraDeniedCidrs(), base.sealRounds(),
                base.sealTimeout(), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1),
                base.agentRequestCpu(), base.agentRequestMemory());
        return new KubernetesRunRuntime(client, config, new RegistryImageReader(HttpClient.newHttpClient(), null));
    }

    private static RunHandle handle(String runId) {
        return new RunHandle(runId, UnitNames.anchor(runId));
    }

    // ---- seeding: a whole unit, made with the arm's own builders ----

    private void seedAnchor(String runId) {
        client.configMaps().inNamespace(NAMESPACE).resource(new ConfigMapBuilder()
                .withNewMetadata().withName(UnitNames.anchor(runId)).withNamespace(NAMESPACE)
                .withLabels(UnitNames.labels(runId, KubernetesRunRuntime.ANCHOR_ROLE))
                .withAnnotations(Map.of(UnitNames.RUN_ID_ANNOTATION, runId,
                        KubernetesRunRuntime.WALL_CLOCK_ANNOTATION, "600"))
                .endMetadata().build()).create();
    }

    private void seedSecrets(String runId) {
        for (Secret secret : UnitSecrets.of(TestUnits.proxiedReview(runId), PodFixtures.CONFIG, owner(runId))) {
            client.secrets().inNamespace(NAMESPACE).resource(secret).create();
        }
    }

    private void seedUnit(String runId) {
        RunUnitSpec spec = TestUnits.proxiedReview(runId);
        OwnerReference owner = owner(runId);
        seedAnchor(runId);
        seedSecrets(runId);
        client.network().v1().networkPolicies().inNamespace(NAMESPACE)
                .resource(UnitPolicies.unit(runId, PodFixtures.CONFIG, owner)).create();
        client.network().v1().networkPolicies().inNamespace(NAMESPACE)
                .resource(UnitPolicies.proxy(runId, PodFixtures.CONFIG, owner)).create();
        client.pods().inNamespace(NAMESPACE).resource(ProxyPod.of(spec, PodFixtures.CONFIG, owner)).create();
        client.pods().inNamespace(NAMESPACE)
                .resource(UnitPod.of(spec, PodFixtures.CONFIG, owner, PodFixtures.PROXY_IP)).create();
    }

    private static OwnerReference owner(String runId) {
        return new OwnerReferenceBuilder().withApiVersion("v1").withKind("ConfigMap")
                .withName(UnitNames.anchor(runId)).withUid("TEST-uid").build();
    }

    /** Every object of the unit that can be listed (Secrets are counted by name). */
    private List<HasMetadata> objectsOf(String runId) {
        String digest = UnitNames.digest(runId);
        List<HasMetadata> objects = new ArrayList<>();
        objects.addAll(client.configMaps().inNamespace(NAMESPACE).withLabel(UnitNames.UNIT_LABEL, digest).list()
                .getItems());
        objects.addAll(client.network().v1().networkPolicies().inNamespace(NAMESPACE)
                .withLabel(UnitNames.UNIT_LABEL, digest).list().getItems());
        objects.addAll(client.pods().inNamespace(NAMESPACE).withLabel(UnitNames.UNIT_LABEL, digest).list()
                .getItems());
        objects.addAll(secretsOf(runId));
        return objects;
    }

    private List<Secret> secretsOf(String runId) {
        List<Secret> secrets = new ArrayList<>();
        for (String name : UnitNames.secretNames(runId)) {
            Secret secret = client.secrets().inNamespace(NAMESPACE).withName(name).get();
            if (secret != null) {
                secrets.add(secret);
            }
        }
        return secrets;
    }

    private ConfigMap anchorOf(String runId) {
        return client.configMaps().inNamespace(NAMESPACE).withName(UnitNames.anchor(runId)).get();
    }

    // ---- a stand-in kubelet ----

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

    private void setStatus(String name, String phase, String reason, List<ContainerStatus> inits,
                           List<ContainerStatus> containers) {
        awaitPod(name);
        client.pods().inNamespace(NAMESPACE).withName(name).editStatus(pod -> new PodBuilder(pod)
                .withNewStatus()
                .withPhase(phase)
                .withReason(reason)
                .withPodIP(PodFixtures.PROXY_IP)
                .withInitContainerStatuses(inits)
                .withContainerStatuses(containers)
                .endStatus()
                .build());
    }

    private void markProxiesStarted(String runId) {
        setStatus(UnitNames.proxyPod(runId), "Running", null, List.of(ended(ProxyPod.SEAL, 0)),
                List.of(started(ProxyPod.MODEL_PROXY), started(ProxyPod.EGRESS_PROXY)));
    }

    /** The unit pod after its agent and publisher ended, with the publisher's and the agent's exit codes. */
    private void markUnitEnded(String runId, String phase, String reason, int publisherExit, int agentExit) {
        setStatus(UnitNames.unitPod(runId), phase, reason,
                List.of(ended(UnitPod.SEAL, 0), ended(UnitPod.FETCH, 0), ended(UnitPod.FETCH_CLOSED, 0),
                        ended(UnitPod.PUBLISHER, publisherExit)),
                List.of(ended(UnitPod.AGENT, agentExit)));
    }

    private static ContainerStatus started(String name) {
        return new ContainerStatusBuilder().withName(name).withStarted(true)
                .withNewState().withNewRunning().endRunning().endState().build();
    }

    private static ContainerStatus ended(String name, int exit) {
        return new ContainerStatusBuilder().withName(name)
                .withNewState().withNewTerminated().withExitCode(exit).endTerminated().endState().build();
    }

    private CompletableFuture<RunHandle> createInBackground(String runId) {
        KubernetesRunRuntime runtime = runtime();
        return CompletableFuture.supplyAsync(() -> runtime.create(TestUnits.proxiedReview(runId)));
    }

    private static RuntimeException failureOf(CompletableFuture<RunHandle> creating) {
        ExecutionException failed = assertThrows(ExecutionException.class, () -> creating.get(30, TimeUnit.SECONDS));
        return assertInstanceOf(RuntimeException.class, failed.getCause());
    }

    // ---- discover and destroy ----

    @Test
    void aUnitLeftHalfMadeIsDiscoveredAndDestroyed() {
        String runId = "TEST-run-half-made";
        seedAnchor(runId);
        seedSecrets(runId);
        KubernetesRunRuntime runtime = runtime();

        assertEquals(List.of(handle(runId)), runtime.discoverUnits());

        runtime.destroy(handle(runId));

        assertEquals(List.of(), runtime.discoverUnits());
        assertEquals(List.of(), objectsOf(runId), "nothing of the unit is left");
    }

    @Test
    void destroyDeletesEverySecretByName() {
        // Unlabelled, so only a delete by name can find them: the arm may not list Secrets.
        String runId = "TEST-run-secrets-by-name";
        seedAnchor(runId);
        for (String name : UnitNames.secretNames(runId)) {
            client.secrets().inNamespace(NAMESPACE).resource(new SecretBuilder()
                    .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata().build()).create();
        }
        KubernetesRunRuntime runtime = runtime();

        runtime.destroy(handle(runId));

        assertEquals(List.of(), secretsOf(runId), "every one of the seven names was deleted");
        assertDoesNotThrow(() -> runtime.destroy(handle(runId)), "a 404 on each name is not an error");
    }

    @Test
    void destroyTouchesNothingOfAnotherUnit() {
        String destroyed = "TEST-run-destroyed";
        String kept = "TEST-run-kept";
        seedUnit(destroyed);
        seedUnit(kept);
        int keptBefore = objectsOf(kept).size();

        runtime().destroy(handle(destroyed));

        assertEquals(List.of(), objectsOf(destroyed));
        assertEquals(keptBefore, objectsOf(kept).size(), "destroy reached another unit's objects");
        assertEquals(List.of(handle(kept)), runtime().discoverUnits());
    }

    // ---- cancel ----

    @Test
    void cancelDeletesBothPodsAndTheSecretsAndKeepsTheAnchor() {
        String runId = "TEST-run-cancelled";
        seedUnit(runId);

        runtime().cancel(handle(runId));

        assertNull(client.pods().inNamespace(NAMESPACE).withName(UnitNames.proxyPod(runId)).get());
        assertNull(client.pods().inNamespace(NAMESPACE).withName(UnitNames.unitPod(runId)).get());
        assertEquals(List.of(), secretsOf(runId), "a cancelled unit keeps no credential");
        ConfigMap anchor = anchorOf(runId);
        assertNotNull(anchor, "the anchor stays, so the unit is still discoverable");
        assertEquals(UnitTeardown.CANCELLED,
                anchor.getMetadata().getAnnotations().get(UnitTeardown.CANCELLED_ANNOTATION));
        assertEquals(List.of(handle(runId)), runtime().discoverUnits());
    }

    @Test
    void cancelTwiceDoesNothingTheSecondTime() {
        String runId = "TEST-run-cancelled-twice";
        seedUnit(runId);
        KubernetesRunRuntime runtime = runtime();
        runtime.cancel(handle(runId));
        String version = anchorOf(runId).getMetadata().getResourceVersion();
        int left = objectsOf(runId).size();

        assertDoesNotThrow(() -> runtime.cancel(handle(runId)));

        assertEquals(version, anchorOf(runId).getMetadata().getResourceVersion(), "the anchor was written again");
        assertEquals(left, objectsOf(runId).size());
    }

    @Test
    void cancelBeforeTheUnitPodExistsStopsCreateFromMakingIt() {
        // A cancel marks the anchor before it stops anything. This is the moment between the two: the mark is set,
        // and create's proxy pod has started.
        String runId = "TEST-run-cancel-early";
        CompletableFuture<RunHandle> creating = createInBackground(runId);
        awaitPod(UnitNames.proxyPod(runId));
        client.configMaps().inNamespace(NAMESPACE).withName(UnitNames.anchor(runId)).edit(anchor ->
                new ConfigMapBuilder(anchor).editMetadata()
                        .addToAnnotations(UnitTeardown.CANCELLED_ANNOTATION, UnitTeardown.CANCELLED)
                        .endMetadata().build());
        markProxiesStarted(runId);

        UnitNotStarted stopped = assertInstanceOf(UnitNotStarted.class, failureOf(creating));

        assertEquals(KubernetesRunRuntime.CANCELLED_BEFORE_START, stopped.detail());
        assertNull(client.pods().inNamespace(NAMESPACE).withName(UnitNames.proxyPod(runId)).get(),
                "the proxy pod, which holds the model credential, was left running");
    }

    // ---- create's own cleanup ----

    @Test
    void theFetchSecretIsDeletedOnceFetchHasEnded() throws Exception {
        String runId = "TEST-run-fetch-secret";
        CompletableFuture<RunHandle> creating = createInBackground(runId);
        markProxiesStarted(runId);
        setStatus(UnitNames.unitPod(runId), "Running", null,
                List.of(ended(UnitPod.SEAL, 0), ended(UnitPod.FETCH, 0), ended(UnitPod.FETCH_CLOSED, 0),
                        started(UnitPod.PUBLISHER)),
                List.of(started(UnitPod.AGENT)));

        assertEquals(handle(runId), creating.get(30, TimeUnit.SECONDS));

        String fetch = UnitNames.environmentSecret(runId, "fetch");
        assertNull(client.secrets().inNamespace(NAMESPACE).withName(fetch).get(), "the forge credential stayed");
        assertEquals(UnitNames.secretNames(runId).size() - 1, secretsOf(runId).size(), "only the fetch Secret goes");
    }

    @Test
    void aFetchThatFailsStopsBothPods() {
        String runId = "TEST-run-fetch-fails";
        CompletableFuture<RunHandle> creating = createInBackground(runId);
        markProxiesStarted(runId);
        setStatus(UnitNames.unitPod(runId), "Failed", null,
                List.of(ended(UnitPod.SEAL, 0), ended(UnitPod.FETCH, 1)), List.of());

        assertInstanceOf(IllegalStateException.class, failureOf(creating));

        assertEquals(List.of(), client.pods().inNamespace(NAMESPACE)
                .withLabel(UnitNames.UNIT_LABEL, UnitNames.digest(runId)).list().getItems(),
                "a pod of a failed create was left running");
        assertNotNull(anchorOf(runId), "the anchor stays for destroy");
    }

    @Test
    void everyUnitObjectBlocksItsAnchorsDeletion() {
        String runId = "TEST-run-owner";
        assertThrows(UnitNotStarted.class, () -> runtime().create(TestUnits.proxiedReview(runId)));

        List<HasMetadata> owned = new ArrayList<>(secretsOf(runId));
        owned.addAll(client.network().v1().networkPolicies().inNamespace(NAMESPACE)
                .withLabel(UnitNames.UNIT_LABEL, UnitNames.digest(runId)).list().getItems());
        assertEquals(UnitNames.secretNames(runId).size() + 2, owned.size());
        for (HasMetadata object : owned) {
            OwnerReference reference = object.getMetadata().getOwnerReferences().getFirst();
            assertEquals(Boolean.TRUE, reference.getBlockOwnerDeletion(), object.getMetadata().getName());
            assertEquals(Boolean.TRUE, reference.getController(), object.getMetadata().getName());
        }
    }

    // ---- salvage ----

    @Test
    void salvageMapsTheAgentsExitCode() {
        String runId = "TEST-run-exit-3";
        seedUnit(runId);
        markUnitEnded(runId, "Failed", null, 0, 3);

        assertEquals(Finalization.salvaged(3, "agent exited 3"), runtime().salvage(handle(runId)));
    }

    @Test
    void aPublisherStoppedBeforeDoneIsFaulted() {
        String runId = "TEST-run-no-done";
        seedUnit(runId);
        markUnitEnded(runId, "Succeeded", null, UnitOutcome.PUBLISHER_TERM_BEFORE_DONE, 0);

        assertEquals(Finalization.faulted("the publisher was stopped before the agent wrote DONE"),
                runtime().salvage(handle(runId)));
    }

    @Test
    void aPodPastItsDeadlineOverran() {
        String runId = "TEST-run-overran";
        seedUnit(runId);
        markUnitEnded(runId, "Failed", UnitOutcome.DEADLINE_EXCEEDED, UnitOutcome.PUBLISHER_TERM_BEFORE_DONE, 137);

        Finalization finalization = runtime().salvage(handle(runId));

        assertEquals(Finalization.Outcome.OVERRAN, finalization.outcome(), finalization.detail());
    }

    @Test
    void aMissingPodVanished() {
        String runId = "TEST-run-no-pod";
        seedAnchor(runId);

        Finalization finalization = runtime().salvage(handle(runId));

        assertEquals(Finalization.Outcome.VANISHED, finalization.outcome(), finalization.detail());
    }

    @Test
    void aFailedProxyPodIsNamedInTheOutcome() {
        String runId = "TEST-run-proxy-evicted";
        seedUnit(runId);
        setStatus(UnitNames.proxyPod(runId), "Failed", "Evicted", List.of(), List.of());
        markUnitEnded(runId, "Failed", null, 0, 1);

        Finalization finalization = runtime().salvage(handle(runId));

        assertEquals(1, finalization.exitCode());
        assertTrue(finalization.detail().contains(UnitNames.proxyPod(runId) + " failed (Evicted)"),
                finalization.detail());
    }

    @Test
    void salvageDeletesNothing() {
        String runId = "TEST-run-salvage-keeps";
        seedUnit(runId);
        markUnitEnded(runId, "Succeeded", null, 0, 0);
        int before = objectsOf(runId).size();

        runtime().salvage(handle(runId));

        assertEquals(before, objectsOf(runId).size());
    }
}

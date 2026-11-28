package dev.codespire.runtime.k8s;

import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunRuntimeContract;
import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared {@link RunRuntimeContract} on the Kubernetes arm, on the kind cluster that enforces network policy, and
 * the lifecycle rules only a real kubelet can show: cancel stops the pods, destroy leaves nothing, attach ends.
 *
 * <p>The quiet unit's agent sleeps far past its wall clock, so it stays alive for every test that needs a unit that
 * still exists, and a salvage of it ends at the pod's deadline. The init timeout is shorter than the other kind tests'
 * so that deadline (init timeout plus wall clock) comes in about 80 s.
 */
@Tag("kind")
class KubernetesRunRuntimeIT extends RunRuntimeContract {

    private static final Duration QUIET_WALL_CLOCK = Duration.ofSeconds(20);

    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(60);

    private static final String SLEEPING_AGENT = "sleep 600";

    /** A close ends the read at once; a stream left to end on its own waits for the 5 s stop grace and the kill. */
    private static final Duration ATTACH_ENDS_WITHIN = Duration.ofSeconds(4);

    private static String namespace;

    private static KubernetesRunRuntime runtime;

    @BeforeAll
    static void startTheArm() {
        namespace = KindCluster.newNamespace("test-contract");
        KubernetesRuntimeConfig base = ClusterUnits.config(namespace);
        KubernetesRuntimeConfig config = new KubernetesRuntimeConfig(base.namespace(), base.publisherImage(),
                base.egressImage(), base.sealCanaries(), base.apiCanary(), base.dnsAddress(), base.dnsNamespace(),
                base.dnsLabels(), base.extraAllowedCidrs(), base.extraDeniedCidrs(), base.sealRounds(),
                base.sealTimeout(), base.sealDeadline(), base.unitStartTimeout(), INIT_TIMEOUT,
                base.agentRequestCpu(), base.agentRequestMemory());
        runtime = ClusterUnits.runtime(config);
    }

    @AfterAll
    static void removeTheNamespace() {
        ClusterUnits.deleteNamespace(namespace);
    }

    @Override
    protected RunRuntime runtime() {
        return runtime;
    }

    @Override
    protected RunUnitSpec quietUnit(String runId) {
        return ClusterUnits.unit(runId, SLEEPING_AGENT, QUIET_WALL_CLOCK);
    }

    @Override
    protected RunUnitSpec echoingUnit(String runId, String marker) {
        return ClusterUnits.unit(runId, "echo " + marker);
    }

    private static KubernetesClient client() {
        return KindCluster.client();
    }

    @Test
    void afterDestroyNoObjectCarriesTheUnitLabelAndNoSecretNameResolves() {
        String runId = ClusterUnits.runId();
        RunHandle handle = runtime.create(quietUnit(runId));

        runtime.destroy(handle);

        String digest = UnitNames.digest(runId);
        assertEquals(List.of(), client().pods().inNamespace(namespace).withLabel(UnitNames.UNIT_LABEL, digest)
                .list().getItems());
        assertEquals(List.of(), client().network().v1().networkPolicies().inNamespace(namespace)
                .withLabel(UnitNames.UNIT_LABEL, digest).list().getItems());
        assertEquals(List.of(), client().configMaps().inNamespace(namespace).withLabel(UnitNames.UNIT_LABEL, digest)
                .list().getItems());
        for (String name : UnitNames.secretNames(runId)) {
            assertNull(client().secrets().inNamespace(namespace).withName(name).get(), name + " is still there");
        }
    }

    @Test
    void cancelEndsBothPodsWithinThirtySeconds() throws InterruptedException {
        RunHandle handle = runtime.create(quietUnit(ClusterUnits.runId()));
        try {
            runtime.cancel(handle);

            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (podsLeft(handle.runId()) > 0) {
                assertTrue(System.nanoTime() < deadline, "a pod of the cancelled unit was still there after 30 s");
                TimeUnit.MILLISECONDS.sleep(500);
            }
            assertNotNull(client().configMaps().inNamespace(namespace).withName(UnitNames.anchor(handle.runId()))
                    .get(), "the anchor stays after cancel");
        } finally {
            runtime.destroy(handle);
        }
    }

    @Test
    void theFetchSecretIsGoneOnceTheAgentRuns() {
        String runId = ClusterUnits.runId();
        RunHandle handle = runtime.create(quietUnit(runId));
        try {
            assertNull(client().secrets().inNamespace(namespace)
                    .withName(UnitNames.environmentSecret(runId, "fetch")).get(), "the forge credential stayed");
            assertNotNull(client().secrets().inNamespace(namespace)
                    .withName(UnitNames.environmentSecret(runId, "agent")).get(), "the agent's own Secret went too");
        } finally {
            runtime.destroy(handle);
        }
    }

    @Test
    void attachReturnsSoonAfterCancel() throws InterruptedException {
        RunHandle handle = runtime.create(quietUnit(ClusterUnits.runId()));
        try {
            Thread reader = attachInBackground(handle);
            TimeUnit.SECONDS.sleep(2);

            runtime.cancel(handle);

            assertTrue(reader.join(ATTACH_ENDS_WITHIN), "attach was still reading " + ATTACH_ENDS_WITHIN
                    + " after cancel");
        } finally {
            runtime.destroy(handle);
        }
    }

    @Test
    void attachEndsWhenItsThreadIsInterrupted() throws InterruptedException {
        RunHandle handle = runtime.create(quietUnit(ClusterUnits.runId()));
        try {
            Thread reader = attachInBackground(handle);
            TimeUnit.SECONDS.sleep(2);

            reader.interrupt();

            assertTrue(reader.join(ATTACH_ENDS_WITHIN), "attach ignored the interrupt of its thread");
        } finally {
            runtime.destroy(handle);
        }
    }

    @Test
    void anAgentThatHandedOffIsSalvagedWithItsExitCode() {
        String handOff = "printf '%s\\n' 'TEST artifact' > /handoff/artifact.md && touch /handoff/DONE; exit 3";
        RunHandle handle = runtime.create(ClusterUnits.unit(ClusterUnits.runId(), handOff));
        try {
            assertEquals(Finalization.salvaged(3, "agent exited 3"), runtime.salvage(handle));
        } finally {
            runtime.destroy(handle);
        }
    }

    @Test
    void anAgentPastItsDeadlineOverran() {
        RunHandle handle = runtime.create(quietUnit(ClusterUnits.runId()));
        try {
            Finalization finalization = runtime.salvage(handle);

            assertEquals(Finalization.Outcome.OVERRAN, finalization.outcome(), finalization.detail());
        } finally {
            runtime.destroy(handle);
        }
    }

    private static Thread attachInBackground(RunHandle handle) {
        List<String> lines = new CopyOnWriteArrayList<>();
        return Thread.ofPlatform().start(() -> runtime.attach(handle, LogChannel.AGENT, lines::add));
    }

    private static int podsLeft(String runId) {
        return client().pods().inNamespace(namespace).withLabel(UnitNames.UNIT_LABEL, UnitNames.digest(runId))
                .list().getItems().size();
    }
}

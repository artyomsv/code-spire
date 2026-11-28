package dev.codespire.runtime.k8s;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The mirror of {@link ClusterEnforcesPolicyIT}, for the cluster whose CNI enforces nothing: the same
 * denied pod reaches the canary. The arm's start-up check for policy enforcement is tested against this
 * cluster, so it must really be open.
 */
@Tag("open-cluster")
class ClusterIsOpenIT {

    @Test
    void aDeniedPodStillReachesTheCanary() {
        String canary = KindCluster.canaryTarget();
        String namespace = KindCluster.newNamespace("test-open");

        int exit = KindCluster.runToCompletion(namespace, "test-denied", "test-denied",
                KindCluster.tryDropOrReach(canary), Duration.ofSeconds(90));

        assertEquals(0, exit, "the denied pod could not reach the canary: this cluster enforces policy");
    }
}

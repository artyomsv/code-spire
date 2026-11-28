package dev.codespire.runtime.k8s;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Proves the cluster under test enforces network policy, so no later "this pod cannot reach X" result
 * is vacuous. A cluster whose CNI ignores policy would let every denied pod through and every such test
 * would pass for the wrong reason.
 */
@Tag("kind")
class ClusterEnforcesPolicyIT {

    @Test
    void aDeniedPodCannotReachTheCanaryAndAnAllowedPodCan() {
        String canary = KindCluster.canaryTarget();
        String namespace = KindCluster.newNamespace("test-policy");
        String canaryIp = canary.substring(0, canary.lastIndexOf(':'));

        int denied = KindCluster.runToCompletion(namespace, "test-denied", "test-denied",
                KindCluster.tryOnce(canary), Duration.ofSeconds(60));
        assertNotEquals(0, denied, "a pod under default-deny reached the canary: the cluster enforces no policy");

        NetworkPolicy allowCanary = new NetworkPolicyBuilder()
                .withNewMetadata().withName("allow-canary").withNamespace(namespace).endMetadata()
                .withNewSpec()
                .withNewPodSelector().addToMatchLabels("app", "test-allowed").endPodSelector()
                .withPolicyTypes("Egress")
                .addNewEgress()
                .addNewTo().withNewIpBlock().withCidr(canaryIp + "/32").endIpBlock().endTo()
                .addNewPort().withProtocol("TCP").withPort(new IntOrString(KindCluster.CANARY_PORT)).endPort()
                .endEgress()
                .endSpec()
                .build();
        KindCluster.client().network().v1().networkPolicies().inNamespace(namespace).resource(allowCanary).create();

        int allowed = KindCluster.runToCompletion(namespace, "test-allowed", "test-allowed",
                KindCluster.tryUntilConnected(canary), Duration.ofSeconds(90));
        assertEquals(0, allowed, "a pod with an egress allowance could not reach the canary");
    }
}

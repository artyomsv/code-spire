package dev.codespire.runtime.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnitPoliciesTest {

    private static final String RUN = UnitNamesTest.RUN;
    private static final String OTHER_RUN = RUN.replace(":1", ":2");

    private static final OwnerReference ANCHOR = new OwnerReferenceBuilder()
            .withApiVersion("v1").withKind("ConfigMap").withName(UnitNames.anchor(RUN)).withUid("TEST-uid").build();

    private static KubernetesRuntimeConfig config(List<String> allowed, List<String> denied) {
        return new KubernetesRuntimeConfig("test-ns", "TEST-publisher:TEST", "TEST-egress:TEST",
                List.of("TEST-canary"), "TEST-api-canary", "10.0.0.10", "kube-system",
                Map.of("k8s-app", "kube-dns"), allowed, denied, 3, Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(60), "100m", "128Mi");
    }

    private static List<String> portsOf(List<NetworkPolicyPort> ports) {
        List<String> described = new ArrayList<>();
        for (NetworkPolicyPort port : ports) {
            described.add(port.getProtocol() + " " + port.getPort().getIntVal());
        }
        return described;
    }

    @Test
    void theUnitPodMayOnlyOpenTcpToItsOwnProxyPod() {
        NetworkPolicy policy = UnitPolicies.unit(RUN, ANCHOR);
        assertEquals(UnitNames.labels(RUN, "unit"), policy.getSpec().getPodSelector().getMatchLabels());
        assertEquals(List.of("Ingress", "Egress"), policy.getSpec().getPolicyTypes());
        assertTrue(policy.getSpec().getIngress().isEmpty());
        assertEquals(1, policy.getSpec().getEgress().size());
        NetworkPolicyEgressRule rule = policy.getSpec().getEgress().get(0);
        assertEquals(1, rule.getTo().size());
        assertEquals(UnitNames.labels(RUN, "proxy"), rule.getTo().get(0).getPodSelector().getMatchLabels());
        assertNull(rule.getTo().get(0).getIpBlock());
        assertNull(rule.getTo().get(0).getNamespaceSelector());
        assertEquals(List.of("TCP 8080", "TCP 8889", "TCP 8890"), portsOf(rule.getPorts()));
    }

    @Test
    void theProxyPodTakesIngressOnlyFromItsOwnUnitPod() {
        NetworkPolicy policy = UnitPolicies.proxy(RUN, config(List.of(), List.of()), ANCHOR);
        assertEquals(UnitNames.labels(RUN, "proxy"), policy.getSpec().getPodSelector().getMatchLabels());
        assertEquals(1, policy.getSpec().getIngress().size());
        assertEquals(1, policy.getSpec().getIngress().get(0).getFrom().size());
        assertEquals(UnitNames.labels(RUN, "unit"),
                policy.getSpec().getIngress().get(0).getFrom().get(0).getPodSelector().getMatchLabels());
        assertEquals(List.of("TCP 8080", "TCP 8889", "TCP 8890"),
                portsOf(policy.getSpec().getIngress().get(0).getPorts()));
    }

    @Test
    void theProxyPodReachesDnsAndPublicAddressesOnly() {
        List<String> denied = List.of("203.0.113.0/24");
        NetworkPolicy policy = UnitPolicies.proxy(RUN, config(List.of(), denied), ANCHOR);
        List<NetworkPolicyEgressRule> egress = policy.getSpec().getEgress();
        assertEquals(2, egress.size());

        var dns = egress.get(0).getTo().get(0);
        assertEquals(Map.of("kubernetes.io/metadata.name", "kube-system"), dns.getNamespaceSelector().getMatchLabels());
        assertEquals(Map.of("k8s-app", "kube-dns"), dns.getPodSelector().getMatchLabels());
        assertEquals(List.of("UDP 53", "TCP 53"), portsOf(egress.get(0).getPorts()));

        var block = egress.get(1).getTo().get(0).getIpBlock();
        assertEquals("0.0.0.0/0", block.getCidr());
        List<String> expectedExcept = new ArrayList<>(UnitPolicies.PRIVATE_RANGES);
        expectedExcept.addAll(denied);
        assertEquals(expectedExcept, block.getExcept());
        assertEquals(List.of("TCP 443", "TCP 80"), portsOf(egress.get(1).getPorts()));
    }

    @Test
    void anExtraAllowedCidrIsItsOwnRuleOnPortsFourFourThreeAndEighty() {
        NetworkPolicy policy = UnitPolicies.proxy(RUN, config(List.of("198.51.100.0/24"), List.of()), ANCHOR);
        List<NetworkPolicyEgressRule> egress = policy.getSpec().getEgress();
        assertEquals(3, egress.size());
        assertEquals("198.51.100.0/24", egress.get(2).getTo().get(0).getIpBlock().getCidr());
        assertTrue(egress.get(2).getTo().get(0).getIpBlock().getExcept().isEmpty());
        assertEquals(List.of("TCP 443", "TCP 80"), portsOf(egress.get(2).getPorts()));
    }

    @Test
    void twoUnitsNeverSelectEachOther() {
        Map<String, String> otherProxy = UnitNames.labels(OTHER_RUN, "proxy");
        Map<String, String> selected = UnitPolicies.unit(RUN, ANCHOR).getSpec().getEgress().get(0).getTo().get(0)
                .getPodSelector().getMatchLabels();
        assertFalse(otherProxy.entrySet().containsAll(selected.entrySet()));
        Map<String, String> otherUnit = UnitNames.labels(OTHER_RUN, "unit");
        Map<String, String> admitted = UnitPolicies.proxy(RUN, config(List.of(), List.of()), ANCHOR).getSpec()
                .getIngress().get(0).getFrom().get(0).getPodSelector().getMatchLabels();
        assertFalse(otherUnit.entrySet().containsAll(admitted.entrySet()));
    }

    @Test
    void everyPolicyIsOwnedByTheAnchor() {
        assertEquals(List.of(ANCHOR), UnitPolicies.unit(RUN, ANCHOR).getMetadata().getOwnerReferences());
        assertEquals(List.of(ANCHOR),
                UnitPolicies.proxy(RUN, config(List.of(), List.of()), ANCHOR).getMetadata().getOwnerReferences());
    }
}

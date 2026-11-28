package dev.codespire.runtime.k8s;

import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.networking.v1.IPBlockBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPort;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPortBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The two NetworkPolicies of a unit. The unit pod may open TCP only to its own proxy pod. The proxy pod takes
 * ingress only from its unit pod and reaches only the cluster's DNS and public addresses.
 */
final class UnitPolicies {

    static final List<String> PRIVATE_RANGES = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "169.254.0.0/16", "100.64.0.0/10", "127.0.0.0/8");

    static final String UNIT_ROLE = "unit";
    static final String PROXY_ROLE = "proxy";

    /** The model proxy, the fetch proxy and the fetch proxy's close port: all the unit may reach. */
    static final List<Integer> PROXY_PORTS =
            List.of(RunUnitSpec.MODEL_PROXY_PORT, RunUnitSpec.FETCH_PROXY_PORT, RunUnitSpec.FETCH_CLOSE_PORT);

    private static final String NAMESPACE_NAME_LABEL = "kubernetes.io/metadata.name";

    private UnitPolicies() {
    }

    static NetworkPolicy unit(String runId, KubernetesRuntimeConfig config, OwnerReference anchor) {
        NetworkPolicyEgressRule toProxy = new NetworkPolicyEgressRuleBuilder()
                .withTo(peer(runId, PROXY_ROLE))
                .withPorts(ports("TCP", PROXY_PORTS))
                .build();
        return new NetworkPolicyBuilder()
                .withNewMetadata()
                .withName(UnitNames.unitPolicy(runId))
                .withNamespace(config.namespace())
                .withLabels(UnitNames.labels(runId, UNIT_ROLE))
                .withOwnerReferences(anchor)
                .endMetadata()
                .withNewSpec()
                .withPodSelector(selector(runId, UNIT_ROLE))
                .withPolicyTypes("Ingress", "Egress")
                .withEgress(toProxy)
                .endSpec()
                .build();
    }

    static NetworkPolicy proxy(String runId, KubernetesRuntimeConfig config, OwnerReference anchor) {
        List<NetworkPolicyEgressRule> egress = new ArrayList<>();

        NetworkPolicyPeerBuilder dns = new NetworkPolicyPeerBuilder()
                .withNamespaceSelector(new LabelSelectorBuilder()
                        .withMatchLabels(Map.of(NAMESPACE_NAME_LABEL, config.dnsNamespace())).build());
        if (!config.dnsLabels().isEmpty()) {
            dns.withPodSelector(new LabelSelectorBuilder().withMatchLabels(config.dnsLabels()).build());
        }
        List<NetworkPolicyPort> dnsPorts = new ArrayList<>();
        dnsPorts.addAll(ports("UDP", List.of(53)));
        dnsPorts.addAll(ports("TCP", List.of(53)));
        egress.add(new NetworkPolicyEgressRuleBuilder().withTo(dns.build()).withPorts(dnsPorts).build());

        List<String> except = new ArrayList<>(PRIVATE_RANGES);
        except.addAll(config.extraDeniedCidrs());
        egress.add(new NetworkPolicyEgressRuleBuilder()
                .withTo(new NetworkPolicyPeerBuilder()
                        .withIpBlock(new IPBlockBuilder().withCidr("0.0.0.0/0").withExcept(except).build())
                        .build())
                .withPorts(ports("TCP", List.of(443, 80)))
                .build());

        for (String cidr : config.extraAllowedCidrs()) {
            egress.add(new NetworkPolicyEgressRuleBuilder()
                    .withTo(new NetworkPolicyPeerBuilder()
                            .withIpBlock(new IPBlockBuilder().withCidr(cidr).build()).build())
                    .withPorts(ports("TCP", List.of(443, 80)))
                    .build());
        }

        return new NetworkPolicyBuilder()
                .withNewMetadata()
                .withName(UnitNames.proxyPolicy(runId))
                .withNamespace(config.namespace())
                .withLabels(UnitNames.labels(runId, PROXY_ROLE))
                .withOwnerReferences(anchor)
                .endMetadata()
                .withNewSpec()
                .withPodSelector(selector(runId, PROXY_ROLE))
                .withPolicyTypes("Ingress", "Egress")
                .addNewIngress()
                .withFrom(peer(runId, UNIT_ROLE))
                .withPorts(ports("TCP", PROXY_PORTS))
                .endIngress()
                .withEgress(egress)
                .endSpec()
                .build();
    }

    private static LabelSelector selector(String runId, String role) {
        return new LabelSelectorBuilder().withMatchLabels(UnitNames.labels(runId, role)).build();
    }

    private static NetworkPolicyPeer peer(String runId, String role) {
        return new NetworkPolicyPeerBuilder().withPodSelector(selector(runId, role)).build();
    }

    private static List<NetworkPolicyPort> ports(String protocol, List<Integer> numbers) {
        List<NetworkPolicyPort> ports = new ArrayList<>();
        for (int number : numbers) {
            ports.add(new NetworkPolicyPortBuilder().withProtocol(protocol).withPort(new IntOrString(number)).build());
        }
        return ports;
    }
}

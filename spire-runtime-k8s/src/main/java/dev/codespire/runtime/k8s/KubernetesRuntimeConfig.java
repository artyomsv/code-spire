package dev.codespire.runtime.k8s;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the Kubernetes arm needs from its deployment: where units run, which images carry the publisher and the
 * egress proxy, and how the cluster's network policy is proven before a unit starts.
 */
public record KubernetesRuntimeConfig(String namespace, String publisherImage, String egressImage,
                                      List<String> sealCanaries, String apiCanary, String dnsAddress,
                                      String dnsNamespace, Map<String, String> dnsLabels,
                                      List<String> extraAllowedCidrs, List<String> extraDeniedCidrs,
                                      int sealRounds, Duration sealTimeout, Duration sealDeadline,
                                      Duration unitStartTimeout, Duration initTimeout,
                                      String agentRequestCpu, String agentRequestMemory) {

    public KubernetesRuntimeConfig {
        requireText(namespace, "namespace");
        requireText(publisherImage, "publisherImage");
        requireText(egressImage, "egressImage");
        Objects.requireNonNull(sealCanaries, "sealCanaries");
        if (sealCanaries.isEmpty()) {
            throw new IllegalArgumentException("the Kubernetes arm needs at least one seal canary");
        }
        sealCanaries = List.copyOf(sealCanaries);
        requireText(dnsAddress, "dnsAddress");
        dnsLabels = dnsLabels == null ? Map.of() : Map.copyOf(dnsLabels);
        extraAllowedCidrs = extraAllowedCidrs == null ? List.of() : List.copyOf(extraAllowedCidrs);
        extraDeniedCidrs = extraDeniedCidrs == null ? List.of() : List.copyOf(extraDeniedCidrs);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("the Kubernetes arm needs a " + name);
        }
    }
}

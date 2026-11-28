package dev.codespire.runtime.k8s;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KubernetesRuntimeConfigTest {

    private static KubernetesRuntimeConfig build(String namespace, String publisherImage, String egressImage,
                                                 List<String> sealCanaries, String dnsAddress) {
        return new KubernetesRuntimeConfig(namespace, publisherImage, egressImage, sealCanaries, "TEST-api-canary",
                dnsAddress, "kube-system", Map.of(), List.of(), List.of(), 3, Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(60), "100m", "128Mi");
    }

    private static final List<String> CANARIES = List.of("TEST-canary");

    @Test
    void aBlankNamespaceIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> build(" ", "TEST-publisher:TEST", "TEST-egress:TEST", CANARIES, "10.0.0.10"));
    }

    @Test
    void aNullNamespaceIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> build(null, "TEST-publisher:TEST", "TEST-egress:TEST", CANARIES, "10.0.0.10"));
    }

    @Test
    void aMissingPublisherImageIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> build("test-ns", "", "TEST-egress:TEST", CANARIES, "10.0.0.10"));
    }

    @Test
    void aMissingEgressImageIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> build("test-ns", "TEST-publisher:TEST", null, CANARIES, "10.0.0.10"));
    }

    @Test
    void noSealCanaryIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> build("test-ns", "TEST-publisher:TEST", "TEST-egress:TEST", List.of(), "10.0.0.10"));
    }

    @Test
    void aBlankDnsAddressIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> build("test-ns", "TEST-publisher:TEST", "TEST-egress:TEST", CANARIES, "  "));
    }
}

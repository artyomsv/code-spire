package dev.codespire.runtime.k8s;

import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The unit and the two pods the pod tests inspect. Placeholders only. */
final class PodFixtures {

    static final String RUN = UnitNamesTest.RUN;
    static final String PROXY_IP = "198.51.100.7";

    static final KubernetesRuntimeConfig CONFIG = new KubernetesRuntimeConfig("test-ns",
            "TEST-publisher:TEST", "TEST-egress:TEST", List.of("192.0.2.10:9999"), "192.0.2.20:443", "192.0.2.53",
            "kube-system", Map.of("k8s-app", "kube-dns"), List.of(), List.of(), 5, Duration.ofMillis(500),
            Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(90), "250m", "1Gi");

    static final OwnerReference ANCHOR = new OwnerReferenceBuilder()
            .withApiVersion("v1").withKind("ConfigMap").withName(UnitNames.anchor(RUN)).withUid("TEST-uid").build();

    private PodFixtures() {
    }

    /** A review unit whose every container carries an environment, so a leak into the pod spec would show. */
    static RunUnitSpec spec() {
        RunUnitSpec base = TestUnits.proxiedReview(RUN);
        return base.withInit(withEnvironment(base.init(), "TEST_TOKEN"))
                .withAgent(withEnvironment(base.agent(), "TEST_PLACEHOLDER"))
                .withPublisher(withEnvironment(base.publisher(), "TEST_PUBLISH"))
                .withModelProxy(withEnvironment(base.modelProxy(), "TEST_REAL_KEY"));
    }

    private static ContainerSpec withEnvironment(ContainerSpec container, String name) {
        return new ContainerSpec(container.image(), container.argv(), Map.of(name, "TEST-value"),
                container.mounts(), container.entrypoint());
    }

    static Pod proxyPod() {
        return ProxyPod.of(spec(), CONFIG, ANCHOR);
    }

    static Pod unitPod() {
        return UnitPod.of(spec(), CONFIG, ANCHOR, PROXY_IP);
    }

    static List<Container> allContainers(Pod pod) {
        List<Container> containers = new ArrayList<>(pod.getSpec().getInitContainers());
        containers.addAll(pod.getSpec().getContainers());
        return containers;
    }

    static Container container(Pod pod, String name) {
        return allContainers(pod).stream().filter(container -> container.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no container " + name));
    }
}

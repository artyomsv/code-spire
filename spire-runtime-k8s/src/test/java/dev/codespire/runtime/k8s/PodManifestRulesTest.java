package dev.codespire.runtime.k8s;

import static dev.codespire.runtime.k8s.PodFixtures.ANCHOR;
import static dev.codespire.runtime.k8s.PodFixtures.CONFIG;
import static dev.codespire.runtime.k8s.PodFixtures.RUN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Rules that hold for both pods of a unit. */
class PodManifestRulesTest {

    private static final Map<String, String> SECRET_OF_CONTAINER = Map.of(
            "fetch", "fetch", "agent", "agent", "publisher", "publisher",
            "model-proxy", "model", "egress-proxy", "egress", "fetch-proxy", "fetchproxy");

    private static List<Pod> pods() {
        return List.of(PodFixtures.proxyPod(), PodFixtures.unitPod());
    }

    @Test
    void noContainerHasAnEnvEntry() {
        for (Pod pod : pods()) {
            for (Container container : PodFixtures.allContainers(pod)) {
                assertTrue(container.getEnv() == null || container.getEnv().isEmpty(), container.getName());
            }
        }
    }

    @Test
    void everyContainerReadsExactlyItsOwnSecret() {
        for (Pod pod : pods()) {
            for (Container container : PodFixtures.allContainers(pod)) {
                String role = SECRET_OF_CONTAINER.get(container.getName());
                if (role == null) {
                    assertTrue(container.getEnvFrom() == null || container.getEnvFrom().isEmpty(),
                            container.getName());
                    continue;
                }
                assertEquals(1, container.getEnvFrom().size(), container.getName());
                assertEquals(UnitNames.environmentSecret(RUN, role),
                        container.getEnvFrom().get(0).getSecretRef().getName(), container.getName());
            }
        }
    }

    @Test
    void noContainerLacksRequestsOrLimitsForCpuAndMemory() {
        for (Pod pod : pods()) {
            for (Container container : PodFixtures.allContainers(pod)) {
                for (String resource : List.of("cpu", "memory")) {
                    assertTrue(container.getResources().getRequests().containsKey(resource),
                            container.getName() + " request " + resource);
                    assertTrue(container.getResources().getLimits().containsKey(resource),
                            container.getName() + " limit " + resource);
                }
            }
        }
    }

    @Test
    void bothPodsAreRestrictedAndHoldNoToken() {
        for (Pod pod : pods()) {
            assertEquals("Never", pod.getSpec().getRestartPolicy());
            assertEquals(false, pod.getSpec().getAutomountServiceAccountToken());
            assertEquals(false, pod.getSpec().getEnableServiceLinks());
            assertNotEquals(Boolean.TRUE, pod.getSpec().getShareProcessNamespace());
            assertEquals(true, pod.getSpec().getSecurityContext().getRunAsNonRoot());
            assertEquals("RuntimeDefault", pod.getSpec().getSecurityContext().getSeccompProfile().getType());
            for (Container container : PodFixtures.allContainers(pod)) {
                assertEquals(false, container.getSecurityContext().getAllowPrivilegeEscalation(),
                        container.getName());
                assertEquals(List.of("ALL"), container.getSecurityContext().getCapabilities().getDrop(),
                        container.getName());
            }
        }
    }

    @Test
    void noImageIsUntaggedOrLatest() {
        for (Pod pod : pods()) {
            for (Container container : PodFixtures.allContainers(pod)) {
                assertTrue(container.getImage().endsWith(":TEST"), container.getImage());
            }
        }
        RunUnitSpec latest = PodFixtures.spec().withAgent(new ContainerSpec("TEST-agent:latest", List.of(),
                Map.of(), PodFixtures.spec().agent().mounts()));
        assertThrows(IllegalArgumentException.class, () -> UnitPod.of(latest, CONFIG, ANCHOR, PodFixtures.PROXY_IP));
        RunUnitSpec untagged = PodFixtures.spec().withModelProxy(new ContainerSpec("registry.test:5000/TEST-model",
                List.of(), Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> ProxyPod.of(untagged, CONFIG, ANCHOR));
    }

    @Test
    void everyObjectCarriesTheUnitLabelAndTheAnchorAsOwner() {
        Set<String> names = Set.of(UnitNames.unitPod(RUN), UnitNames.proxyPod(RUN));
        for (Pod pod : pods()) {
            assertTrue(names.contains(pod.getMetadata().getName()));
            assertEquals(UnitNames.digest(RUN), pod.getMetadata().getLabels().get(UnitNames.UNIT_LABEL));
            assertEquals(List.of(ANCHOR), pod.getMetadata().getOwnerReferences());
            assertEquals("test-ns", pod.getMetadata().getNamespace());
            assertEquals(RUN, pod.getMetadata().getAnnotations().get(UnitNames.RUN_ID_ANNOTATION));
        }
        assertFalse(PodFixtures.unitPod().getMetadata().getLabels()
                .equals(PodFixtures.proxyPod().getMetadata().getLabels()));
    }
}

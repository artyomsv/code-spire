package dev.codespire.runtime.k8s;

import static dev.codespire.runtime.k8s.PodFixtures.CONFIG;
import static dev.codespire.runtime.k8s.PodFixtures.container;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.Volume;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProxyPodTest {

    private final Pod pod = PodFixtures.proxyPod();

    @Test
    void sealRunsFirstWithTheWorkerCanaryAndDnsAsItsControl() {
        assertEquals(1, pod.getSpec().getInitContainers().size());
        Container seal = pod.getSpec().getInitContainers().get(0);
        assertEquals("seal", seal.getName());
        assertEquals(List.of("spire-seal"), seal.getCommand());
        assertEquals(List.of("--canary", "192.0.2.10:9999", "--control", "192.0.2.53", "--rounds", "5",
                "--timeout-ms", "500", "--interval-ms", "250", "--deadline-s", "60"), seal.getArgs());
        assertFalse(seal.getArgs().contains(CONFIG.apiCanary()));
        assertEquals("TEST-publisher:TEST", seal.getImage());
    }

    @Test
    void theFetchProxyClosesOnPortEightEightNineZeroAndAtTheInitTimeout() {
        assertEquals(List.of("spire-fetch-proxy", "8890", "90"), container(pod, "fetch-proxy").getCommand());
        assertEquals(List.of("model-proxy", "egress-proxy", "fetch-proxy"),
                pod.getSpec().getContainers().stream().map(Container::getName).toList());
    }

    @Test
    void theModelProxyAndEgressProxyHaveTcpStartupProbes() {
        Probe model = container(pod, "model-proxy").getStartupProbe();
        assertEquals(8080, model.getTcpSocket().getPort().getIntVal());
        assertEquals(120, model.getPeriodSeconds() * model.getFailureThreshold());
        Probe egress = container(pod, "egress-proxy").getStartupProbe();
        assertEquals(8888, egress.getTcpSocket().getPort().getIntVal());
        assertEquals(20, egress.getPeriodSeconds() * egress.getFailureThreshold());
        assertTrue(container(pod, "egress-proxy").getCommand() == null
                || container(pod, "egress-proxy").getCommand().isEmpty());
    }

    @Test
    void spireEgressResolvesToLoopback() {
        assertEquals(1, pod.getSpec().getHostAliases().size());
        assertEquals("127.0.0.1", pod.getSpec().getHostAliases().get(0).getIp());
        assertEquals(List.of("spire-egress"), pod.getSpec().getHostAliases().get(0).getHostnames());
    }

    @Test
    void theProxiesHaveAReadOnlyRootAndAnEmptyDirTmp() {
        for (Container proxy : pod.getSpec().getContainers()) {
            assertEquals(true, proxy.getSecurityContext().getReadOnlyRootFilesystem(), proxy.getName());
            assertEquals(1, proxy.getVolumeMounts().size(), proxy.getName());
            assertEquals("/tmp", proxy.getVolumeMounts().get(0).getMountPath(), proxy.getName());
            Volume tmp = pod.getSpec().getVolumes().stream()
                    .filter(volume -> volume.getName().equals(proxy.getVolumeMounts().get(0).getName()))
                    .findFirst().orElseThrow();
            assertEquals("Memory", tmp.getEmptyDir().getMedium(), proxy.getName());
        }
    }

    @Test
    void theModelProxyMountsNothingOfTheUnit() {
        List<String> mounted = container(pod, "model-proxy").getVolumeMounts().stream()
                .map(mount -> mount.getMountPath()).toList();
        assertEquals(List.of("/tmp"), mounted);
        assertTrue(pod.getSpec().getVolumes().stream().noneMatch(volume -> volume.getSecret() != null));
    }

    @Test
    void itsDeadlineIsABackstop() {
        // wall clock 600 s + init timeout 90 s + 60 s + 300 s
        assertEquals(1050L, pod.getSpec().getActiveDeadlineSeconds());
    }
}

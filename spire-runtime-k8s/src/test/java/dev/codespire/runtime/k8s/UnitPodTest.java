package dev.codespire.runtime.k8s;

import static dev.codespire.runtime.k8s.PodFixtures.CONFIG;
import static dev.codespire.runtime.k8s.PodFixtures.RUN;
import static dev.codespire.runtime.k8s.PodFixtures.container;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnitPodTest {

    private final Pod pod = PodFixtures.unitPod();

    private Volume volume(String name) {
        return pod.getSpec().getVolumes().stream().filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void initContainersRunInTheOrderSealFetchFetchClosedPublisher() {
        assertEquals(List.of("seal", "fetch", "fetch-closed", "publisher"),
                pod.getSpec().getInitContainers().stream().map(Container::getName).toList());
        assertEquals(List.of("agent"), pod.getSpec().getContainers().stream().map(Container::getName).toList());
    }

    @Test
    void sealProbesTheWorkerCanaryAndTheApiAndNeedsTheModelProxy() {
        assertEquals(List.of("--canary", "192.0.2.10:9999", "--canary", "192.0.2.20:443",
                "--control", "spire-model:8080", "--rounds", "5", "--timeout-ms", "500", "--interval-ms", "250",
                "--deadline-s", "60"), container(pod, "seal").getArgs());
        assertEquals(List.of("spire-seal"), container(pod, "seal").getCommand());
    }

    @Test
    void fetchClosedSendsTheCloseAndChecksThePort() {
        Container closed = container(pod, "fetch-closed");
        assertEquals(List.of("spire-fetch-closed"), closed.getCommand());
        assertEquals(List.of("--close", "spire-fetch:8890", "--target", "spire-fetch:8889", "--refusals", "3",
                "--deadline-s", "30"), closed.getArgs());
    }

    @Test
    void thePublisherIsANativeSidecarThatStaysUntilTerm() {
        Container publisher = pod.getSpec().getInitContainers().get(3);
        assertEquals("publisher", publisher.getName());
        assertEquals("Always", publisher.getRestartPolicy());
        assertEquals(List.of("publish", "--stay-until-term"), publisher.getArgs());
        assertNull(container(pod, "seal").getRestartPolicy());
    }

    @Test
    void spireModelAndSpireFetchResolveToTheProxyPod() {
        assertEquals(1, pod.getSpec().getHostAliases().size());
        assertEquals(PodFixtures.PROXY_IP, pod.getSpec().getHostAliases().get(0).getIp());
        assertEquals(List.of("spire-model", "spire-fetch"), pod.getSpec().getHostAliases().get(0).getHostnames());
    }

    @Test
    void theInputsSecretIsMountedReadOnlyInTheAgentOnly() {
        VolumeMount agentMount = container(pod, "agent").getVolumeMounts().stream()
                .filter(mount -> mount.getMountPath().equals("/inputs")).findFirst().orElseThrow();
        assertEquals(true, agentMount.getReadOnly());
        Volume secret = volume(agentMount.getName());
        assertEquals(UnitNames.inputsSecret(RUN), secret.getSecret().getSecretName());
        assertEquals(0444, secret.getSecret().getDefaultMode());

        VolumeMount initMount = container(pod, "fetch").getVolumeMounts().stream()
                .filter(mount -> mount.getMountPath().equals("/inputs")).findFirst().orElseThrow();
        assertNull(volume(initMount.getName()).getSecret());
        assertTrue(volume(initMount.getName()).getEmptyDir() != null);

        for (Container other : PodFixtures.allContainers(pod)) {
            if (other.getName().equals("agent")) {
                continue;
            }
            assertTrue(other.getVolumeMounts() == null
                    || other.getVolumeMounts().stream().noneMatch(mount -> mount.getName().equals(secret.getName())),
                    other.getName());
        }
    }

    @Test
    void sharedVolumesAreOnNodeDiskWithTheUnitsSizeLimit() {
        RunUnitSpec spec = PodFixtures.spec();
        for (String name : List.of("work", "out")) {
            Volume shared = volume(name);
            assertNull(shared.getEmptyDir().getMedium(), name);
            assertEquals(String.valueOf(spec.diskBytes()), shared.getEmptyDir().getSizeLimit().getAmount(), name);
        }
        long emptyDirsOnNodeDisk = pod.getSpec().getVolumes().stream()
                .filter(candidate -> candidate.getEmptyDir() != null && candidate.getEmptyDir().getMedium() == null)
                .count();
        // work, out, and init's own inputs directory
        assertEquals(3, emptyDirsOnNodeDisk);
    }

    @Test
    void theAgentsTmpIsMemoryBackedWithTheUnitsSizeLimit() {
        VolumeMount tmp = container(pod, "agent").getVolumeMounts().stream()
                .filter(mount -> mount.getMountPath().equals("/tmp")).findFirst().orElseThrow();
        Volume volume = volume(tmp.getName());
        assertEquals("Memory", volume.getEmptyDir().getMedium());
        assertEquals(String.valueOf(PodFixtures.spec().diskBytes()), volume.getEmptyDir().getSizeLimit().getAmount());
    }

    @Test
    void readOnlyMountsStayReadOnly() {
        RunUnitSpec spec = PodFixtures.spec();
        ContainerSpec publisher = spec.publisher();
        ContainerSpec readOnlyHandoff = new ContainerSpec(publisher.image(), publisher.argv(),
                publisher.environment(), List.of(Mount.readOnly("out", "/handoff")));
        Pod changed = UnitPod.of(spec.withPublisher(readOnlyHandoff), CONFIG, PodFixtures.ANCHOR,
                PodFixtures.PROXY_IP);
        assertEquals(true, container(changed, "publisher").getVolumeMounts().get(0).getReadOnly());
        assertEquals(false, container(pod, "fetch").getVolumeMounts().get(0).getReadOnly());
    }

    @Test
    void thePodStopsAtInitTimeoutPlusWallClock() {
        assertEquals(690L, pod.getSpec().getActiveDeadlineSeconds());
        assertEquals(30L, pod.getSpec().getTerminationGracePeriodSeconds());
    }

    @Test
    void theAgentKeepsItsImageArgvAndEntrypoint() {
        RunUnitSpec spec = PodFixtures.spec();
        Container agent = container(pod, "agent");
        assertEquals(spec.agent().image(), agent.getImage());
        assertEquals(spec.agent().argv(), agent.getArgs());
        assertTrue(agent.getCommand() == null || agent.getCommand().isEmpty());
        ContainerSpec withEntrypoint = new ContainerSpec(spec.agent().image(), spec.agent().argv(),
                spec.agent().environment(), spec.agent().mounts(), List.of("TEST-entry"));
        Pod changed = UnitPod.of(spec.withAgent(withEntrypoint), CONFIG, PodFixtures.ANCHOR, PodFixtures.PROXY_IP);
        assertEquals(List.of("TEST-entry"), container(changed, "agent").getCommand());
    }
}

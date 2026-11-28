package dev.codespire.runtime.k8s;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Why a unit's pod did not start, as the run's failure detail says it. */
class UnitStartFailureTest {

    private static Pod pod(String phase, List<ContainerStatus> inits, List<ContainerStatus> containers) {
        return new PodBuilder()
                .withNewMetadata().withName("test-unit").endMetadata()
                .withNewStatus()
                .withPhase(phase)
                .withInitContainerStatuses(inits)
                .withContainerStatuses(containers)
                .endStatus()
                .build();
    }

    private static ContainerStatus terminated(String name, int exit, String reason) {
        return new ContainerStatusBuilder().withName(name).withImage("TEST-image:TEST")
                .withNewState().withNewTerminated().withExitCode(exit).withReason(reason).endTerminated().endState()
                .build();
    }

    private static ContainerStatus waiting(String name, String reason) {
        return new ContainerStatusBuilder().withName(name).withImage("TEST-" + name + ":TEST")
                .withNewState().withNewWaiting().withReason(reason).endWaiting().endState()
                .build();
    }

    private static ContainerStatus running(String name) {
        return new ContainerStatusBuilder().withName(name).withImage("TEST-image:TEST")
                .withNewState().withNewRunning().endRunning().endState()
                .build();
    }

    @Test
    void itNamesPhaseContainerStateExitCodeAndOomKilled() {
        Pod pod = pod("Failed", List.of(terminated("seal", 0, "Completed"), terminated("fetch", 137, "OOMKilled")),
                List.of(waiting("agent", "PodInitializing")));

        String detail = UnitStartFailure.detail("TEST headline", pod, List.of("TEST-last-line"));

        assertTrue(detail.startsWith("TEST headline ("), detail);
        assertTrue(detail.contains("phase Failed"), detail);
        assertTrue(detail.contains("seal terminated, exit 0, OOMKilled false"), detail);
        assertTrue(detail.contains("fetch terminated, exit 137, OOMKilled true"), detail);
        assertTrue(detail.contains("agent waiting PodInitializing (image TEST-agent:TEST)"), detail);
        assertTrue(detail.endsWith("last output: TEST-last-line"), detail);
    }

    @Test
    void aPodWithNoStatusSaysSo() {
        Pod pod = new PodBuilder().withNewMetadata().withName("test-unit").endMetadata().build();

        String detail = UnitStartFailure.detail("TEST", pod, List.of());

        assertTrue(detail.contains("phase unknown; no container status yet"), detail);
        assertTrue(detail.endsWith("last output: none"), detail);
    }

    @Test
    void theLogTailIsAtMostTwentyLinesAndTwoKibibytes() {
        List<String> many = IntStream.range(0, 50).mapToObj(line -> "TEST-line-" + line).toList();
        List<String> tail = UnitStartFailure.tail(many);
        assertEquals(UnitStartFailure.TAIL_LINES, tail.size());
        assertEquals("TEST-line-30", tail.getFirst());
        assertEquals("TEST-line-49", tail.getLast());

        List<String> wide = IntStream.range(0, 20).mapToObj(line -> "x".repeat(495) + "TEST-" + line).toList();
        List<String> bounded = UnitStartFailure.tail(wide);
        int joined = String.join(UnitStartFailure.SEPARATOR, bounded).length();
        assertTrue(joined <= UnitStartFailure.TAIL_CHARS, "length " + joined);
        assertEquals(wide.getLast(), bounded.getLast());

        List<String> one = UnitStartFailure.tail(List.of("TEST-older", "y".repeat(5000) + "TEST-end"));
        assertEquals(1, one.size());
        assertTrue(one.getFirst().length() <= UnitStartFailure.TAIL_CHARS);
        assertTrue(one.getFirst().endsWith("TEST-end"));
    }

    @Test
    void anImagePullReasonIsAnImageProblem() {
        for (String reason : List.of("ErrImagePull", "ImagePullBackOff", "InvalidImageName")) {
            Pod pod = pod("Pending", List.of(running("seal")), List.of(waiting("model-proxy", reason)));
            assertTrue(UnitStartFailure.isImageProblem(pod), reason);
        }
        Pod initializing = pod("Pending", List.of(running("seal")), List.of(waiting("agent", "PodInitializing")));
        assertFalse(UnitStartFailure.isImageProblem(initializing));
        Pod crashed = pod("Failed", List.of(terminated("seal", 1, "Error")), List.of());
        assertFalse(UnitStartFailure.isImageProblem(crashed));
    }

    @Test
    void aFetchFailureReadsAsTheDockerArmsInitReport() {
        List<String> log = List.of("TEST-progress",
                "{\"event\":\"failed\",\"cause\":\"CLONE_FAILED\",\"detail\":\"TEST-detail\"}");
        assertEquals(": CLONE_FAILED: TEST-detail", FetchFailureReport.of(log));
        assertEquals(": TEST-exception: TEST-message",
                FetchFailureReport.of(List.of("TEST-exception: TEST-message", "\tat TEST.frame")));
        assertEquals("", FetchFailureReport.of(List.of()));
    }
}

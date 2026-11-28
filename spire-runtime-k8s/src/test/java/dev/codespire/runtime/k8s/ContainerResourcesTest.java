package dev.codespire.runtime.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ContainerResourcesTest {

    private static void assertTable(String requestCpu, String requestMemory, String limitCpu, String limitMemory,
                                    ResourceRequirements actual) {
        assertEquals(requestCpu, actual.getRequests().get("cpu").toString());
        assertEquals(requestMemory, actual.getRequests().get("memory").toString());
        assertEquals(limitCpu, actual.getLimits().get("cpu").toString());
        assertEquals(limitMemory, actual.getLimits().get("memory").toString());
    }

    @Test
    void theFixedContainersMatchTheSpecsResourceTable() {
        assertTable("50m", "64Mi", "500m", "256Mi", ContainerResources.seal());
        assertTable("250m", "256Mi", "2", "1Gi", ContainerResources.fetch());
        assertTable("50m", "128Mi", "500m", "512Mi", ContainerResources.publisher());
        assertTable("50m", "128Mi", "500m", "192Mi", ContainerResources.modelProxy());
        assertTable("25m", "16Mi", "500m", "64Mi", ContainerResources.smallProxy());
    }

    private static RunUnitSpec withLimits(long memoryBytes, long nanoCpus) {
        RunUnitSpec base = PodFixtures.spec();
        return new RunUnitSpec(base.runId(), base.init(), base.agent(), base.publisher(), base.enterprise(),
                base.egress(), base.modelProxy(), memoryBytes, nanoCpus, base.diskBytes(), Duration.ofMinutes(10),
                base.inputs(), base.fetchHosts());
    }

    @Test
    void theAgentGetsTheSpecsLimitsAndTheConfiguredRequests() {
        // config requests are 250m and 1Gi
        assertTable("250m", "1Gi", "2000m", String.valueOf(4L << 30),
                ContainerResources.agent(withLimits(4L << 30, 2_000_000_000L), PodFixtures.CONFIG));
    }

    @Test
    void aRequestAboveTheSpecsLimitIsClampedToTheLimit() {
        assertTable("100m", String.valueOf(512L << 20), "100m", String.valueOf(512L << 20),
                ContainerResources.agent(withLimits(512L << 20, 100_000_000L), PodFixtures.CONFIG));
    }

    @Test
    void aCpuLimitBelowOneMillicoreRoundsUpToOne() {
        ResourceRequirements agent = ContainerResources.agent(withLimits(4L << 30, 1L), PodFixtures.CONFIG);
        assertEquals("1m", agent.getLimits().get("cpu").toString());
        assertEquals("1m", agent.getRequests().get("cpu").toString());
    }
}

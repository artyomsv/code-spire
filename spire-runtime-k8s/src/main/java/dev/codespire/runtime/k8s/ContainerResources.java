package dev.codespire.runtime.k8s;

import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;

/**
 * Requests and limits of every container of a unit, from the spec's "Resources" table. A container with no
 * request or limit is a defect: the scheduler cannot place it and one runaway process can starve its node.
 */
final class ContainerResources {

    private static final long NANO_CPUS_PER_MILLICORE = 1_000_000L;

    private ContainerResources() {
    }

    /** The seal and the fetch-closed check, each a JVM start. */
    static ResourceRequirements seal() {
        return of("50m", "64Mi", "500m", "256Mi");
    }

    static ResourceRequirements fetch() {
        return of("250m", "256Mi", "2", "1Gi");
    }

    static ResourceRequirements publisher() {
        return of("50m", "128Mi", "500m", "512Mi");
    }

    /** The agent's limits are the spec's; its requests are the deployment's. */
    static ResourceRequirements agent(RunUnitSpec spec, KubernetesRuntimeConfig config) {
        String cpuLimit = (spec.nanoCpus() / NANO_CPUS_PER_MILLICORE) + "m";
        String memoryLimit = String.valueOf(spec.memoryBytes());
        return of(config.agentRequestCpu(), config.agentRequestMemory(), cpuLimit, memoryLimit);
    }

    static ResourceRequirements modelProxy() {
        return of("50m", "128Mi", "500m", "192Mi");
    }

    /** The egress proxy and the fetch proxy. */
    static ResourceRequirements smallProxy() {
        return of("25m", "16Mi", "500m", "64Mi");
    }

    private static ResourceRequirements of(String requestCpu, String requestMemory,
                                           String limitCpu, String limitMemory) {
        return new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity(requestCpu))
                .addToRequests("memory", new Quantity(requestMemory))
                .addToLimits("cpu", new Quantity(limitCpu))
                .addToLimits("memory", new Quantity(limitMemory))
                .build();
    }
}

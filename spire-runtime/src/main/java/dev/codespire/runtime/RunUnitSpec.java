package dev.codespire.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * A run is not one container. It is an init clone, the agent, and the publisher sidecar, sharing
 * ephemeral volumes and nothing outside the unit (ADR-038, RUN-TOPOLOGY §3).
 *
 * <p>The parts run in this order: {@code init} to completion, then {@code agent} and
 * {@code publisher} concurrently. The unit ends when the agent exits and the publisher has drained.
 *
 * <p>The three are separate components rather than a list because they are not interchangeable:
 * only the publisher holds the push credential, only the agent runs untrusted model output, and
 * only init runs before either. A list would let a caller reorder them silently.
 */
public record RunUnitSpec(String runId,
                          ContainerSpec init, ContainerSpec agent, ContainerSpec publisher,
                          long memoryBytes, long nanoCpus, Duration wallClock) {

    public RunUnitSpec {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(init, "init");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(wallClock, "wallClock");
        if (memoryBytes <= 0 || nanoCpus <= 0) {
            throw new IllegalArgumentException(
                    "a run unit needs real limits; unlimited is not a limit (memory=" + memoryBytes
                            + ", nanoCpus=" + nanoCpus + ")");
        }
        if (wallClock.isZero() || wallClock.isNegative()) {
            throw new IllegalArgumentException("a run unit needs a wall clock, was: " + wallClock);
        }
    }
}

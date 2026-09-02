package dev.codespire.runtime;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A run is not one container. It is an init clone, the agent, and the publisher sidecar, sharing
 * ephemeral volumes and nothing outside the unit (ADR-039, RUN-TOPOLOGY §3).
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
        if (runId.isBlank()) {
            // Every container and volume is labelled with it, so a blank one makes a unit
            // undiscoverable: the watchdog cannot attribute it and destroy targets nothing.
            throw new IllegalArgumentException("a run unit needs a runId");
        }
        if (wallClock.isZero() || wallClock.isNegative()) {
            throw new IllegalArgumentException("a run unit needs a wall clock, was: " + wallClock);
        }
        requireContainment(agent, publisher);
    }

    /**
     * The publisher may not write to anything the agent can write to.
     *
     * <p><b>This is the invariant ADR-039 rests on, and until now nothing could enforce it.</b>
     * {@link Mount} made the read-only flag typed so it could not be misspelled — but nobody read
     * it, so a unit handing the publisher {@code Mount.writable("handoff", "/handoff")} compiled
     * and ran. The publisher is the one process holding a git write credential; the whole reason it
     * is safe for it to hold one is that it cannot be influenced by the agent.
     *
     * <p>Checked HERE rather than in a composition root or in the Docker arm, because this is the
     * only place that sees all three containers and every arm must pass through it. A per-arm copy
     * would let the Kubernetes arm silently grant what Docker refuses.
     */
    private static void requireContainment(ContainerSpec agent, ContainerSpec publisher) {
        Set<String> agentWritable = new LinkedHashSet<>();
        for (Mount mount : agent.mounts()) {
            if (!mount.readOnly()) {
                agentWritable.add(mount.volume());
            }
        }
        for (Mount mount : publisher.mounts()) {
            if (agentWritable.contains(mount.volume()) && !mount.readOnly()) {
                throw new IllegalArgumentException("the publisher may not write to \""
                        + mount.volume() + "\", which the agent can also write. It holds the push "
                        + "credential, and that is safe only while the agent cannot reach it.");
            }
        }
    }
}

package dev.codespire.runtime;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p>{@code enterprise} is the one thing every container gets identically (FR-F14) — see
 * {@link EnterpriseEnvironment} for why it is held here and not on each part. It has no defaulting
 * constructor on purpose: a caller that does not need it says {@link EnterpriseEnvironment#NONE}
 * out loud, the same reasoning that makes {@link Mount#writable} spell its {@code false}.
 */
public record RunUnitSpec(String runId,
                          ContainerSpec init, ContainerSpec agent, ContainerSpec publisher,
                          EnterpriseEnvironment enterprise,
                          long memoryBytes, long nanoCpus, Duration wallClock) {

    public RunUnitSpec {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(init, "init");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(enterprise, "enterprise");
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
        requireNoEnvironmentCollision(enterprise, init, agent, publisher);
        requireHostPathsDoNotShadowVolumes(enterprise, init, agent, publisher);
    }

    /**
     * What this container's process actually sees: its own environment plus the deployment's.
     *
     * <p>Every arm must build a container's environment through this rather than reading
     * {@link ContainerSpec#environment()} directly, which is why the merge lives here and not in
     * the Docker arm — a per-arm copy would let the Kubernetes arm ship a unit with no proxy and
     * no error, and the symptom (calls that hang, then time out) names nothing.
     */
    public Map<String, String> environmentFor(ContainerSpec container) {
        Objects.requireNonNull(container, "container");
        Map<String, String> merged = new LinkedHashMap<>(container.environment());
        merged.putAll(enterprise.environment());
        return Map.copyOf(merged);
    }

    /** The host paths every container of this unit mounts, read-only. */
    public List<HostMount> hostMounts() {
        return enterprise.mounts();
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

    /**
     * A deployment-wide variable may not silently replace a container's own.
     *
     * <p>The two are set by different people — the operator's proxy configuration against the
     * builder's per-role wiring — so a collision means one of them is being ignored, and nothing
     * says which. Refusing is the only answer that cannot be wrong: either precedence rule is
     * defensible in the abstract and catastrophic in one direction, since the colliding names that
     * matter are the credentials each role is handed. Silently letting the deployment win would let
     * a mistyped operator variable blank the publisher's push token; silently letting the container
     * win would let the agent bypass the proxy an operator believes is mandatory.
     */
    private static void requireNoEnvironmentCollision(EnterpriseEnvironment enterprise,
                                                      ContainerSpec... containers) {
        for (ContainerSpec container : containers) {
            for (String name : enterprise.environment().keySet()) {
                if (container.environment().containsKey(name)) {
                    throw new IllegalArgumentException("the deployment sets \"" + name
                            + "\", which this unit's " + container.image() + " container also sets."
                            + " One of the two would be silently ignored, so neither is applied.");
                }
            }
        }
    }

    /**
     * A host mount may not land on a path the unit already mounts a volume at.
     *
     * <p>Otherwise a CA bundle configured at {@code /workspace} would replace the agent's work tree
     * with a read-only host file, and the run would fail with the clone appearing to have produced
     * nothing. The arms differ in which of two colliding binds wins, so this cannot be left to
     * them.
     */
    private static void requireHostPathsDoNotShadowVolumes(EnterpriseEnvironment enterprise,
                                                           ContainerSpec... containers) {
        Set<String> hostPaths = new LinkedHashSet<>();
        for (HostMount mount : enterprise.mounts()) {
            if (!hostPaths.add(mount.path())) {
                throw new IllegalArgumentException("duplicate host mount path: " + mount.path());
            }
        }
        for (ContainerSpec container : containers) {
            for (Mount mount : container.mounts()) {
                if (hostPaths.contains(mount.path())) {
                    throw new IllegalArgumentException("the deployment mounts a host path at \""
                            + mount.path() + "\", where this unit already mounts the \""
                            + mount.volume() + "\" volume");
                }
            }
        }
    }
}

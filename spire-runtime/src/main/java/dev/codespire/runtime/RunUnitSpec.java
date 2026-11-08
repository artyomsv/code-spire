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
 *
 * <p>{@code egress} says whether the agent reaches the network only through the unit's egress proxy; see
 * {@link EgressPolicy}. It has no defaulting constructor for the same reason: {@link EgressPolicy#OFF} is said
 * out loud. Only a unit stored before the field existed reads back without one, and then as OFF, which is how it ran.
 *
 * <p>{@code modelProxy} is the unit's model proxy, or null for a unit without one (harness reviews step 3). It holds
 * the real model credential; the agent beside it holds a placeholder and reaches it at {@link #MODEL_PROXY_ADDRESS}.
 * Only the model proxy is behind the egress proxy then: the agent gets the deployment's trust and no proxy at all, and
 * its network leads nowhere but to the model proxy. So a model proxy needs an egress network, and it mounts nothing
 * of the unit's (the runtime gives it the host trust mounts, read-only, as every container).
 *
 * <p><b>{@code diskBytes} is a limit like the other two, and it is stated in the SPI rather than
 * left to each arm, because the portable answer differs per arm while the guarantee must not.</b>
 * Memory, CPU and process count were bounded and disk was not, so one {@code fallocate} from an
 * agent at full shell access filled the daemon's disk and took every concurrent run with it — and
 * on a developer machine the databases beside it.
 *
 * <p><b>How much of it an arm can actually spend differs, and that is why the number lives here.</b>
 * A size-bounded tmpfs is the only enforcement that travels — a write past {@code size=} gets
 * {@code ENOSPC}, identically on Docker Desktop for Windows and macOS (both run a real Linux VM),
 * on native Linux, under rootless Docker and on Kubernetes. {@code --storage-opt size=} is not an
 * option: it needs xfs with pquota, and Docker Desktop is overlay2 on ext4, so it fails at
 * container creation on the machines most developers use.
 *
 * <p>On <b>Kubernetes</b> that covers the whole unit: {@code emptyDir} is a POD volume, so it
 * survives an init container exiting, and {@code medium: Memory} with {@code sizeLimit} bounds the
 * shared workspace as well as {@code /tmp}.
 *
 * <p>On <b>Docker</b> it covers {@code /tmp} only, and the reason is measured rather than assumed:
 * a tmpfs-backed local volume is dropped when the last container using it stops. Two containers
 * that overlap share it; two that do not lose it. This unit runs init TO COMPLETION and only then
 * starts the agent, so a tmpfs {@code /workspace} would wipe the clone in between — a broken run in
 * place of an unbounded one. So that arm bounds {@code /tmp}, RUN-TOPOLOGY §9 carries the rest as a
 * deployment requirement, and {@code techdebt/spire-runtime-docker/} records the two candidate
 * designs that would close it.
 *
 * <p><b>What no arm bounds, said here rather than discovered later:</b> a container's own writable
 * layer. Closing that portably means a read-only root filesystem, which is a property of the agent
 * IMAGE rather than of the runtime — so it belongs in AGENT-IMAGE-CONTRACT.md as a clause
 * {@code spire-agent-image verify} checks, not in a spec component.
 */
public record RunUnitSpec(String runId,
                          ContainerSpec init, ContainerSpec agent, ContainerSpec publisher,
                          EnterpriseEnvironment enterprise, EgressPolicy egress, ContainerSpec modelProxy,
                          long memoryBytes, long nanoCpus, long diskBytes, Duration wallClock,
                          UnitInputs inputs) {

    /** The name the agent of a proxied unit reaches its model proxy by, on the unit's agent network. */
    public static final String MODEL_PROXY_ALIAS = "spire-model";

    /** The model proxy's listening port, the image contract's. */
    public static final int MODEL_PROXY_PORT = 8080;

    /** Where a harness adapter points the agent's model provider in a proxied unit. */
    public static final String MODEL_PROXY_ADDRESS = "http://" + MODEL_PROXY_ALIAS + ":" + MODEL_PROXY_PORT;

    /** A unit with no model proxy; {@link #withModelProxy} adds one. */
    public RunUnitSpec(String runId, ContainerSpec init, ContainerSpec agent, ContainerSpec publisher,
                       EnterpriseEnvironment enterprise, EgressPolicy egress,
                       long memoryBytes, long nanoCpus, long diskBytes, Duration wallClock, UnitInputs inputs) {
        this(runId, init, agent, publisher, enterprise, egress, null, memoryBytes, nanoCpus, diskBytes, wallClock,
                inputs);
    }

    /** A unit with no inputs: every build unit, and a held build's unit stored before inputs existed. */
    public RunUnitSpec(String runId, ContainerSpec init, ContainerSpec agent, ContainerSpec publisher,
                       EnterpriseEnvironment enterprise, EgressPolicy egress,
                       long memoryBytes, long nanoCpus, long diskBytes, Duration wallClock) {
        this(runId, init, agent, publisher, enterprise, egress, null, memoryBytes, nanoCpus, diskBytes, wallClock,
                null);
    }

    public RunUnitSpec {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(init, "init");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(enterprise, "enterprise");
        if (egress == null) {
            // A held build's unit is stored as JSON and read back to resume its publisher. One stored before the
            // proxy existed carries no egress, and that unit truly ran with none, so OFF is what happened, not a
            // default chosen for it. A unit built today always names its egress.
            egress = EgressPolicy.OFF;
        }
        Objects.requireNonNull(wallClock, "wallClock");
        if (memoryBytes <= 0 || nanoCpus <= 0 || diskBytes <= 0) {
            throw new IllegalArgumentException(
                    "a run unit needs real limits; unlimited is not a limit (memory=" + memoryBytes
                            + ", nanoCpus=" + nanoCpus + ", disk=" + diskBytes + ")");
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
        if (modelProxy != null) requireModelProxyPlacement(egress, modelProxy);
        requireNoEnvironmentCollision(enterprise, egress, init, agent, publisher, modelProxy);
        requireHostPathsDoNotShadowVolumes(enterprise, init, agent, publisher);
        if (inputs != null) inputs.requireReachOnlyInitAndAgent(init, agent, publisher);
    }

    /**
     * What this container's process actually sees: its own environment plus the deployment's.
     *
     * <p>Every arm must build a container's environment through this rather than reading
     * {@link ContainerSpec#environment()} directly, which is why the merge lives here and not in
     * the Docker arm — a per-arm copy would let the Kubernetes arm ship a unit with no proxy and
     * no error, and the symptom (calls that hang, then time out) names nothing.
     *
     * <p>With the agent behind its egress proxy, the agent gets the deployment's trust and NOT its proxy: every
     * proxy variable names the egress proxy, and nothing is exempt from it (an empty {@code NO_PROXY}). The egress
     * proxy chains to the corporate proxy itself. The other containers get the deployment's environment whole.
     *
     * <p>With a model proxy, the model proxy is the container behind the egress proxy and gets those variables; the
     * agent gets the deployment's trust only, since nothing a proxy variable could name is reachable from its network.
     */
    public Map<String, String> environmentFor(ContainerSpec container) {
        Objects.requireNonNull(container, "container");
        Map<String, String> merged = new LinkedHashMap<>(container.environment());
        merged.putAll(deploymentEnvironment(enterprise, egress, placementOf(container)));
        return Map.copyOf(merged);
    }

    /** Where a container sits relative to the egress proxy, which decides what the deployment adds to it. */
    private enum Placement { OUTSIDE, BEHIND_EGRESS, TRUST_ONLY }

    private Placement placementOf(ContainerSpec container) {
        if (modelProxy != null && container == modelProxy) return Placement.BEHIND_EGRESS;
        if (container != agent) return Placement.OUTSIDE;
        return modelProxy != null ? Placement.TRUST_ONLY : Placement.BEHIND_EGRESS;
    }

    /**
     * What the deployment adds to one container: all of it; its trust alone for the agent beside a model proxy; or
     * its trust and the egress proxy for the one container behind it.
     */
    private static Map<String, String> deploymentEnvironment(EnterpriseEnvironment enterprise, EgressPolicy egress,
                                                             Placement placement) {
        if (placement == Placement.OUTSIDE || !egress.proxiesTheAgent()) {
            return enterprise.environment();
        }
        if (placement == Placement.TRUST_ONLY) {
            return enterprise.trustEnvironment();
        }
        Map<String, String> applied = new LinkedHashMap<>(enterprise.trustEnvironment());
        for (String name : List.of("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy")) {
            applied.put(name, EgressPolicy.PROXY_ADDRESS);
        }
        applied.put("NO_PROXY", "");
        applied.put("no_proxy", "");
        return applied;
    }

    /** Rebuild with another init container, enumerating the components once. */
    public RunUnitSpec withInit(ContainerSpec container) {
        return new RunUnitSpec(runId, container, agent, publisher, enterprise, egress, modelProxy,
                memoryBytes, nanoCpus, diskBytes, wallClock, inputs);
    }

    /** Rebuild with another agent container, enumerating the components once. */
    public RunUnitSpec withAgent(ContainerSpec container) {
        return new RunUnitSpec(runId, init, container, publisher, enterprise, egress, modelProxy,
                memoryBytes, nanoCpus, diskBytes, wallClock, inputs);
    }

    /** Rebuild with another publisher, enumerating the components once. */
    public RunUnitSpec withPublisher(ContainerSpec container) {
        return new RunUnitSpec(runId, init, agent, container, enterprise, egress, modelProxy,
                memoryBytes, nanoCpus, diskBytes, wallClock, inputs);
    }

    /** Rebuild with another model proxy, or none, enumerating the components once. */
    public RunUnitSpec withModelProxy(ContainerSpec container) {
        return new RunUnitSpec(runId, init, agent, publisher, enterprise, egress, container,
                memoryBytes, nanoCpus, diskBytes, wallClock, inputs);
    }

    /** Rebuild with other inputs, enumerating the components once. */
    public RunUnitSpec withInputs(UnitInputs replacement) {
        return new RunUnitSpec(runId, init, agent, publisher, enterprise, egress, modelProxy,
                memoryBytes, nanoCpus, diskBytes, wallClock, replacement);
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
     * A model proxy reaches the vendor only through the unit's egress proxy, on a network the agent is not on. With
     * egress off there is neither, so it would have no way out and the agent's every model call would fail naming
     * nothing. It holds the credential, so it mounts nothing of the unit's: not the workspace the agent writes, not
     * the handoff, not the inputs.
     */
    private static void requireModelProxyPlacement(EgressPolicy egress, ContainerSpec modelProxy) {
        if (!egress.proxiesTheAgent()) {
            throw new IllegalArgumentException("a model proxy reaches its vendor through the unit's egress proxy, and"
                    + " this unit's egress is OFF; set SPIRE_RUN_EGRESS_MODE to report or enforce, or build the unit"
                    + " without a model proxy");
        }
        if (!modelProxy.mounts().isEmpty()) {
            throw new IllegalArgumentException("the model proxy holds the model credential and may mount nothing of"
                    + " the unit's, was: " + modelProxy.mounts());
        }
    }

    /**
     * A deployment-wide variable may not silently replace a container's own.
     *
     * <p>The two are set by different people — the operator's proxy configuration against the
     * builder's per-role wiring — so a collision means one of them is being ignored, and nothing
     * says which. Refusing is the only answer that cannot be wrong: either precedence rule is
     * defensible in the abstract and catastrophic in one direction, and the names that would
     * collide are the credentials each role is handed.
     *
     * <p><b>Not reachable today, and that is the reason to have it rather than an argument
     * against.</b> A review established that the deployment can currently set only nine fixed
     * names and every container-side name is a literal in the builder or a harness adapter, so no
     * collision can occur — an earlier version of this comment sold a scenario the code cannot
     * produce, which is the kind of prose this project treats as a defect. What the check buys is
     * that the property is structural BEFORE a second runtime arm, a per-repository environment or
     * an operator-supplied variable name can reach it; by then the silent-precedence bug would be
     * a live one and this refusal would be a behaviour change rather than an invariant.
     */
    private static void requireNoEnvironmentCollision(EnterpriseEnvironment enterprise, EgressPolicy egress,
                                                      ContainerSpec init, ContainerSpec agent,
                                                      ContainerSpec publisher, ContainerSpec modelProxy) {
        refuseCollision(deploymentEnvironment(enterprise, egress, Placement.OUTSIDE), init, "init");
        refuseCollision(deploymentEnvironment(enterprise, egress,
                modelProxy == null ? Placement.BEHIND_EGRESS : Placement.TRUST_ONLY), agent, "agent");
        refuseCollision(deploymentEnvironment(enterprise, egress, Placement.OUTSIDE), publisher, "publisher");
        if (modelProxy != null) {
            refuseCollision(deploymentEnvironment(enterprise, egress, Placement.BEHIND_EGRESS), modelProxy,
                    "model proxy");
        }
    }

    /**
     * Named by ROLE, not by image.
     *
     * <p>The init and publisher containers run the SAME image, so a message naming the image
     * cannot tell an operator which of the two collided — and the role is the only thing that
     * makes the collision actionable.
     */
    private static void refuseCollision(Map<String, String> deployment, ContainerSpec container,
                                        String role) {
        for (String name : deployment.keySet()) {
            if (container.environment().containsKey(name)) {
                throw new IllegalArgumentException("the deployment sets \"" + name
                        + "\", which this unit's " + role + " container also sets."
                        + " One of the two would be silently ignored, so neither is applied.");
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

package dev.codespire.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the deployment adds to each container of a {@link RunUnitSpec}, by where the container sits relative to the
 * egress proxy, and the checks that keep it from replacing a container's own variables or misplacing a model proxy.
 */
record UnitDeployment(EnterpriseEnvironment enterprise, EgressPolicy egress) {

    /** Where a container sits relative to the egress proxy, which decides what the deployment adds to it. */
    enum Placement { OUTSIDE, BEHIND_EGRESS, TRUST_ONLY }

    /**
     * What the deployment adds to one container: all of it; its trust alone for the agent beside a model proxy; or
     * its trust and the egress proxy for the one container behind it.
     */
    Map<String, String> environmentAt(Placement placement) {
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

    /**
     * A model proxy reaches the vendor only through the unit's egress proxy, on a network the agent is not on. With
     * egress off there is neither, so it would have no way out and the agent's every model call would fail naming
     * nothing. It holds the credential, so it mounts nothing of the unit's: not the workspace the agent writes, not
     * the handoff, not the inputs.
     */
    void requireModelProxyPlacement(ContainerSpec modelProxy) {
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
    void requireNoEnvironmentCollision(ContainerSpec init, ContainerSpec publisher) {
        refuseCollision(environmentAt(Placement.OUTSIDE), init, "init");
        refuseCollision(environmentAt(Placement.OUTSIDE), publisher, "publisher");
    }

    /** The same rule for the agent and, beside it, the model proxy, whose placements depend on each other. */
    void requireNoAgentSideCollision(ContainerSpec agent, ContainerSpec modelProxy) {
        refuseCollision(environmentAt(modelProxy == null ? Placement.BEHIND_EGRESS : Placement.TRUST_ONLY),
                agent, "agent");
        if (modelProxy != null) {
            refuseCollision(environmentAt(Placement.BEHIND_EGRESS), modelProxy, "model proxy");
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
}

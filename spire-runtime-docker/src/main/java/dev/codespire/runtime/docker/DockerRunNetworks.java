package dev.codespire.runtime.docker;

/**
 * A run unit's egress plumbing on Docker: the unit's internal network, which the agent joins and nothing routes out
 * of, and the egress proxy container, the agent's one way out.
 *
 * <p>Both are named from the run id and labelled with it, like the unit's volumes, so a restarted worker and the
 * orphan watchdog find them by label alone and destroy removes them with the unit.
 */
final class DockerRunNetworks {

    private DockerRunNetworks() {
    }

    /** The unit's internal network: the agent and the proxy, nothing else. */
    static String agentNetworkName(String runId) {
        return "spire-" + runId + "-agent";
    }

    /** The unit's egress proxy container. */
    static String proxyName(String runId) {
        return "spire-" + runId + "-proxy";
    }
}

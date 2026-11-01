package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Network;
import dev.codespire.runtime.EgressAttempt;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunUnitSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A run unit's egress plumbing on Docker: the unit's internal network, which the agent joins and nothing routes out
 * of, and the egress proxy container, the agent's one way out.
 *
 * <p><b>The topology.</b> The network is {@code --internal} with the bridge's IPv4 gateway mode {@code isolated}:
 * no route to other networks, and no address on the host either, so a container on it reaches nothing but the other
 * containers on it. {@code --internal} alone still gives the bridge a host address, and an agent that ignored its
 * proxy could reach every host service listening there (Docker's "gateway modes"). The agent is created on that
 * network alone. The proxy is created on the daemon's default bridge, which routes out, and connected to the internal
 * network under the alias {@link EgressPolicy#PROXY_ALIAS} before it starts, so the agent finds it by that name
 * through Docker's embedded DNS. init and the publisher stay on the default bridge as before: they run our own code
 * with the forge credential and never share the agent's network.
 *
 * <p><b>What the proxy is told</b> is {@link TinyproxyConfig}'s; <b>what it saw</b> is {@link ProxyLogParser}'s.
 *
 * <p><b>Cleanup.</b> The network and the proxy are named from the run id and labelled with it, like the unit's
 * volumes, so a restarted worker and the orphan watchdog find them by label alone, a network left by an interrupted
 * teardown included, and destroy removes them with the unit.
 */
final class DockerRunNetworks {

    /** The proxy container's role label. */
    static final String PROXY = "egress-proxy";

    /** The bridge driver option that keeps the internal network's gateway off the host, and its value. */
    static final String GATEWAY_MODE_IPV4 = "com.docker.network.bridge.gateway_mode_ipv4";

    static final String ISOLATED = "isolated";

    /** The first Docker Engine release with the isolated gateway mode. An older engine is refused, never trusted. */
    static final int MIN_ENGINE_MAJOR = 28;

    private final DockerClient client;

    private final EgressProxyContainer proxies;

    /** Whether this daemon has been seen to support the isolated gateway; asked once. */
    private volatile boolean engineChecked;

    DockerRunNetworks(DockerClient client, String image) {
        this.client = client;
        this.proxies = new EgressProxyContainer(client, image);
    }

    /** The unit's internal network: the agent and the proxy, nothing else. */
    static String agentNetworkName(String runId) {
        return DockerRunRuntime.volumeName(runId, "agent");
    }

    /** The unit's egress proxy container. */
    static String proxyName(String runId) {
        return DockerRunRuntime.volumeName(runId, "proxy");
    }

    String image() {
        return proxies.image();
    }

    /**
     * Creates the unit's internal network and starts its proxy on it. Called before the agent exists, so the agent's
     * first call already has somewhere to go.
     *
     * @param labels the run's labels (its id, and the hold for a held unit), set on the network and the proxy alike;
     *               the proxy also gets its role
     */
    void open(RunUnitSpec spec, Map<String, String> labels) {
        requireIsolatedGatewaySupport();
        String network = createNetwork(agentNetworkName(spec.runId()), labels);
        String subnet = subnetOf(network);
        Map<String, String> proxyLabels = new LinkedHashMap<>(labels);
        proxyLabels.put(DockerRunRuntime.ROLE_LABEL, PROXY);
        String proxy = proxies.create(spec, subnet, proxyLabels);
        client.connectToNetworkCmd()
                .withNetworkId(network)
                .withContainerId(proxy)
                .withContainerNetwork(new ContainerNetwork().withAliases(EgressPolicy.PROXY_ALIAS))
                .exec();
        proxies.start(proxy);
    }

    /**
     * An engine before the isolated gateway mode would accept the option and still give the bridge a host address,
     * so the barrier would be claimed and absent. Refused with the version and the way out, rather than run that way.
     */
    private void requireIsolatedGatewaySupport() {
        if (engineChecked) {
            return;
        }
        String version = client.versionCmd().exec().getVersion();
        int major;
        try {
            major = Integer.parseInt(version.split("\\.")[0].strip());
        } catch (RuntimeException unparsable) {
            major = -1;
        }
        if (major < MIN_ENGINE_MAJOR) {
            throw new IllegalStateException("The Docker Engine here reports version " + version + "; an agent behind"
                    + " the egress proxy needs " + MIN_ENGINE_MAJOR + ".0 or later, the first with the isolated gateway"
                    + " mode that keeps its network off the host. Upgrade the engine, or set SPIRE_RUN_EGRESS_MODE=off");
        }
        engineChecked = true;
    }

    private String createNetwork(String name, Map<String, String> labels) {
        try {
            return client.createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    .withInternal(true)
                    .withOptions(Map.of(GATEWAY_MODE_IPV4, ISOLATED))
                    .withLabels(labels)
                    .exec().getId();
        } catch (ConflictException exists) {
            // A redelivered command for the same run meets its own network from the first attempt. Reused only when
            // it is this run's, internal and gateway-isolated; anything else under the name is not ours to attach an
            // agent to, and one without the isolation would put the agent where it can reach the host.
            Network found = client.inspectNetworkCmd().withNetworkId(name).exec();
            Map<String, String> options = found.getOptions() == null ? Map.of() : found.getOptions();
            if (!labels.get(DockerRunRuntime.RUN_ID_LABEL).equals(found.getLabels().get(DockerRunRuntime.RUN_ID_LABEL))
                    || !Boolean.TRUE.equals(found.getInternal()) || !ISOLATED.equals(options.get(GATEWAY_MODE_IPV4))) {
                throw new IllegalStateException("A network named " + name + " exists and is not this run's internal,"
                        + " gateway-isolated network", exists);
            }
            return found.getId();
        }
    }

    private String subnetOf(String network) {
        var ipam = client.inspectNetworkCmd().withNetworkId(network).exec().getIpam();
        if (ipam == null || ipam.getConfig() == null || ipam.getConfig().isEmpty()
                || ipam.getConfig().getFirst().getSubnet() == null) {
            // Without a subnet the proxy could not be limited to the agent's side, and an open proxy on the default
            // bridge is a way out for every container there. Refused rather than started that way.
            throw new IllegalStateException("The daemon reported no subnet for network " + network);
        }
        return ipam.getConfig().getFirst().getSubnet();
    }

    /**
     * What the unit's proxy saw, read once and never followed, after the agent has gone. Empty for a unit with no
     * proxy.
     */
    List<EgressAttempt> egressOf(String runId) {
        var proxy = client.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(DockerRunRuntime.RUN_ID_LABEL, runId, DockerRunRuntime.ROLE_LABEL, PROXY))
                .exec();
        if (proxy.isEmpty()) {
            return List.of();
        }
        return ProxyLogParser.parse(proxies.logOf(proxy.getFirst().getId()));
    }

    /** Every network labelled with this run, removed. Called after the unit's containers are gone. */
    void removeNetworks(String runId) {
        for (Network network : networksOf(runId)) {
            try {
                client.removeNetworkCmd(network.getId()).exec();
            } catch (NotFoundException gone) {
                // removed between the list and here
            }
        }
    }

    /** The labels of every network this run holds, for the hold check. */
    List<Map<String, String>> networkLabels(String runId) {
        List<Map<String, String>> labels = new ArrayList<>();
        networksOf(runId).forEach(network -> labels.add(network.getLabels()));
        return labels;
    }

    /**
     * Every run that still holds a labelled network, as run id to network id. Discovery reads it beside the
     * containers, so a network left by an interrupted teardown is still found and removed.
     */
    Map<String, String> runsHoldingNetworks() {
        Map<String, String> runs = new LinkedHashMap<>();
        for (Network network : client.listNetworksCmd().withFilter("label", List.of(DockerRunRuntime.RUN_ID_LABEL)).exec()) {
            String runId = network.getLabels() == null ? null : network.getLabels().get(DockerRunRuntime.RUN_ID_LABEL);
            if (runId != null && !runId.isBlank()) runs.putIfAbsent(runId, network.getId());
        }
        return runs;
    }

    private List<Network> networksOf(String runId) {
        return client.listNetworksCmd()
                .withFilter("label", List.of(DockerRunRuntime.RUN_ID_LABEL + "=" + runId))
                .exec();
    }
}

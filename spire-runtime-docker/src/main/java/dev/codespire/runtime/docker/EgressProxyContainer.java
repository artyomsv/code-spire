package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunUnitSpec;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The egress proxy container itself: created with its rendered configuration, started, waited on until it listens,
 * and read back. The network it sits on is {@link DockerRunNetworks}'s.
 *
 * <p>Its sandbox is the agent's minus everything it does not need: {@code cap-drop ALL}, {@code no-new-privileges},
 * a read-only root filesystem with a small {@code /tmp} for its two files, and small memory, CPU and pid bounds.
 */
final class EgressProxyContainer {

    /** What the proxy may spend: tinyproxy needs a few megabytes, and a bound keeps a flood from costing more. */
    static final long PROXY_MEMORY_BYTES = 64L * 1024 * 1024;

    static final long PROXY_NANO_CPUS = 500_000_000L;

    static final long PROXY_PIDS_LIMIT = 64;

    /** Where the image's command writes the two files; the only writable path in the container. */
    static final String PROXY_TMPFS = "rw,nosuid,nodev,noexec,size=1m";

    /** How long a starting proxy may take to listen; tinyproxy takes milliseconds. */
    private static final Duration PROXY_START_TIMEOUT = Duration.ofSeconds(20);

    /**
     * What the proxy enforces and for whom.
     *
     * @param egress what the proxy enforces: the spec's own, or for a unit with a model proxy its hosts in ENFORCE
     * @param subnet the one network whose containers may use the proxy
     */
    record Placement(EgressPolicy egress, String subnet) {
    }

    private final DockerClient client;

    private final Sidecars sidecars;

    private final String image;

    EgressProxyContainer(DockerClient client, String image) {
        this.client = client;
        this.sidecars = new Sidecars(client);
        this.image = image;
    }

    String image() {
        return image;
    }

    /** Creates the unit's proxy on the default bridge, configured for this run. Not started. */
    String create(RunUnitSpec spec, Placement placement, Map<String, String> labels) {
        String name = DockerRunNetworks.proxyName(spec.runId());
        sidecars.removeStale(name);
        HostConfig host = HostConfig.newHostConfig()
                .withMemory(PROXY_MEMORY_BYTES)
                .withNanoCPUs(PROXY_NANO_CPUS)
                .withPidsLimit(PROXY_PIDS_LIMIT)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withCapDrop(Capability.ALL)
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/tmp", PROXY_TMPFS))
                // Its log is read after the agent has gone; destroy removes it.
                .withAutoRemove(false);
        return client.createContainerCmd(image)
                .withName(name)
                .withEnv(List.of(
                        "SPIRE_EGRESS_CONF=" + TinyproxyConfig.configFor(placement.egress(), spec.enterprise(),
                                placement.subnet()),
                        "SPIRE_EGRESS_FILTER=" + TinyproxyConfig.filterFor(placement.egress())))
                .withLabels(labels)
                .withHostConfig(host)
                .exec().getId();
    }

    /**
     * Starts the proxy and waits until it is listening. The agent starts the moment this returns, and a harness whose
     * first call meets a refused connection fails the run on a race with nothing to do with the run. A proxy that
     * exits instead (a configuration it refused) is reported with the last lines of its own log, which name the
     * directive.
     */
    void start(String proxy) {
        sidecars.startAndAwaitListening(
                new Sidecars.Start(proxy, EgressPolicy.PROXY_PORT, "the egress proxy", PROXY_START_TIMEOUT));
    }

    /** The whole log of a proxy, bounded in characters: what {@link ProxyLogParser} reads. */
    List<String> logOf(String proxy) {
        return sidecars.logOf(proxy, Sidecars.LogBound.whole(ProxyLogParser.MAX_LOG_CHARS));
    }
}

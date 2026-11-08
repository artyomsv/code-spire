package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.ModelProxyReport;
import dev.codespire.runtime.RunUnitSpec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A unit's model proxy container (harness reviews step 3): created on the model network, joined to the agent's
 * network under {@link RunUnitSpec#MODEL_PROXY_ALIAS}, started, waited on until it listens, and read back. The
 * networks are {@link DockerRunNetworks}'s.
 *
 * <p>It is the one container of the unit holding the model credential, so its sandbox is the egress proxy's: {@code
 * cap-drop ALL}, {@code no-new-privileges}, a read-only root filesystem with a small {@code /tmp}, and small memory, CPU
 * and pid bounds. The memory is larger because it is a JVM. It mounts the deployment's host trust mounts, read-only,
 * and nothing of the unit's ({@link RunUnitSpec} refuses a model proxy with mounts). Its environment, the credential
 * included, comes from {@link RunUnitSpec#environmentFor}; the credential is never a label.
 */
final class ModelProxyContainer {

    /** The container's role label. */
    static final String ROLE = "model-proxy";

    /** A small JVM: the image caps its heap at 75% of this. */
    static final long MEMORY_BYTES = 192L * 1024 * 1024;

    static final long NANO_CPUS = 500_000_000L;

    static final long PIDS_LIMIT = 64;

    /** The JVM writes nothing it needs (no perf data); this is for what it might write anyway. */
    static final String TMPFS = "rw,nosuid,nodev,noexec,size=8m";

    /** How long a starting model proxy may take to listen: a JVM start, well under a second on a warm host. */
    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);

    private final DockerClient client;

    ModelProxyContainer(DockerClient client) {
        this.client = client;
    }

    /** The unit's model proxy container. */
    static String containerName(String runId) {
        return DockerRunRuntime.volumeName(runId, "model-proxy");
    }

    /**
     * Creates the model proxy on {@code modelNetwork}, where the egress proxy is its way out, and joins it to
     * {@code agentNetwork} under its alias. Not started.
     */
    String create(RunUnitSpec spec, String modelNetwork, String agentNetwork, Map<String, String> labels) {
        ContainerSpec proxy = spec.modelProxy();
        String name = containerName(spec.runId());
        EgressProxyContainer.removeStale(client, name);
        HostConfig host = HostConfig.newHostConfig()
                .withBinds(DockerRunRuntime.bindsOf(spec.runId(), spec.hostMounts(), proxy))
                .withMemory(MEMORY_BYTES)
                .withNanoCPUs(NANO_CPUS)
                .withPidsLimit(PIDS_LIMIT)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withCapDrop(Capability.ALL)
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/tmp", TMPFS))
                .withNetworkMode(modelNetwork)
                // Its log is read after the agent has gone; destroy removes it.
                .withAutoRemove(false);
        List<String> env = new ArrayList<>();
        spec.environmentFor(proxy).forEach((variable, value) -> env.add(variable + "=" + value));
        var create = client.createContainerCmd(proxy.image())
                .withName(name)
                .withEnv(env)
                .withLabels(labels)
                .withHostConfig(host);
        if (!proxy.argv().isEmpty()) create.withCmd(proxy.argv());
        if (proxy.entrypoint() != null) create.withEntrypoint(proxy.entrypoint());
        String id = create.exec().getId();
        client.connectToNetworkCmd()
                .withNetworkId(agentNetwork)
                .withContainerId(id)
                .withContainerNetwork(new com.github.dockerjava.api.model.ContainerNetwork()
                        .withAliases(RunUnitSpec.MODEL_PROXY_ALIAS))
                .exec();
        return id;
    }

    /** Starts it and waits until it listens, so the agent's first model call has somewhere to go. */
    void start(String id) {
        EgressProxyContainer.startAndAwaitListening(client, id, RunUnitSpec.MODEL_PROXY_PORT, "the model proxy",
                START_TIMEOUT);
    }

    /** What the model proxy saw, from its whole log, bounded like the egress proxy's. */
    ModelProxyReport reportOf(String id) {
        return ModelProxyLogParser.parse(
                EgressProxyContainer.logOf(client, id, Integer.MAX_VALUE, ModelProxyLogParser.MAX_LOG_CHARS));
    }
}

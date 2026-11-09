package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
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

    /**
     * How long a starting model proxy may take to listen: a JVM start under 0.5 CPU, about 2 to 3.4 s when measured,
     * on a host that may be busy with other work. In the live acceptance of harness reviews step 3 one start was
     * running and not seen listening within the earlier 30 s, cause unknown; a timeout now reports the container's
     * state and the tail of its log ({@link SidecarStartFailure}), so the next one says which it was.
     */
    static final Duration START_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Its log, one line per request, rotated: at most three files of 2 MiB (about 100,000 lines), which stays under
     * {@link ModelProxyLogParser#MAX_LOG_CHARS} when read back. The json-file driver keeps rotated files readable
     * through {@code docker logs}, so a run of ordinary length is read whole; a run past that loses its oldest lines
     * rather than the host's disk.
     */
    static final LogConfig LOG =
            new LogConfig(LogConfig.LoggingType.JSON_FILE, Map.of("max-size", "2m", "max-file", "3"));

    private final DockerClient client;

    private final Sidecars sidecars;

    ModelProxyContainer(DockerClient client) {
        this.client = client;
        this.sidecars = new Sidecars(client);
    }

    /** The two internal networks a model proxy sits on: the model network (its way out) and the agent's. */
    record Networks(String model, String agent) {
    }

    /** The unit's model proxy container. */
    static String containerName(String runId) {
        return DockerRunRuntime.volumeName(runId, "model-proxy");
    }

    /**
     * Creates the model proxy on the model network, where the egress proxy is its way out, and joins it to the agent's
     * network under its alias. Not started.
     */
    String create(RunUnitSpec spec, Networks networks, Map<String, String> labels) {
        ContainerSpec proxy = spec.modelProxy();
        String name = containerName(spec.runId());
        sidecars.removeStale(name);
        List<String> env = new ArrayList<>();
        spec.environmentFor(proxy).forEach((variable, value) -> env.add(variable + "=" + value));
        var create = client.createContainerCmd(proxy.image())
                .withName(name)
                .withEnv(env)
                .withLabels(labels)
                .withHostConfig(hostConfig(spec, networks.model()));
        if (!proxy.argv().isEmpty()) create.withCmd(proxy.argv());
        if (proxy.entrypoint() != null) create.withEntrypoint(proxy.entrypoint());
        String id = create.exec().getId();
        joinAgentNetwork(id, networks.agent());
        return id;
    }

    private static HostConfig hostConfig(RunUnitSpec spec, String modelNetwork) {
        return HostConfig.newHostConfig()
                .withBinds(DockerRunRuntime.bindsOf(spec.runId(), spec.hostMounts(), spec.modelProxy()))
                .withMemory(MEMORY_BYTES)
                .withNanoCPUs(NANO_CPUS)
                .withPidsLimit(PIDS_LIMIT)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withCapDrop(Capability.ALL)
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/tmp", TMPFS))
                .withNetworkMode(modelNetwork)
                .withLogConfig(LOG)
                // Its log is read after the agent has gone; destroy removes it.
                .withAutoRemove(false);
    }

    private void joinAgentNetwork(String id, String agentNetwork) {
        client.connectToNetworkCmd()
                .withNetworkId(agentNetwork)
                .withContainerId(id)
                .withContainerNetwork(new com.github.dockerjava.api.model.ContainerNetwork()
                        .withAliases(RunUnitSpec.MODEL_PROXY_ALIAS))
                .exec();
    }

    /** Starts it and waits until it listens, so the agent's first model call has somewhere to go. */
    void start(String id) {
        sidecars.startAndAwaitListening(
                new Sidecars.Start(id, RunUnitSpec.MODEL_PROXY_PORT, "the model proxy", START_TIMEOUT));
    }

    /** What the model proxy saw, from its whole log, bounded like the egress proxy's. */
    ModelProxyReport reportOf(String id) {
        return ModelProxyLogParser.parse(
                sidecars.logOf(id, Sidecars.LogBound.whole(ModelProxyLogParser.MAX_LOG_CHARS)));
    }
}

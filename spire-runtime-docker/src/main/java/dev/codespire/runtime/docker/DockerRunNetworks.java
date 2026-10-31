package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import dev.codespire.runtime.EgressAttempt;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.RunUnitSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A run unit's egress plumbing on Docker: the unit's internal network, which the agent joins and nothing routes out
 * of, and the egress proxy container, the agent's one way out.
 *
 * <p><b>The topology.</b> The network is {@code --internal}: no gateway, so a container on it reaches nothing but the
 * other containers on it. The agent is created on that network alone. The proxy is created on the daemon's default
 * bridge, which routes out, and connected to the internal network under the alias {@link EgressPolicy#PROXY_ALIAS}
 * before it starts, so the agent finds it by that name through Docker's embedded DNS. init and the publisher stay on
 * the default bridge as before: they run our own code with the forge credential and never share the agent's network.
 *
 * <p><b>What the proxy is told.</b> Its tinyproxy configuration is rendered here per run and handed over as a
 * variable: it accepts clients from the internal network's subnet only, forwards CONNECT to ports 443 and 80, logs
 * every request, refuses every host the harness did not declare when the mode is enforce, and chains to the
 * corporate proxy when the deployment has one.
 *
 * <p><b>Cleanup.</b> The network and the proxy are named from the run id and labelled with it, like the unit's
 * volumes, so a restarted worker and the orphan watchdog find them by label alone and destroy removes them with the
 * unit.
 */
final class DockerRunNetworks {

    /** The proxy container's role label. */
    static final String PROXY = "egress-proxy";

    /** What the proxy may spend: tinyproxy needs a few megabytes, and a bound keeps a flood from costing more. */
    static final long PROXY_MEMORY_BYTES = 64L * 1024 * 1024;

    static final long PROXY_NANO_CPUS = 500_000_000L;

    static final long PROXY_PIDS_LIMIT = 64;

    /** Where the image's command writes the two files; the only writable path in the container. */
    static final String PROXY_TMPFS = "rw,nosuid,nodev,noexec,size=1m";

    /**
     * The proxy log is the agent's doing: every line names a host the agent chose. These bound what reading it costs
     * the worker, whatever the agent wrote.
     */
    static final int MAX_LOG_CHARS = 8 * 1024 * 1024;

    static final int MAX_LINE_CHARS = 4096;

    static final int MAX_ATTEMPTS = 256;

    /** How long a starting proxy may take to listen; tinyproxy takes milliseconds. */
    private static final Duration PROXY_START_TIMEOUT = Duration.ofSeconds(20);

    private static final Duration PROXY_POLL = Duration.ofMillis(100);

    /** The port as /proc/net/tcp prints it (8888 is 22B8); tinyproxy binds every address, so either table. */
    private static final String LISTENING_PROBE = "grep -qi '" + String.format(":%04X ", EgressPolicy.PROXY_PORT)
            + "' /proc/net/tcp /proc/net/tcp6 2>/dev/null";

    /** A read of an exited container's log is finite; this bounds one the daemon is slow to finish. */
    private static final Duration LOG_READ_TIMEOUT = Duration.ofSeconds(30);

    private static final Pattern REQUEST =
            Pattern.compile("Request \\(file descriptor \\d+\\): ([A-Z]{3,10}) (\\S{1,2048}) HTTP/\\d(?:\\.\\d)?$");

    private static final Pattern REFUSED = Pattern.compile("Proxying refused on filtered (?:domain|url) \"([^\"]{1,2048})\"");

    private static final Pattern ABSOLUTE_URL = Pattern.compile("(?i)^(https?)://([^/?#]+)");

    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?|\\[[0-9a-f:.]{2,45}\\]");

    /** A corporate upstream as tinyproxy reads it: {@code [user:password@]host:port}, nothing that ends the line. */
    private static final Pattern UPSTREAM = Pattern.compile("[^\\s\"/]+:\\d{1,5}");

    private static final Pattern NO_PROXY_ENTRY = Pattern.compile("[A-Za-z0-9.:/-]{1,253}");

    private final DockerClient client;

    private final String image;

    DockerRunNetworks(DockerClient client, String image) {
        this.client = client;
        this.image = image;
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
        return image;
    }

    /**
     * Creates the unit's internal network and starts its proxy on it. Called before the agent exists, so the agent's
     * first call already has somewhere to go.
     *
     * @param labels the run's labels (its id, and the hold for a held unit), set on the network and the proxy alike;
     *               the proxy also gets its role
     */
    void open(RunUnitSpec spec, Map<String, String> labels) {
        String network = createNetwork(agentNetworkName(spec.runId()), labels);
        String subnet = subnetOf(network);
        Map<String, String> proxyLabels = new LinkedHashMap<>(labels);
        proxyLabels.put(DockerRunRuntime.ROLE_LABEL, PROXY);
        String proxy = createProxy(spec, subnet, proxyLabels);
        client.connectToNetworkCmd()
                .withNetworkId(network)
                .withContainerId(proxy)
                .withContainerNetwork(new ContainerNetwork().withAliases(EgressPolicy.PROXY_ALIAS))
                .exec();
        client.startContainerCmd(proxy).exec();
        awaitListening(proxy);
    }

    /**
     * Waits until the proxy is listening. The agent starts the moment this returns, and a harness whose first call
     * meets a refused connection fails the run on a race with nothing to do with the run.
     *
     * <p>Read from {@code /proc/net/tcp} inside the proxy, because the image has no probe of its own and this needs
     * none: a listening socket on the port is the fact. A proxy that exits instead (a configuration it refused) is
     * reported with the first lines of its own log, which name the directive.
     */
    private void awaitListening(String proxy) {
        long deadline = System.nanoTime() + PROXY_START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var state = client.inspectContainerCmd(proxy).exec().getState();
            if (!Boolean.TRUE.equals(state.getRunning())) {
                throw new IllegalStateException("the egress proxy exited before it listened (exit "
                        + state.getExitCodeLong() + "): " + String.join(" | ", firstLogLines(proxy)));
            }
            if (listening(proxy)) {
                return;
            }
            try {
                Thread.sleep(PROXY_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for the egress proxy", e);
            }
        }
        throw new IllegalStateException("the egress proxy did not listen within " + PROXY_START_TIMEOUT);
    }

    private boolean listening(String proxy) {
        try {
            String exec = client.execCreateCmd(proxy).withCmd("sh", "-c", LISTENING_PROBE).exec().getId();
            try (ResultCallback.Adapter<Frame> probe = client.execStartCmd(exec).exec(new ResultCallback.Adapter<Frame>())) {
                probe.awaitCompletion(5, TimeUnit.SECONDS);
            }
            Long exit = client.inspectExecCmd(exec).exec().getExitCodeLong();
            return exit != null && exit == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException notYet) {
            return false;
        }
    }

    private List<String> firstLogLines(String proxy) {
        List<String> lines = new ArrayList<>();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (lines.size() < 20) lines.add(new String(frame.getPayload(), StandardCharsets.UTF_8).strip());
            }
        };
        try (ResultCallback.Adapter<Frame> stream = client.logContainerCmd(proxy).withStdOut(true).withStdErr(true)
                .withTail(20).exec(callback)) {
            stream.awaitCompletion(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException unreadable) {
            lines.add("(log unreadable)");
        }
        return lines;
    }

    private String createNetwork(String name, Map<String, String> labels) {
        try {
            return client.createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    .withInternal(true)
                    .withLabels(labels)
                    .exec().getId();
        } catch (ConflictException exists) {
            // A redelivered command for the same run meets its own network from the first attempt. Reused only when
            // it is this run's and internal; anything else under the name is not ours to attach an agent to.
            Network found = client.inspectNetworkCmd().withNetworkId(name).exec();
            if (!labels.get(DockerRunRuntime.RUN_ID_LABEL).equals(found.getLabels().get(DockerRunRuntime.RUN_ID_LABEL))
                    || !Boolean.TRUE.equals(found.getInternal())) {
                throw new IllegalStateException("A network named " + name + " exists and is not this run's internal network",
                        exists);
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

    private String createProxy(RunUnitSpec spec, String subnet, Map<String, String> labels) {
        String name = proxyName(spec.runId());
        removeStaleProxy(name);
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
                        "SPIRE_EGRESS_CONF=" + configFor(spec.egress(), spec.enterprise(), subnet),
                        "SPIRE_EGRESS_FILTER=" + filterFor(spec.egress())))
                .withLabels(labels)
                .withHostConfig(host)
                .exec().getId();
    }

    /** A proxy left by an earlier attempt of the same run, which would hold the name. Only ever this run's. */
    private void removeStaleProxy(String name) {
        try {
            client.removeContainerCmd(name).withForce(true).exec();
        } catch (NotFoundException absent) {
            // the ordinary case
        }
    }

    /**
     * The proxy's tinyproxy configuration.
     *
     * <p>{@code Allow} limits clients to the internal network: the proxy also sits on the default bridge, and without
     * it every container there could use it as a way out. Report mode has no filter, so every host is let through and
     * logged; enforce mode denies by default and allows the declared hosts exactly.
     */
    static String configFor(EgressPolicy egress, EnterpriseEnvironment enterprise, String subnet) {
        List<String> lines = new ArrayList<>(List.of(
                "Port " + EgressPolicy.PROXY_PORT,
                "Timeout 600",
                "MaxClients 64",
                // No LogFile, and that is what sends the log to stdout. tinyproxy opens a named log file through
                // create_file_safely, which refuses a symlink or anything not a regular file -- so "/dev/stdout"
                // failed, tinyproxy fell back to syslog, and with no syslog in the container every line was lost.
                "LogLevel Connect",
                "DisableViaHeader Yes",
                "Allow " + subnet));
        EgressPolicy.PORTS.stream().sorted().forEach(port -> lines.add("ConnectPort " + port));
        if (egress.mode() == EgressPolicy.Mode.ENFORCE) {
            lines.add("Filter \"/tmp/filter\"");
            lines.add("FilterDefaultDeny Yes");
        }
        lines.addAll(upstreamLines(enterprise));
        return String.join("\n", lines);
    }

    /**
     * The corporate proxy to chain to, and the hosts it is not used for.
     *
     * <p>The value is the operator's and may carry basic auth; tinyproxy takes it as {@code user:password@host:port}
     * after the scheme. It reaches the proxy container's environment, as it already reaches init's and the
     * publisher's, and never a label or a log line here.
     */
    private static List<String> upstreamLines(EnterpriseEnvironment enterprise) {
        var upstream = enterprise.upstreamProxy();
        if (upstream.isEmpty()) {
            return List.of();
        }
        List<String> noProxy = enterprise.noProxy();
        if (noProxy.contains("*")) {
            return List.of();
        }
        String target = upstream.orElseThrow().replaceFirst("(?i)^https?://", "").replaceFirst("/+$", "");
        if (!UPSTREAM.matcher(target).matches()) {
            throw new IllegalArgumentException("The corporate proxy is not of the form [scheme://][user:password@]host:port,"
                    + " so the egress proxy cannot chain to it");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Upstream http " + target);
        for (String entry : noProxy) {
            if (NO_PROXY_ENTRY.matcher(entry).matches()) {
                lines.add("Upstream none \"" + entry + "\"");
            }
        }
        return lines;
    }

    /** One anchored pattern per declared host; tinyproxy matches host names case-insensitively. */
    static String filterFor(EgressPolicy egress) {
        return String.join("\n", egress.hosts().stream().sorted()
                .map(host -> "^" + host.replace(".", "\\.") + "$")
                .toList());
    }

    /**
     * Every host and port the proxy log shows a request for, allowed unless the proxy refused that host.
     *
     * <p>The log is agent-controlled text, so it is parsed strictly: a request line whose target does not parse as a
     * host, or a host that is not a plain name or address, is not reported at all rather than reported mangled, and
     * the answer stops at {@link #MAX_ATTEMPTS} distinct attempts. A URL's user information never leaves here.
     */
    static List<EgressAttempt> parse(List<String> lines) {
        // Keyed by host and port; the value says whether the port alone already refuses it.
        Map<Map.Entry<String, Integer>, Boolean> requested = new LinkedHashMap<>();
        Set<String> refused = new LinkedHashSet<>();
        for (String line : lines) {
            if (line.length() > MAX_LINE_CHARS) continue;
            Matcher request = REQUEST.matcher(line);
            if (request.find()) {
                boolean connect = "CONNECT".equals(request.group(1));
                target(request.group(1), request.group(2)).ifPresent(hostPort -> {
                    if (requested.size() < MAX_ATTEMPTS || requested.containsKey(hostPort)) {
                        // tinyproxy refuses a CONNECT to a port outside ConnectPort and logs that below this level.
                        boolean portRefused = connect && !EgressPolicy.PORTS.contains(hostPort.getValue());
                        requested.merge(hostPort, portRefused, Boolean::logicalOr);
                    }
                });
                continue;
            }
            Matcher refusal = REFUSED.matcher(line);
            if (refusal.find()) {
                hostOf(refusal.group(1)).ifPresent(refused::add);
            }
        }
        List<EgressAttempt> attempts = new ArrayList<>();
        requested.forEach((hostPort, portRefused) -> attempts.add(new EgressAttempt(hostPort.getKey(), hostPort.getValue(),
                !portRefused && !refused.contains(hostPort.getKey()))));
        return List.copyOf(attempts);
    }

    /** The host and port a request line names: {@code host:port} for CONNECT, an absolute URL otherwise. */
    private static Optional<Map.Entry<String, Integer>> target(String method, String target) {
        String authority;
        int defaultPort;
        if ("CONNECT".equals(method)) {
            authority = target;
            defaultPort = 443;
        } else {
            Matcher url = ABSOLUTE_URL.matcher(target);
            if (!url.find()) return Optional.empty();
            authority = url.group(2);
            defaultPort = "https".equalsIgnoreCase(url.group(1)) ? 443 : 80;
        }
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        int colon = authority.lastIndexOf(':');
        boolean hasPort = colon > authority.lastIndexOf(']');
        String host = hasPort ? authority.substring(0, colon) : authority;
        int port = defaultPort;
        if (hasPort) {
            String digits = authority.substring(colon + 1);
            if (!digits.matches("\\d{1,5}")) return Optional.empty();
            port = Integer.parseInt(digits);
            if (port < 1 || port > 65535) return Optional.empty();
        }
        int resolvedPort = port;
        return hostOf(host).map(name -> Map.entry(name, resolvedPort));
    }

    private static Optional<String> hostOf(String raw) {
        String host = raw.toLowerCase(Locale.ROOT);
        return HOST.matcher(host).matches() ? Optional.of(host) : Optional.empty();
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
        StringBuilder log = new StringBuilder();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (log.length() < MAX_LOG_CHARS) {
                    log.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
            }
        };
        try (ResultCallback.Adapter<Frame> stream = client.logContainerCmd(proxy.getFirst().getId())
                .withStdOut(true).withStdErr(true).withTailAll()
                .exec(callback)) {
            stream.awaitCompletion(LOG_READ_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException closing) {
            // The answer is what was read before the stream failed to close.
        }
        return parse(Arrays.stream(log.toString().split("\\R")).map(String::strip).toList());
    }

    /** Every network labelled with this run, removed. Called after the unit's containers are gone. */
    void removeNetworks(String runId) {
        for (Network network : client.listNetworksCmd()
                .withFilter("label", List.of(DockerRunRuntime.RUN_ID_LABEL + "=" + runId))
                .exec()) {
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
        for (Network network : client.listNetworksCmd()
                .withFilter("label", List.of(DockerRunRuntime.RUN_ID_LABEL + "=" + runId))
                .exec()) {
            labels.add(network.getLabels());
        }
        return labels;
    }
}

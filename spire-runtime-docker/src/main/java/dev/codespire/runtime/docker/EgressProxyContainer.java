package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunUnitSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    private static final Duration PROXY_POLL = Duration.ofMillis(100);

    /** The port as /proc/net/tcp prints it (8888 is 22B8); tinyproxy binds every address, so either table. */
    private static final String LISTENING_PROBE = "grep -qi '" + String.format(":%04X ", EgressPolicy.PROXY_PORT)
            + "' /proc/net/tcp /proc/net/tcp6 2>/dev/null";

    /** A read of an exited container's log is finite; this bounds one the daemon is slow to finish. */
    private static final Duration LOG_READ_TIMEOUT = Duration.ofSeconds(30);

    private final DockerClient client;

    private final String image;

    EgressProxyContainer(DockerClient client, String image) {
        this.client = client;
        this.image = image;
    }

    String image() {
        return image;
    }

    /** Creates the unit's proxy on the default bridge, configured for this run. Not started. */
    String create(RunUnitSpec spec, String subnet, Map<String, String> labels) {
        String name = DockerRunNetworks.proxyName(spec.runId());
        removeStale(name);
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
                        "SPIRE_EGRESS_CONF=" + TinyproxyConfig.configFor(spec.egress(), spec.enterprise(), subnet),
                        "SPIRE_EGRESS_FILTER=" + TinyproxyConfig.filterFor(spec.egress())))
                .withLabels(labels)
                .withHostConfig(host)
                .exec().getId();
    }

    /** A proxy left by an earlier attempt of the same run, which would hold the name. Only ever this run's. */
    private void removeStale(String name) {
        try {
            client.removeContainerCmd(name).withForce(true).exec();
        } catch (NotFoundException absent) {
            // the ordinary case
        }
    }

    /**
     * Starts the proxy and waits until it is listening. The agent starts the moment this returns, and a harness whose
     * first call meets a refused connection fails the run on a race with nothing to do with the run.
     *
     * <p>Read from {@code /proc/net/tcp} inside the proxy, because the image has no probe of its own and this needs
     * none: a listening socket on the port is the fact. A proxy that exits instead (a configuration it refused) is
     * reported with the last lines of its own log, which name the directive.
     */
    void start(String proxy) {
        client.startContainerCmd(proxy).exec();
        long deadline = System.nanoTime() + PROXY_START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var state = client.inspectContainerCmd(proxy).exec().getState();
            if (!Boolean.TRUE.equals(state.getRunning())) {
                throw new IllegalStateException("the egress proxy exited before it listened (exit "
                        + state.getExitCodeLong() + "): " + String.join(" | ", logOf(proxy, 20, 4096)));
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

    /** The whole log of a proxy, bounded in characters: what {@link ProxyLogParser} reads. */
    List<String> logOf(String proxy) {
        return logOf(proxy, Integer.MAX_VALUE, ProxyLogParser.MAX_LOG_CHARS);
    }

    /** A container's log lines, bounded in characters, and to the last {@code tail} lines when that is finite. */
    private List<String> logOf(String container, int tail, int maxChars) {
        StringBuilder log = new StringBuilder();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (log.length() < maxChars) {
                    log.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
            }
        };
        var command = client.logContainerCmd(container).withStdOut(true).withStdErr(true);
        command = tail == Integer.MAX_VALUE ? command.withTailAll() : command.withTail(tail);
        try (ResultCallback.Adapter<Frame> stream = command.exec(callback)) {
            stream.awaitCompletion(LOG_READ_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException closing) {
            // The answer is what was read before the stream failed to close.
        }
        return Arrays.stream(log.toString().split("\\R")).map(String::strip).filter(line -> !line.isEmpty()).toList();
    }
}

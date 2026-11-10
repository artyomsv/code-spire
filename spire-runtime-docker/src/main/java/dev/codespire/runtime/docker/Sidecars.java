package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What a unit's two sidecars, the egress proxy and the model proxy, share: clearing a stale container of the same
 * name, starting one and waiting until it listens, and reading its log, bounded.
 */
final class Sidecars {

    private static final Duration POLL = Duration.ofMillis(100);

    /** A read of an exited container's log is finite; this bounds one the daemon is slow to finish. */
    private static final Duration LOG_READ_TIMEOUT = Duration.ofSeconds(30);

    /**
     * A sidecar to start: its container, the port it listens on, what a failure calls it, and how long it may take.
     */
    record Start(String container, int port, String what, Duration timeout) {
    }

    /** How much of a log to read: the newest {@code tailLines} ({@link Integer#MAX_VALUE} for all), and how many chars. */
    record LogBound(int tailLines, int maxChars) {

        /** The whole log, bounded in characters. */
        static LogBound whole(int maxChars) {
            return new LogBound(Integer.MAX_VALUE, maxChars);
        }
    }

    private final DockerClient client;

    Sidecars(DockerClient client) {
        this.client = client;
    }

    /** A sidecar left by an earlier attempt of the same run, which would hold the name. Only ever this run's. */
    void removeStale(String name) {
        try {
            client.removeContainerCmd(name).withForce(true).exec();
        } catch (NotFoundException absent) {
            // the ordinary case
        }
    }

    /**
     * Starts a sidecar and waits until it listens on its port, read from {@code /proc/net/tcp} inside it, since neither
     * image has a probe of its own. Whether it exited first or never listened, the failure says its state and the last
     * lines of its own log ({@link SidecarStartFailure}), so a timeout explains itself as an exit does.
     */
    void startAndAwaitListening(Start sidecar) {
        client.startContainerCmd(sidecar.container()).exec();
        long deadline = System.nanoTime() + sidecar.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            var state = client.inspectContainerCmd(sidecar.container()).exec().getState();
            if (!Boolean.TRUE.equals(state.getRunning())) {
                throw startFailure(sidecar.container(), sidecar.what() + " exited before it listened");
            }
            if (listening(sidecar.container(), sidecar.port())) {
                return;
            }
            pause(sidecar.what());
        }
        throw startFailure(sidecar.container(), sidecar.what() + " did not listen within " + sidecar.timeout());
    }

    /** A container's log lines, bounded as {@code bound} says. */
    List<String> logOf(String container, LogBound bound) {
        StringBuilder log = new StringBuilder();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (log.length() < bound.maxChars()) {
                    log.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
            }
        };
        var command = client.logContainerCmd(container).withStdOut(true).withStdErr(true);
        command = bound.tailLines() == Integer.MAX_VALUE ? command.withTailAll() : command.withTail(bound.tailLines());
        try (ResultCallback.Adapter<Frame> stream = command.exec(callback)) {
            stream.awaitCompletion(LOG_READ_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException closing) {
            // The answer is what was read before the stream failed to close.
        }
        return Arrays.stream(log.toString().split("\\R")).map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static void pause(String what) {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for " + what, e);
        }
    }

    /** The failure, with the container's state read now and the bounded tail of its log. */
    private IllegalStateException startFailure(String container, String headline) {
        SidecarStartFailure.State state;
        try {
            state = SidecarStartFailure.State.of(client.inspectContainerCmd(container).exec().getState());
        } catch (RuntimeException uninspectable) {
            state = SidecarStartFailure.State.of(null);
        }
        List<String> log;
        try {
            log = logOf(container, new LogBound(SidecarStartFailure.TAIL_LINES, SidecarStartFailure.TAIL_CHARS));
        } catch (RuntimeException unreadable) {
            // The failure is still the start's; an unreadable log must not replace it with its own.
            log = List.of("(log unreadable: " + unreadable.getClass().getSimpleName() + ")");
        }
        return new IllegalStateException(SidecarStartFailure.detail(headline, state, log));
    }

    /** The port as /proc/net/tcp prints it (8888 is 22B8); either table, since a server may bind either family. */
    private boolean listening(String container, int port) {
        String probe = "grep -qi '" + String.format(":%04X ", port) + "' /proc/net/tcp /proc/net/tcp6 2>/dev/null";
        try {
            String exec = client.execCreateCmd(container).withCmd("sh", "-c", probe).exec().getId();
            try (ResultCallback.Adapter<Frame> run = client.execStartCmd(exec)
                    .exec(new ResultCallback.Adapter<Frame>())) {
                run.awaitCompletion(5, TimeUnit.SECONDS);
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
}

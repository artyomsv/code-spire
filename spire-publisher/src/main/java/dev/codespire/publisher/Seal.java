package dev.codespire.publisher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * {@code spire-seal}: measures that the pod's network is closed before the agent starts (M5 slice 1).
 *
 * <p>A round counts as sealed only when every canary is blocked and the control connects in that same round. The
 * control proves the probe can reach anything at all, so a broken probe never reads as a seal. {@code rounds} such
 * rounds in a row seal the pod; an open canary resets the count. "Connects" means a TCP connect that completes within
 * the timeout; a refusal or a timeout is blocked.
 */
final class Seal {

    /** One {@code host:port} to try. */
    record Target(String host, int port) {

        Target {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("target host is empty");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("target port out of range: " + port);
            }
        }

        static Target parse(String hostPort) {
            if (hostPort == null) {
                throw new IllegalArgumentException("target is missing");
            }
            int colon = hostPort.lastIndexOf(':');
            if (colon < 1 || colon == hostPort.length() - 1) {
                throw new IllegalArgumentException("target is not host:port: " + hostPort);
            }
            try {
                return new Target(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("target port is not a number: " + hostPort, e);
            }
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    record Settings(List<Target> canaries, Target control, int rounds, Duration timeout, Duration interval,
            Duration deadline) {

        Settings {
            if (canaries == null || canaries.isEmpty()) {
                throw new IllegalArgumentException("a seal needs at least one canary");
            }
            if (rounds < 1) {
                throw new IllegalArgumentException("rounds must be at least 1");
            }
            canaries = List.copyOf(canaries);
        }
    }

    sealed interface Result {

        record Sealed() implements Result {
        }

        record Open(Target target) implements Result {
        }

        record NoControl(Target target) implements Result {
        }
    }

    /** True only on a completed connect. */
    interface Connector {

        boolean connects(Target target, Duration timeout);
    }

    interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;
    }

    /** The real connector: a plain TCP connect with a timeout. */
    static final class SocketConnector implements Connector {

        @Override
        public boolean connects(Target target, Duration timeout) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(target.host(), target.port()), (int) timeout.toMillis());
                return true;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
    }

    private final Settings settings;

    private final Connector connector;

    private final Clock clock;

    private final Sleeper sleeper;

    Seal(Settings settings, Connector connector, Clock clock, Sleeper sleeper) {
        this.settings = settings;
        this.connector = connector;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    Result run() throws InterruptedException {
        Instant deadline = clock.instant().plus(settings.deadline());
        int sealedInARow = 0;
        Target open;
        boolean controlAnswered;
        while (true) {
            open = firstOpenCanary();
            controlAnswered = settings.control() == null || connector.connects(settings.control(), settings.timeout());
            if (open == null && controlAnswered) {
                sealedInARow++;
                if (sealedInARow >= settings.rounds()) {
                    return new Result.Sealed();
                }
            } else {
                sealedInARow = 0;
            }
            if (!clock.instant().isBefore(deadline)) {
                break;
            }
            sleeper.sleep(settings.interval());
        }
        if (open != null) {
            return new Result.Open(open);
        }
        if (!controlAnswered) {
            return new Result.NoControl(settings.control());
        }
        // Blocked and answered, but too few rounds fitted before the deadline: not proven.
        return new Result.Open(settings.canaries().get(0));
    }

    private Target firstOpenCanary() {
        Target first = null;
        for (Target canary : settings.canaries()) {
            if (connector.connects(canary, settings.timeout()) && first == null) {
                first = canary;
            }
        }
        return first;
    }
}

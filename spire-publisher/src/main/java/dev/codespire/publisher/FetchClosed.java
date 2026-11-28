package dev.codespire.publisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * {@code spire-fetch-closed}: closes the fetch proxy and proves it is closed (M5 slice 1).
 *
 * <p>It connects once to the close port (a refused connect is fine: the proxy is already closed), then needs
 * {@code refusals} failed connects to the target in a row. A connect that completes resets the count.
 */
final class FetchClosed {

    record Settings(Seal.Target close, Seal.Target target, int refusals, Duration timeout, Duration interval,
            Duration deadline) {

        Settings {
            if (close == null || target == null) {
                throw new IllegalArgumentException("--close and --target are required");
            }
            if (refusals < 1) {
                throw new IllegalArgumentException("refusals must be at least 1");
            }
        }
    }

    private final Settings settings;

    private final Seal.Connector connector;

    private final Clock clock;

    private final Seal.Sleeper sleeper;

    FetchClosed(Settings settings, Seal.Connector connector, Clock clock, Seal.Sleeper sleeper) {
        this.settings = settings;
        this.connector = connector;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** @return true when the target refused {@code refusals} times in a row before the deadline */
    boolean run() throws InterruptedException {
        Instant deadline = clock.instant().plus(settings.deadline());
        connector.connects(settings.close(), settings.timeout());
        int refused = 0;
        while (true) {
            if (connector.connects(settings.target(), settings.timeout())) {
                refused = 0;
            } else {
                refused++;
                if (refused >= settings.refusals()) {
                    return true;
                }
            }
            if (!clock.instant().isBefore(deadline)) {
                return false;
            }
            sleeper.sleep(settings.interval());
        }
    }
}

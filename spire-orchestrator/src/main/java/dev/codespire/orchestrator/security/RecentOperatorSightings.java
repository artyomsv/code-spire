package dev.codespire.orchestrator.security;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Which operators this process recorded a moment ago, so {@code /api/me} does not write the same row
 * again.
 *
 * <p>The session probe is not only asked once per dashboard load: the live socket's close handler
 * ({@code useLiveSocket}) asks it on every close before it reconnects, so a flapping socket would turn
 * {@code operator_seen} into a write stream. An identical sighting within {@link #WINDOW} is skipped;
 * a changed username or display name is written at once. Per process and in memory: a second
 * instance or a restart writes once more, which is harmless.
 */
@ApplicationScoped
public class RecentOperatorSightings {

    static final Duration WINDOW = Duration.ofSeconds(60);

    private record Sighting(String username, String displayName, Instant at) {
    }

    private final Map<String, Sighting> recent = new ConcurrentHashMap<>();

    Supplier<Instant> clock = Instant::now;

    /** Whether this sighting differs from the last one recorded, or that one is older than the window. */
    public boolean isNew(String subject, String username, String displayName) {
        Sighting last = recent.get(subject);
        return last == null
                || !last.username().equals(username)
                || !last.displayName().equals(displayName)
                || last.at().plus(WINDOW).isBefore(clock.get());
    }

    public void remember(String subject, String username, String displayName) {
        recent.put(subject, new Sighting(username, displayName, clock.get()));
    }
}

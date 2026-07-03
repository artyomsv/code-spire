package dev.codespire.orchestrator.view;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory timeline read model + WebSocket push. This is the Phase 0
 * dashboard's data source (FR-8 initial); real projections move to spire-ui's
 * Postgres tables at P1/P2.
 */
@ApplicationScoped
public class TimelineBroadcaster {

    private static final Logger LOG = Logger.getLogger(TimelineBroadcaster.class);
    private static final int MAX_ENTRIES = 500;

    private final List<TimelineEntry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @Inject
    OpenConnections connections;

    @Inject
    ObjectMapper mapper;

    public void record(String lane, String type, String reviewId, String detail) {
        TimelineEntry entry = new TimelineEntry(sequence.incrementAndGet(), lane, type, reviewId, detail, Instant.now());
        entries.add(entry);
        if (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        push(entry);
    }

    public List<TimelineEntry> snapshot() {
        return List.copyOf(entries);
    }

    private void push(TimelineEntry entry) {
        String json;
        try {
            json = mapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize timeline entry", e);
            return;
        }
        connections.stream()
                .filter(c -> c.handshakeRequest().path().endsWith("/ws/timeline"))
                .forEach(c -> c.sendText(json).subscribe().with(v -> {
                }, t -> LOG.debugf("WS push failed: %s", t.getMessage())));
    }
}

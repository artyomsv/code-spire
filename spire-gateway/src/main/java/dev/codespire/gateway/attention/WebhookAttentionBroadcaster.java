package dev.codespire.gateway.attention;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.attention.AttentionView;
import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pushes the gateway's attention conditions to connected clients.
 *
 * <p>Unlike the orchestrator's equivalent this needs no scheduled sweep, because both of its
 * conditions are write-driven: a registration starts refusing deliveries when a delivery is refused,
 * and stops when one verifies. Nothing here becomes true merely because time passed, so every
 * transition has a call site to hang a push on.
 */
@ApplicationScoped
public class WebhookAttentionBroadcaster {

    private static final Logger LOG = Logger.getLogger(WebhookAttentionBroadcaster.class);
    private static final String PATH = "/gw/ws/webhook-attention";

    @Inject
    WebhookAttentionRows rows;

    @Inject
    OpenConnections connections;

    @Inject
    ObjectMapper mapper;

    /** The payload last pushed, so a write that changed no condition pushes nothing. */
    private final AtomicReference<String> lastPushed = new AtomicReference<>();

    /** The current conditions as JSON, for a client that has just connected. */
    public String snapshot() throws JsonProcessingException {
        String json = mapper.writeValueAsString(rows.collect());
        lastPushed.set(json);
        return json;
    }

    /**
     * Re-evaluate and push if anything changed.
     *
     * <p>Never throws. This runs on the webhook hot path — every verified delivery calls it via
     * clear-on-success — and a diagnostic push must never be able to fail an inbound delivery the
     * provider will then retry.
     */
    public void refresh() {
        String json;
        try {
            List<AttentionView> current = rows.collect();
            json = mapper.writeValueAsString(current);
        } catch (RuntimeException | JsonProcessingException e) {
            LOG.debugf("Attention refresh failed to evaluate: %s", e.getMessage());
            return;
        }
        if (json.equals(lastPushed.getAndSet(json))) {
            return;
        }
        connections.stream()
                // Exact match, not endsWith: a suffix test makes a path RENAME look safe while
                // silently matching zero connections, so every push would be dropped with no error.
                .filter(c -> PATH.equals(c.handshakeRequest().path()))
                .forEach(c -> c.sendText(json).subscribe().with(v -> {
                }, t -> LOG.debugf("Attention push failed: %s", t.getMessage())));
    }
}

package dev.codespire.orchestrator.attention;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Pushes the orchestrator's attention conditions to connected clients.
 *
 * <p>Two things drive a push, because the conditions divide into two kinds:
 *
 * <ul>
 *   <li><b>Write-driven</b> — a credential check recorded, a review's status changed, a
 *       dead-lettered message replayed. Those call {@link #refresh()} directly, so the client sees
 *       the change immediately.
 *   <li><b>Time-driven</b> — {@code REVIEW_STUCK} becomes true because a threshold elapsed with no
 *       write at all. Nothing exists to hook, so a scheduled sweep re-evaluates.
 * </ul>
 *
 * <p>The sweep is periodic evaluation and is not pretending otherwise. What it avoids is periodic
 * evaluation <em>per client</em>: one evaluator serves every connection, and a push happens only when
 * the condition set actually changed, so a healthy system is silent no matter how many dashboards are
 * open. The client holds no timer and makes no request.
 */
@ApplicationScoped
public class AttentionBroadcaster {

    private static final Logger LOG = Logger.getLogger(AttentionBroadcaster.class);
    private static final String PATH = "/api/ws/attention";

    @Inject
    AttentionQueries queries;

    @Inject
    OpenConnections connections;

    @Inject
    ObjectMapper mapper;

    /**
     * The payload last pushed, so an unchanged sweep pushes nothing. Without this the sweep would be
     * client polling turned inside out — same traffic, same wakeups, just originated by the server.
     */
    private final AtomicReference<String> lastPushed = new AtomicReference<>();

    /** The current conditions as JSON, for a client that has just connected. */
    public String snapshot() throws JsonProcessingException {
        String json = mapper.writeValueAsString(queries.collect());
        lastPushed.set(json);
        return json;
    }

    /**
     * Re-evaluate and push if anything changed. Safe to call from any write path — evaluating is a
     * handful of indexed queries, and an unchanged result costs nothing but the evaluation.
     *
     * <p>Never throws: this is a diagnostic side effect of whatever the caller actually came to do,
     * and it must not fail that.
     */
    public void refresh() {
        String json;
        try {
            json = mapper.writeValueAsString(queries.collect());
        } catch (RuntimeException | JsonProcessingException e) {
            LOG.debugf("Attention refresh failed to evaluate: %s", e.getMessage());
            return;
        }
        if (json.equals(lastPushed.getAndSet(json))) {
            return;
        }
        push(json);
    }

    /**
     * Catches the transitions no write announces — a review crossing the stuck threshold, and a
     * failure ageing past nothing in particular. Deliberately frequent enough to feel live and cheap
     * enough to ignore: the queries are indexed and an unchanged result pushes nothing.
     */
    @Scheduled(every = "${spire.attention.sweep-seconds}s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        refresh();
    }

    private void push(String json) {
        connections.stream()
                .filter(c -> PATH.equals(c.handshakeRequest().path()))
                .forEach(c -> c.sendText(json).subscribe().with(v -> {
                }, t -> LOG.debugf("Attention push failed: %s", t.getMessage())));
    }
}

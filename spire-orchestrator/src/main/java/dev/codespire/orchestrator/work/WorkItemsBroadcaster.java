package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tells every work-items subscriber which items changed, without delaying them on a browser's response. A notice
 * carries ids only: the browser re-reads what it shows over REST, with the REST endpoint's own authorization.
 */
@ApplicationScoped
public class WorkItemsBroadcaster implements WorkItemChangeSink {

    /** Shared with the transport; the work package does not depend on the socket package. */
    public static final String ENDPOINT_ID = "work-items";
    static final String EVERY_ITEM = "*";

    private static final Logger LOG = Logger.getLogger(WorkItemsBroadcaster.class);

    @Inject
    OpenConnections connections;

    @Inject
    ObjectMapper mapper;

    @Override
    public void changed(Set<String> ids) {
        if (ids.isEmpty()) return;
        Collection<WebSocketConnection> subscribers = connections.findByEndpointId(ENDPOINT_ID);
        if (subscribers.isEmpty()) return;
        ObjectNode frame = mapper.createObjectNode();
        if (ids.contains(EVERY_ITEM)) frame.put("changed", EVERY_ITEM);
        else new TreeSet<>(ids).forEach(frame.putArray("changed")::add);
        String payload;
        try {
            payload = mapper.writeValueAsString(frame);
        } catch (JsonProcessingException failure) {
            LOG.warnf(failure, "a work-item change notice could not be serialised");
            return;
        }
        for (WebSocketConnection subscriber : subscribers) send(subscriber, payload);
    }

    @Override
    public void refreshAll() {
        changed(Set.of(EVERY_ITEM));
    }

    private void send(WebSocketConnection subscriber, String payload) {
        try {
            subscriber.sendText(payload).subscribe().with(ignored -> { }, failure ->
                    LOG.debugf("a work-items subscriber dropped a notice: %s", failure.getClass().getSimpleName()));
        } catch (RuntimeException failure) {
            // A synchronous send failure must not prevent delivery to the remaining subscribers.
            LOG.debugf(failure, "a work-items subscriber dropped a notice");
        }
    }
}

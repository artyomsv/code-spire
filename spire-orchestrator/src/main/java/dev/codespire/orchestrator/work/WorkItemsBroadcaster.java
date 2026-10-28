package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/** Tells every work-items subscriber which items changed, without delaying them on a browser's response. */
@ApplicationScoped
public class WorkItemsBroadcaster implements WorkItemChangeSink {

    /** Shared with the transport; the work package does not depend on the socket package. */
    public static final String ENDPOINT_ID = "work-items";

    @Inject
    OpenConnections connections;

    @Inject
    ObjectMapper mapper;

    @Override
    public void changed(Set<String> ids) {
    }

    @Override
    public void refreshAll() {
    }
}

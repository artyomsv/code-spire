package dev.codespire.orchestrator.view;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/timeline")
@RolesAllowed({"spire-viewer", "spire-admin"})
@Produces(MediaType.APPLICATION_JSON)
public class TimelineResource {

    @Inject
    TimelineBroadcaster timeline;

    @GET
    public List<TimelineEntry> timeline() {
        return timeline.snapshot();
    }
}

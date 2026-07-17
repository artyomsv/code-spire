package dev.codespire.orchestrator.dlq;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/** Dead-letter queue inspection + manual replay/discard (Step 2). */
@Path("/api/dlq")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DlqResource {

    @Inject
    DlqRepository repository;

    @Inject
    DlqReplayProducer replayProducer;

    @GET
    public List<DlqEntry> list(@QueryParam("pending") @DefaultValue("true") boolean pending) {
        return repository.list(pending);
    }

    @POST
    @Path("/{id}/replay")
    @Consumes(MediaType.WILDCARD) // no request body
    public DlqEntry replay(@PathParam("id") String id) {
        UUID uuid = uuid(id);
        DlqEntry entry = repository.get(uuid).orElseThrow(() -> new NotFoundException("No dead-letter entry " + id));
        if (!"pending".equals(entry.status())) {
            throw new WebApplicationException("Dead-letter entry " + id + " is not pending (status: "
                    + entry.status() + ")", Response.Status.CONFLICT);
        }
        replayProducer.publish(entry.originalTopic(), entry.kafkaKey(), entry.payload());
        repository.markReplayed(uuid);
        return repository.get(uuid).orElseThrow();
    }

    @DELETE
    @Path("/{id}")
    public Response discard(@PathParam("id") String id) {
        if (!repository.markDiscarded(uuid(id))) {
            throw new NotFoundException("No dead-letter entry " + id);
        }
        return Response.noContent().build();
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid dead-letter entry id");
        }
    }
}

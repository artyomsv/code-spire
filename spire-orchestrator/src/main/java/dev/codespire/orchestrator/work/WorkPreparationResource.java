package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.orchestrator.security.OidcSubjects;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.Map;

@Path("/api/work-items/{id}/preparation")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class WorkPreparationResource {
    @Inject WorkItemStore store;
    @Inject WorkSourceRegistry sources;
    @Inject WorkArtifacts artifacts;
    @Inject WorkItemTransitions transitions;
    @Inject SecurityIdentity identity;
    public record Input(long expectedRevision,WorkPreparation.Artifact specification,WorkPreparation.Artifact plan,
                        String baseBranch,String baseCommit,String harness,String model) {}

    @GET @Path("/reference")
    public WorkArtifacts.Reference reference(@PathParam("id") String id,@QueryParam("key") String key) {
        var item=store.load(id);if(item==null)throw new NotFoundException();
        try { return artifacts.resolve(sources.get(item.sourceId()).orElseThrow(NotFoundException::new),key); }
        catch(WorkArtifacts.ArtifactUnavailable unavailable) { throw new ServiceUnavailableException(unavailable.getMessage()); }
    }
    @POST public Response register(@PathParam("id") String id,Input input) {
        if(input==null || input.expectedRevision()<1)throw new BadRequestException("The current item revision is required");
        String actor=OidcSubjects.of(identity);if(actor.isBlank())throw new ForbiddenException("A verified operator identity is required");
        WorkPreparation preparation;
        try { preparation=new WorkPreparation(input.specification(),input.plan(),input.baseBranch(),input.baseCommit(),input.harness(),input.model(),actor); }
        catch(IllegalArgumentException | NullPointerException invalid) { throw new BadRequestException(invalid.getMessage()); }
        var result=transitions.prepare(id,input.expectedRevision(),preparation);
        return Response.status(result.status()).entity(Map.of("reason",result.reason())).build();
    }
}

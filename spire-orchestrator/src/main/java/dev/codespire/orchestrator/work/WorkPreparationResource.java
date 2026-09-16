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
    @Inject dev.codespire.orchestrator.factory.FactoryConfig factoryConfig;
    @Inject dev.codespire.orchestrator.repository.RepositoryAccounts accounts;
    @Inject dev.codespire.orchestrator.provider.ProviderClients clients;
    public record Input(long expectedRevision,WorkPreparation.Artifact specification,WorkPreparation.Artifact plan,
                        String baseBranch,String baseCommit,String harness,String model) {}
    /** The harness names this deployment can actually run: a key without an agent image refuses at dispatch. */
    public record Options(java.util.List<String> harnesses) {}
    public record Head(String branch,String commit) {}

    @GET @Path("/options")
    public Options options() {
        return new Options(factoryConfig.agentImage().keySet().stream().sorted().toList());
    }

    /**
     * The current head of one branch, read through the repository's own account.
     *
     * The approval binds a commit, so the operator had to paste 40 hex characters from a terminal and
     * could bind a tree nobody looked at. The forge answers the same question.
     */
    @GET @Path("/head")
    public Head head(@PathParam("id") String id,@QueryParam("branch") String branch) {
        if(branch==null || branch.isBlank())throw new BadRequestException("A base branch is required");
        var item=store.load(id);if(item==null)throw new NotFoundException();
        var observed=transitions.observe(item);
        if(observed.evidence().failure()!=null)throw new ServiceUnavailableException(observed.evidence().failure());
        try {
            var account=accounts.resolve(item.repositoryId(),dev.codespire.orchestrator.provider.ProviderRole.FACTORY)
                    .or(()->accounts.resolve(item.repositoryId(),dev.codespire.orchestrator.provider.ProviderRole.REVIEWER)).orElseThrow();
            var scm=clients.diffSource(account);
            return new Head(branch.trim(),scm.fetchBranchHead(observed.source().repository(),branch.trim()));
        } catch(RuntimeException unavailable) {
            throw new ServiceUnavailableException("The branch head could not be read through this repository's account");
        }
    }

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
        // The reason is the contract other code keys on; the detail says which rule refused, so the
        // operator is told what to change instead of being handed one word for three plan rules.
        Map<String,String> body=result.detail()==null?Map.of("reason",result.reason())
                :Map.of("reason",result.reason(),"detail",result.detail());
        return Response.status(result.status()).entity(body).build();
    }
}

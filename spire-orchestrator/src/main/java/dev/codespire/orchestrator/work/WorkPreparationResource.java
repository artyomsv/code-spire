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
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(WorkPreparationResource.class);
    @Inject WorkItemStore store;
    @Inject WorkSourceRegistry sources;
    @Inject WorkArtifacts artifacts;
    @Inject WorkItemTransitions transitions;
    @Inject SecurityIdentity identity;
    @Inject dev.codespire.orchestrator.factory.FactoryConfig factoryConfig;
    @Inject dev.codespire.orchestrator.repository.RepositoryAccounts accounts;
    @Inject dev.codespire.orchestrator.provider.ProviderClients clients;
    @Inject WorkPreparationSweep sweep;
    public record Input(long expectedRevision,WorkPreparation.Artifact specification,WorkPreparation.Artifact plan,
                        String baseBranch,String baseCommit,String harness,String model) {}
    /**
     * The harnesses this deployment can run, each with the token types it can report: a key without an
     * agent image refuses at dispatch, and so does a model with no price for a type the harness reports.
     */
    public record Options(java.util.List<String> harnesses, Map<String,java.util.List<String>> reportedTypes) {}
    public record Head(String branch,String commit) {}
    /**
     * What an approver is asked to approve, read from the tracker now: the specification text and the
     * one plan step, or the rule that makes them unusable. Bodies are returned and never stored.
     */
    /**
     * @param binding the preparation binding these texts were read against: the same value a plan
     *     decision stores as its artifact, so a screen can tell whether the texts are what it approves
     */
    public record Evidence(String reason,String detail,String specification,String instruction,String binding) {}

    @GET @Path("/evidence")
    public Evidence evidence(@PathParam("id") String id) {
        var item=store.load(id);if(item==null)throw new NotFoundException();
        if(item.preparation()==null)throw refused(409,"preparation_missing");
        var source=sources.get(item.sourceId()).orElseThrow(NotFoundException::new);
        var observed=artifacts.observe(source,id,item.preparation());
        return new Evidence(observed.failure(),observed.detail(),observed.specification(),observed.instruction(),item.preparation().binding());
    }

    @GET @Path("/options")
    public Options options() {
        java.util.List<String> harnesses=factoryConfig.agentImage().keySet().stream().sorted().toList();
        return new Options(harnesses,harnesses.stream().collect(java.util.stream.Collectors.toMap(harness->harness,
                harness->dev.codespire.contract.llm.HarnessTokenReport.reportedBy(harness).stream().map(Enum::name).sorted().toList())));
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
        if(observed.evidence().failure()!=null)throw refused(503,observed.evidence().failure());
        // A missing binding is configuration, not an outage: no retry fixes it, so it is not a 503.
        var account=accounts.resolve(item.repositoryId(),dev.codespire.orchestrator.provider.ProviderRole.FACTORY)
                .or(()->accounts.resolve(item.repositoryId(),dev.codespire.orchestrator.provider.ProviderRole.REVIEWER))
                .orElseThrow(()->refused(409,"repository_account_missing"));
        var scm=clients.diffSource(account);
        String name=branch.trim();
        // Only the forge call is caught. A fault in this service before it stays a 500, rather than
        // reading as a forge that could not answer.
        try {
            return new Head(name,scm.fetchBranchHead(observed.source().repository(),name));
        } catch(UnsupportedOperationException unsupported) {
            throw refused(501,"branch_head_unsupported");
        } catch(RuntimeException refusedByForge) {
            // A wrong branch name and a rejected credential both arrive here as a forge refusal whose
            // type differs per adapter, so the answer says "not confirmed" and names both causes.
            // Only the failure type is logged. Adapter messages carry request paths and response-body
            // snippets from the forge, and the branch is operator input, so neither goes to the log.
            LOG.warnf("work item %s: the forge did not confirm the requested branch (%s)",id,refusedByForge.getClass().getSimpleName());
            throw refused(502,"branch_head_unconfirmed");
        }
    }

    /** The same {reason} body the registration refusals use, so the screen can say what to change. */
    private static WebApplicationException refused(int status,String reason) {
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("reason",reason)).build());
    }

    @GET @Path("/reference")
    public WorkArtifacts.Reference reference(@PathParam("id") String id,@QueryParam("key") String key) {
        var item=store.load(id);if(item==null)throw new NotFoundException();
        try { return artifacts.resolve(sources.get(item.sourceId()).orElseThrow(NotFoundException::new),key); }
        catch(WorkArtifacts.ArtifactUnavailable unavailable) { throw new ServiceUnavailableException(unavailable.getMessage()); }
    }
    /**
     * Compose this item's task again from its ticket.
     *
     * <p>Offered because the specification is a SNAPSHOT of the ticket: an edit after preparation does
     * not change what was approved, and only a person can say whether the edit was meant for this task.
     * It supersedes an open decision, because the texts a new decision binds are new.
     */
    @POST @Path("/compose")
    public Response compose(@PathParam("id") String id,@QueryParam("expectedRevision") long expectedRevision) {
        var item=store.load(id);if(item==null)throw new NotFoundException();
        String actor=OidcSubjects.of(identity);
        if(actor.isBlank())throw new ForbiddenException("A verified operator identity is required");
        // The revision the operator was LOOKING at. Without it a stale tab could replace a decision that
        // opened after the page was rendered, and the history would attribute it to the system.
        if(expectedRevision>0 && store.history(id).size()!=expectedRevision)
            return Response.status(409).entity(Map.of("reason","work_item_changed")).build();
        var result=sweep.prepareAgain(id,actor);
        return Response.status(result.prepared()?200:409).entity(Map.of("reason",result.reason())).build();
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
        return Response.status(result.status()).entity(result.body()).build();
    }
}

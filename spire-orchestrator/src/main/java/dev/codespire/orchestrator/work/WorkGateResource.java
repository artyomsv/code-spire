package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkGate;
import dev.codespire.orchestrator.security.OidcSubjects;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Path("/api/approvals")
@RolesAllowed({"spire-viewer","spire-admin"})
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class WorkGateResource {
    @Inject WorkItemTransitions transitions;
    @Inject DataSource dataSource;
    @Inject SecurityIdentity identity;
    @Inject WorkGateChannels channels;
    @Inject WorkItemStore store;
    public record View(String workItemId,String issueKey,WorkGate gate,boolean prReviewAvailable,String prReviewDetail,String trackerCommand) {}
    public record Answer(long expectedVersion,String idempotencyKey,boolean approve,String note) {}

    @GET public List<View> list(@QueryParam("history") @DefaultValue("false") boolean history) {
        String sql=history?"SELECT g.id,g.work_item_id,w.issue_key FROM work_item_gate g JOIN work_item w ON w.id=g.work_item_id WHERE g.state<>'OPEN' ORDER BY g.opened_at DESC,g.id LIMIT 100"
                :"SELECT g.id,g.work_item_id,w.issue_key FROM work_item_gate g JOIN work_item w ON w.id=g.work_item_id WHERE g.state='OPEN' ORDER BY g.expires_at,g.id LIMIT 100";
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement(sql);ResultSet rs=ps.executeQuery()) {
            List<View> rows=new ArrayList<>();while(rs.next()) {
                var item=store.load(rs.getString(2));var gate=transitions.gateFromHistory(rs.getString(2),rs.getObject(1,UUID.class));
                boolean available="land".equals(gate.phase()) && channels.approvalAvailable(item.repositoryId());
                rows.add(new View(rs.getString(2),rs.getString(3),gate,available,
                        available?"A current human approval of the linked PR head may answer this land gate.":"PR review answers are unavailable for this forge or phase; use dashboard or tracker.",
                        "/approve "+gate.id()+" "+gate.generation()+" "+(gate.artifact()==null?"-":gate.artifact())));
            }return List.copyOf(rows);
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    @POST @Path("/{id}/answer") @RolesAllowed("spire-admin")
    public Response answer(@PathParam("id") UUID id,Answer input) {
        if(input==null || input.expectedVersion()<1 || input.idempotencyKey()==null || input.idempotencyKey().isBlank())throw new BadRequestException("A version and answer identity are required");
        String subject=OidcSubjects.of(identity);
        if(subject.isBlank())throw new ForbiddenException("A verified operator identity is required");
        var result=transitions.answer(id,input.expectedVersion(),input.idempotencyKey(),input.approve(),input.note(),subject);
        return Response.status(result.status()).entity(Map.of("reason",result.reason())).build();
    }
}

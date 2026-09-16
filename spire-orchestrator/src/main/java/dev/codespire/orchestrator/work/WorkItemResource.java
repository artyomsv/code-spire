package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.work.*;
import dev.codespire.worksource.*;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

@Path("/api/work-items")
@RolesAllowed({"spire-viewer", "spire-admin"})
@Produces(MediaType.APPLICATION_JSON)
public class WorkItemResource {
    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkSourceRegistry sources;
    @Inject WorkItemTransitions transitions;
    @Inject WorkItemControl control;
    @Inject io.quarkus.security.identity.SecurityIdentity identity;
    public record Resume(long expectedRevision,boolean readmit,String note) {
        public Resume(long expectedRevision,boolean readmit) {this(expectedRevision,readmit,null);}
    }
    @POST @Path("/{id}/resume") @RolesAllowed("spire-admin")
    @Consumes(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response resume(@PathParam("id") String id,Resume input) {
        if(input==null || input.expectedRevision()<1)throw new BadRequestException("The current work-item revision is required");
        String subject=dev.codespire.orchestrator.security.OidcSubjects.of(identity);
        if(subject.isBlank())throw new ForbiddenException("A verified operator identity is required");
        var item=store.load(id);
        var result=item!=null && "suspended".equals(item.workflowStatus())
                ?control.resume(id,input.expectedRevision(),subject,input.note())
                :transitions.resume(id,input.expectedRevision(),input.readmit());
        return jakarta.ws.rs.core.Response.status(result.status()).entity(result.body()).build();
    }
    public record Profile(UUID id, String name, long version) {}
    /**
     * @param generation the attempt this entry belongs to. A re-admitted item keeps its history, so a
     *     decision from an earlier attempt must not read as approval of the current one.
     */
    public record Milestone(long sequence, String type, String reason, Instant occurredAt,String phase,String workflowStatus,
                            UUID attemptId,UUID gateId,String gateState,String resolver,long generation) {}
    public record Build(UUID attemptId,String state,String runId,String reason,long generation) {}
    public record View(String id, UUID sourceId, UUID repositoryId, String repository, String issueKey, String trackerUrl,
                       long generation, String phase, String workflowStatus, String reason, Profile profile, long revision,
                       Instant updatedAt, List<WorkPolicy.IgnoredLabel> ignoredLabels, List<Milestone> events,
                       Map<WorkPolicy.Phase,String> effectiveModes, Map<WorkPolicy.Phase,String> admittedModes,
                       String policyReason, Profile ceiling, List<WorkPolicy.AppliedLabel> appliedLabels,
                       WorkPolicyLimits effectiveLimits,WorkPolicyLimits admittedLimits,WorkGate gate,WorkProgress progress,
                       WorkPreparation preparation,List<Build> builds,WorkControl control,
                       /**
                        * Who the ids on this item's labels are, as the tracker last observed them.
                        * The label carries the stable provider id, which is the only thing safe to
                        * store; a screen that shows it alone tells the operator "actor 900123".
                        * Resolved at read time from the source's allowed people, so a renamed handle
                        * is right on the next read and no observed name is ever persisted here.
                        */
                       List<WorkSourceRegistry.Person> people) {}
    /**
     * @param counts items per workflow status across every page, so a filter can say how many rows it
     *     holds before it is chosen, and a band can say how many items need a person
     */
    public record Page(List<View> items, long total, int offset, int limit, Map<String,Long> counts) {}

    /**
     * Only the people whose ids are already on this item's labels. The source's allowlist is
     * authorization configuration and the item view reaches viewers, so it is not repeated per item;
     * an id the view already carries gains only the handle the tracker observed for it.
     */
    static List<WorkSourceRegistry.Person> labelAppliers(List<WorkSourceRegistry.Person> allowed,List<WorkPolicy.AppliedLabel> applied) {
        Set<String> referenced=applied.stream().map(WorkPolicy.AppliedLabel::actorId).collect(java.util.stream.Collectors.toSet());
        return allowed.stream().filter(person->referenced.contains(person.providerUserId())).toList();
    }
    public record Tracker(String title, String body, String trackerStatus) {}

    @GET
    public Page list(@QueryParam("offset") @DefaultValue("0") int offset, @QueryParam("limit") @DefaultValue("50") int limit,
                     @QueryParam("status") String status) {
        if (offset < 0 || limit < 1 || limit > 100) throw new BadRequestException("Use a nonnegative offset and limit 1–100");
        // A screen groups several statuses under one filter ("needs you" is approval, input and
        // suspension), so the filter accepts a comma-separated set rather than one status.
        String[] statuses = status == null || status.isBlank() ? null
                : Arrays.stream(status.split(",")).map(String::trim).filter(value -> !value.isEmpty()).distinct().toArray(String[]::new);
        if (statuses != null && (statuses.length == 0 || statuses.length > 10)) throw new BadRequestException("Name between one and ten workflow statuses");
        try (Connection c = dataSource.getConnection()) {
            long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM work_item WHERE (?::text[] IS NULL OR workflow_status = ANY(?::text[]))")) {
                var array = statuses == null ? null : c.createArrayOf("text", statuses);
                ps.setArray(1,array);ps.setArray(2,array);try(ResultSet rs=ps.executeQuery()){rs.next();total=rs.getLong(1);}
            }
            Map<String,Long> counts = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT workflow_status,count(*) FROM work_item GROUP BY workflow_status");ResultSet rs=ps.executeQuery()) {
                while (rs.next()) counts.put(rs.getString(1), rs.getLong(2));
            }
            List<View> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM work_item WHERE (?::text[] IS NULL OR workflow_status = ANY(?::text[])) ORDER BY updated_at DESC,id LIMIT ? OFFSET ?")) {
                var array = statuses == null ? null : c.createArrayOf("text", statuses);
                ps.setArray(1,array);ps.setArray(2,array);ps.setInt(3, limit); ps.setInt(4, offset);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) items.add(get(rs.getString(1))); }
            }
            return new Page(List.copyOf(items), total, offset, limit, Map.copyOf(counts));
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    @GET @Path("/{id}")
    public View get(@PathParam("id") String id) {
        List<EventEnvelope> events;
        try { events = store.history(id); }
        catch (IllegalArgumentException invalid) { throw new NotFoundException(); }
        if (events.isEmpty()) throw new NotFoundException();
        WorkItemEvent item = (WorkItemEvent) events.getLast().payload();
        WorkSourceRegistry.Source source = sources.get(item.sourceId()).orElseThrow(NotFoundException::new);
        WorkPolicy.Profile selected = item.policy().selected();
        return new View(id, item.sourceId(), item.repositoryId(), source.repository().full(), item.issue().issueKey(), item.issue().link().toString(),
                item.generation(), item.phase(), item.workflowStatus(), item.reason(),
                selected == null ? null : new Profile(selected.id(), selected.name(), selected.version()), events.size(), events.getLast().occurredAt(),
                item.policy().ignored(), events.stream().map(event -> {
                    WorkItemEvent state = (WorkItemEvent) event.payload();
                    boolean clamp = "POLICY_CLAMPED".equals(state.milestone());
                    return new Milestone(event.sequence(), "POLICY_OBSERVED".equals(state.milestone()) ? event.eventType() : state.milestone(),
                            clamp ? state.policy().reason() : state.reason(), event.occurredAt(),state.phase(),state.workflowStatus(),
                            state.progress().attemptId(),state.gate()==null?null:state.gate().id(),state.gate()==null?null:state.gate().state(),state.gate()==null?null:state.gate().resolver(),state.generation());
                }).toList(),
                item.policy().effective(), item.admittedModes(), item.policy().reason(), item.policy().ceiling() == null ? null
                        : new Profile(item.policy().ceiling().id(), item.policy().ceiling().name(), item.policy().ceiling().version()), item.policy().applied(),
                item.policy().limits(),item.admittedLimits(),item.gate(),item.progress(),item.preparation(),builds(id),item.control(),labelAppliers(source.allowedPeople(),item.policy().applied()));
    }

    private List<Build> builds(String id) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT attempt_id,state,run_id,reason,generation FROM work_run_effect WHERE work_item_id=? ORDER BY created_at,attempt_id")) {
            ps.setString(1,id);try(ResultSet rs=ps.executeQuery()) {
                List<Build> rows=new ArrayList<>();while(rs.next())rows.add(new Build(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)));return List.copyOf(rows);
            }
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }

    @GET @Path("/{id}/tracker")
    public Tracker tracker(@PathParam("id") String id) {
        get(id);
        WorkItemEvent item = store.load(id);
        WorkSourceRegistry.Source source = sources.get(item.sourceId()).orElseThrow(NotFoundException::new);
        FutureTask<WorkSource.Fetch> task = new FutureTask<>(() -> sources.client(source).fetch(item.issue()));
        Thread.ofVirtual().start(task);
        try {
            WorkSource.Fetch result = task.get(20, TimeUnit.SECONDS);
            if (result instanceof WorkSource.Fetch.Found found)
                return new Tracker(found.ticket().title(), found.ticket().body(), found.ticket().trackerStatus());
        } catch (Exception failure) {
            task.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        throw new ServiceUnavailableException("Tracker unavailable; durable workflow is still available");
    }
}

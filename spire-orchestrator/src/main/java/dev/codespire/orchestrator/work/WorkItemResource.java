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
    public record Profile(UUID id, String name, long version) {}
    public record Milestone(long sequence, String type, String reason, Instant occurredAt) {}
    public record View(String id, UUID sourceId, UUID repositoryId, String repository, String issueKey, String trackerUrl,
                       long generation, String phase, String workflowStatus, String reason, Profile profile, long revision,
                       Instant updatedAt, List<WorkPolicy.IgnoredLabel> ignoredLabels, List<Milestone> events,
                       Map<WorkPolicy.Phase,String> effectiveModes, Map<WorkPolicy.Phase,String> admittedModes,
                       String policyReason, Profile ceiling, List<WorkPolicy.AppliedLabel> appliedLabels) {}
    public record Page(List<View> items, long total, int offset, int limit) {}
    public record Tracker(String title, String body, String trackerStatus) {}

    @GET
    public Page list(@QueryParam("offset") @DefaultValue("0") int offset, @QueryParam("limit") @DefaultValue("50") int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new BadRequestException("Use a nonnegative offset and limit 1–100");
        try (Connection c = dataSource.getConnection()) {
            long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM work_item"); ResultSet rs = ps.executeQuery()) { rs.next(); total = rs.getLong(1); }
            List<View> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM work_item ORDER BY updated_at DESC,id LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit); ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) items.add(get(rs.getString(1))); }
            }
            return new Page(List.copyOf(items), total, offset, limit);
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
                item.policy().ignored(), events.stream().map(event -> new Milestone(event.sequence(), event.eventType(),
                        ((WorkItemEvent) event.payload()).reason(), event.occurredAt())).toList(),
                item.policy().effective(), item.admittedModes(), item.policy().reason(), item.policy().ceiling() == null ? null
                        : new Profile(item.policy().ceiling().id(), item.policy().ceiling().name(), item.policy().ceiling().version()), item.policy().applied());
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

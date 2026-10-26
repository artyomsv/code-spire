package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.work.StepSummary;
import dev.codespire.contract.work.WorkItemEvent;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The build steps of an item's current generation for the dashboard (M4 slice 8, §6.8): each step's newest summary,
 * decrypted, or {@code summary_missing} when its run handed none over, so the screen can say the step recorded no
 * decisions. Admin only, as the draft is: the agent wrote the text and it may quote the repository.
 */
@Path("/api/work-items/{id}/steps")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
public class WorkStepsResource {
    @Inject WorkItemStore store;
    @Inject WorkStepSummaries summaries;

    /**
     * One plan step as the Build step shows it: its title, state ({@code waiting}, {@code building}, {@code verifying},
     * {@code passed}, {@code empty}, {@code failed}), the commit it left, and its newest summary.
     */
    public record Row(int index, String title, String state, String head, String summaryState, StepSummary summary) {}

    public record View(List<WorkStepSummaries.Step> steps, List<Row> rows) {}

    @GET
    public View read(@PathParam("id") String id) {
        List<EventEnvelope> history = store.history(id);
        if (history.isEmpty()) throw new NotFoundException();
        WorkItemEvent item = (WorkItemEvent) history.getLast().payload();
        return new View(summaries.list(id, item.generation()), List.of());
    }
}

package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.work.StepSummary;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.contract.work.WorkSteps;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;

/**
 * The build steps of an item's current generation for the dashboard (M4 slice 8, §6.8): each step's newest summary,
 * decrypted, or {@code summary_missing} when its run handed none over, so the screen can say the step recorded no
 * decisions. Admin only, as the draft is: the agent wrote the text and it may quote the repository.
 *
 * <p>{@code rows} is the Build step's view of a plan built step by step (M4 slice 3, §6.10): one row per plan step, with
 * its title from the registered plan, its state from the cursor, the commit it left and its summary. An item without a
 * plan cursor has none.
 */
@Path("/api/work-items/{id}/steps")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
public class WorkStepsResource {
    @Inject WorkItemStore store;
    @Inject WorkStepSummaries summaries;
    @Inject WorkItemArtifacts artifacts;

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
        List<WorkStepSummaries.Step> written = summaries.list(id, item.generation());
        return new View(written, rows(item, written));
    }

    private List<Row> rows(WorkItemEvent item, List<WorkStepSummaries.Step> written) {
        WorkSteps steps = item.steps();
        if (steps == null) return List.of();
        List<WorkArtifacts.Step> titles = titles(item);
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < steps.total(); index++) {
            String title = index < titles.size() ? titles.get(index).title() : "Step " + (index + 1);
            int position = index;
            WorkStepSummaries.Step summary = written.stream().filter(step -> step.stepIndex() == position).findFirst().orElse(null);
            Standing standing = standing(item, index);
            rows.add(new Row(index, title, standing.state(), standing.head(), summary == null ? null : summary.state(),
                    summary == null ? null : summary.summary()));
        }
        return rows;
    }

    private record Standing(String state, String head) {}

    /** The step's state and the commit it left: from the cursor once completed, from the item while it is current. */
    private static Standing standing(WorkItemEvent item, int index) {
        WorkSteps steps = item.steps();
        if (index < steps.index()) {
            WorkSteps.StepResult done = steps.completed().get(index);
            return new Standing(done.empty() ? "empty" : "passed", done.execution().head());
        }
        if (index > steps.index()) return new Standing("waiting", null);
        WorkExecution execution = item.progress().execution();
        boolean verify = "verify".equals(item.phase());
        String head = verify && execution != null ? execution.head() : null;
        if ("active".equals(item.workflowStatus())) return new Standing(verify ? "verifying" : "building", head);
        if (verify && "waiting_approval".equals(item.workflowStatus()) || "failed".equals(item.workflowStatus()))
            return new Standing("failed", head);
        return new Standing("waiting", head);
    }

    /** The registered plan's steps in order; a plan with no stored text names none, and rows fall back to numbers. */
    private List<WorkArtifacts.Step> titles(WorkItemEvent item) {
        WorkPreparation preparation = item.preparation();
        if (preparation == null || preparation.plan().storedId() == null) return List.of();
        return artifacts.read(item.workItemId(), preparation.plan().storedId())
                .map(text -> PlanRules.accept(text, new PlanRules.Bound(preparation.bindingVersion(), preparation.specification().sha256(), Long.MAX_VALUE)).steps())
                .orElse(List.of());
    }
}

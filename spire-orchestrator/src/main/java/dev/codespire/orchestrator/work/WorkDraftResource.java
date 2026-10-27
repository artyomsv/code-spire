package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.work.WorkDraft;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPreparation;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A generated item's draft for the dashboard (M4 slice 3, §6.10): the written specification and plan, decrypted, each
 * with its digest and the state of the ticket comment that carries it, the plan's steps in order, and the revisions used.
 * Admin only, as the preparation evidence is: the text may quote the repository.
 */
@Path("/api/work-items/{id}/draft")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
public class WorkDraftResource {
    @Inject WorkItemStore store;
    @Inject WorkItemArtifacts artifacts;
    @Inject WorkSourceEffects effects;

    /** The comment's outbox state, and what the tracker returned for it once sent; null until then. */
    public record Comment(String state, String ref) {}
    public record Written(String text, String sha256, Comment comment) {}
    public record Revisions(int specification, int plan) {}
    /** The plan as written, and its steps in order. */
    public record Plan(String text, String sha256, Comment comment, List<WorkArtifacts.Step> steps) {}
    /** @param ticketLink the ticket, which the screen links while the comment has no address of its own */
    public record View(Written specification, Plan plan, Revisions revisions, String ticketLink) {}

    @GET
    public View read(@PathParam("id") String id) {
        List<EventEnvelope> history = store.history(id);
        if (history.isEmpty()) throw new NotFoundException();
        WorkItemEvent item = (WorkItemEvent) history.getLast().payload();
        WorkDraft draft = item.draft();
        if (draft == null) throw new WebApplicationException(Response.status(404).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("reason", "draft_missing")).build());
        return new View(written(history, item, draft.specification()), plan(history, item, draft),
                new Revisions(draft.specificationRevisions(), draft.planRevisions()), item.issue().link().toString());
    }

    private Written written(List<EventEnvelope> history, WorkItemEvent item, WorkPreparation.Artifact artifact) {
        if (artifact == null) return null;
        String text = artifacts.read(item.workItemId(), artifact.storedId())
                .orElseThrow(() -> new IllegalStateException("Work item " + item.workItemId() + " names an artifact it does not store"));
        return new Written(text, artifact.sha256(), comment(history, artifact));
    }

    private Plan plan(List<EventEnvelope> history, WorkItemEvent item, WorkDraft draft) {
        Written written = written(history, item, draft.plan());
        if (written == null) return null;
        PlanRules.Checked checked = PlanRules.accept(written.text(),
                new PlanRules.Bound(WorkPreparation.STEPS_BINDING, draft.specification().sha256(), Long.MAX_VALUE));
        return new Plan(written.text(), written.sha256(), written.comment(), checked.steps());
    }

    /**
     * The comment of the attempt that wrote this artifact; null when its tracker takes no comments. The plan's write
     * carries the specification too, so the artifact is matched to the phase that wrote it.
     */
    private Comment comment(List<EventEnvelope> history, WorkPreparation.Artifact artifact) {
        UUID attempt = WorkItemTransitions.facts(history).reversed().stream()
                .filter(event -> "DRAFT_ARTIFACT_WRITTEN".equals(event.milestone()) && event.draft() != null
                        && artifact.equals(event.draft().artifactFor(event.phase())))
                .map(event -> event.progress().attemptId()).findFirst().orElse(null);
        if (attempt == null) return null;
        WorkSourceEffects.Status status = effects.status(WorkSourceEffects.draftId(attempt));
        return status == null ? null : new Comment(status.state(), status.remoteId());
    }
}

package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.worksource.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.*;

/** Bounded transient reads through the selected tracker account; no artifact body is persisted here. */
@ApplicationScoped
public class WorkArtifacts {
    @Inject WorkSourceRegistry sources;
    @Inject ObjectMapper mapper;
    public record Reference(WorkPreparation.Artifact artifact,String title) {}
    /**
     * A refusal carries the coarse {@code failure} other code keys on, plus the {@code detail} that
     * names which rule refused it. Three separate plan rules shared one reason, so an operator was
     * told "single_step_plan_required" for invalid JSON, for a stale specification digest and for a
     * second step alike, and had to guess which one they had hit.
     */
    public record Evidence(String failure,String specification,String instruction,String detail) {
        public static Evidence absent() { return new Evidence(null,null,null,null); }
        static Evidence refused(String failure,String detail) { return new Evidence(failure,null,null,detail); }
        static Evidence supplied(String specification,String instruction) { return new Evidence(null,specification,instruction,null); }
    }
    public Reference resolve(WorkSourceRegistry.Source source,String key) {
        return bounded(()-> {
            WorkSource client=sources.client(source);
            WorkIssueLocation location=client.resolve(key);
            WorkTicket ticket=fetch(client,source,location);
            return new Reference(new WorkPreparation.Artifact(ticket.location(),WorkPreparation.digest(ticket.body())),ticket.title());
        });
    }
    public Evidence observe(WorkSourceRegistry.Source source,WorkPreparation preparation) {
        if(preparation==null)return Evidence.absent();
        try {
            return bounded(()-> {
                WorkSource client=sources.client(source);
                WorkTicket specification=fetch(client,source,preparation.specification().location());
                WorkTicket plan=fetch(client,source,preparation.plan().location());
                if(!preparation.specification().sha256().equals(WorkPreparation.digest(specification.body())))
                    return Evidence.refused("artifacts_changed","specification_changed");
                if(!preparation.plan().sha256().equals(WorkPreparation.digest(plan.body())))
                    return Evidence.refused("artifacts_changed","plan_changed");
                com.fasterxml.jackson.databind.JsonNode root;
                try { root=mapper.readTree(plan.body()); }
                catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { return Evidence.refused("single_step_plan_required","plan_not_json"); }
                if(root==null || !root.isObject() || !root.path("schemaVersion").isInt() || root.path("schemaVersion").asInt()!=1)
                    return Evidence.refused("single_step_plan_required","plan_schema_version");
                if(!preparation.specification().sha256().equals(root.path("specificationSha256").asText()))
                    return Evidence.refused("single_step_plan_required","plan_specification_mismatch");
                if(!root.path("steps").isArray() || root.path("steps").size()!=1)
                    return Evidence.refused("single_step_plan_required","plan_step_count");
                var step=root.path("steps").get(0);
                if(!step.path("id").isTextual() || step.path("id").asText().isBlank()
                        || !step.path("instruction").isTextual() || step.path("instruction").asText().isBlank())
                    return Evidence.refused("single_step_plan_required","plan_step_fields");
                return Evidence.supplied(specification.body(),step.path("instruction").asText());
            });
        } catch(ArtifactUnavailable unavailable) { return Evidence.refused("artifacts_unavailable",null); }
    }
    private WorkTicket fetch(WorkSource client,WorkSourceRegistry.Source source,WorkIssueLocation location) {
        if(location==null || location.ref()==null || location.ref().type()!=source.type()
                || !source.origin().equals(location.ref().origin()) || !source.projectId().equals(location.ref().projectId()))
            throw new ArtifactUnavailable();
        if(!(client.fetch(location) instanceof WorkSource.Fetch.Found found))throw new ArtifactUnavailable();
        WorkTicket ticket=found.ticket();
        if(!ticket.location().ref().equals(location.ref()) || ticket.body()==null || ticket.body().isBlank() || ticket.body().length()>48*1024)
            throw new ArtifactUnavailable();
        return ticket;
    }
    private static <T> T bounded(Callable<T> operation) {
        FutureTask<T> task=new FutureTask<>(operation);Thread.ofVirtual().start(task);
        try { return task.get(20,TimeUnit.SECONDS); }
        catch(Exception failure) {
            task.cancel(true);
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new ArtifactUnavailable();
        }
    }
    public static final class ArtifactUnavailable extends RuntimeException {
        ArtifactUnavailable() { super("Tracker artifacts are unavailable; check the references and selected source account"); }
    }
}

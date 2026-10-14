package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.work.*;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Observe the existing reviewer at the delivered head. This does not supply a verifier or a merge capability. */
@ApplicationScoped
public class WorkReview {
    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject ReviewProjection reviews;
    @Inject EncryptionService encryption;
    @Inject ObjectMapper mapper;
    @Inject dev.codespire.orchestrator.factory.MachineAccounts accounts;
    @Inject dev.codespire.orchestrator.provider.ProviderClients clients;
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(WorkReview.class);
    /** Pull requests this process has marked ready, so a pending review does not read the forge on every pass. */
    private final Set<String> markedReady=java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * When a definite refusal (a closed PR, an unsupported forge) was last met, per attempt. Asked again only
     * after {@link #REFUSAL_RETRY}: a closed draft was otherwise re-read and re-mutated on every 5-second pass,
     * spending the factory account's API quota (review of PR #184).
     */
    private final java.util.Map<String,java.time.Instant> refusedAt=new java.util.concurrent.ConcurrentHashMap<>();
    static final java.time.Duration REFUSAL_RETRY=java.time.Duration.ofMinutes(10);
    record Observation(String review,String workspace,String slug,String reason) {}

    @Scheduled(every="${spire.work-delivery-interval:5s}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void drain() {
        List<String> ids=new ArrayList<>();
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT id FROM work_item WHERE phase='review' AND workflow_status='active' ORDER BY updated_at LIMIT 20");ResultSet rs=ps.executeQuery()) {
            while(rs.next())ids.add(rs.getString(1));
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        for(String id:ids)advance(id);
    }
    public void advance(String id) {
        var item=store.load(id);if(item==null || !"review".equals(item.phase()) || !"active".equals(item.workflowStatus()))return;
        WorkExecution execution=item.progress().execution();
        if(execution==null || execution.pullRequest()==null){waiting(item,"delivered_pr_required");return;}
        if(Boolean.TRUE.equals(execution.pullRequest().draft()) && !ensureMarkedReady(item,execution))return;
        Observation observed=observe(item,execution);
        if(observed.reason()!=null){waiting(item,observed.reason());return;}
        var detail=reviews.loadDetail(observed.workspace(),observed.slug(),execution.pullRequest().number()).orElse(null);
        if(detail==null || !detail.id().equals(observed.review()) || !detail.sha().equals(execution.head())
                || !"completed".equals(detail.status()) || detail.degraded()) {waiting(item,"review_result_pending");return;}
        if(detail.openBlockers()>0){waiting(item,"review_blockers_open");return;}
        // Review spend already belongs to the existing reviewer ledger. Merely observing it is not another call.
        transitions.complete(id,new WorkItemTransitions.PhaseResult(item.progress().attemptId(),true,0,0,0,true,execution.reviewed(observed.review())));
    }
    /**
     * Delivery is the end of the factory's work on a pull request, so a draft is marked ready for review
     * here. The reviewer skips drafts, and without this the review this phase waits for never comes
     * (item #41, 2026-10-08). Once per process: a pull request already ready is answered with no write, so
     * a restart costs one read.
     */
    private boolean ensureMarkedReady(WorkItemEvent item,WorkExecution execution) {
        String key=item.progress().attemptId()+":"+execution.pullRequest().number();
        if(markedReady.contains(key))return true;
        java.time.Instant refused=refusedAt.get(key);
        if(refused!=null && refused.plus(REFUSAL_RETRY).isAfter(java.time.Instant.now()))return false;
        var sink=accounts.resolve(item.repositoryId()).map(clients::pullRequestSink);
        if(sink.isEmpty()){waiting(item,"factory_account_unavailable");return false;}
        try {
            sink.orElseThrow().markReady(repositoryOf(item),execution.pullRequest());
            markedReady.add(key);
            refusedAt.remove(key);
            return true;
        }catch(dev.codespire.contract.port.PullRequestSink.DeliveryUnavailable refusal) {
            refusedAt.put(key,java.time.Instant.now());
            waiting(item,refusal.getMessage());return false;
        }catch(RuntimeException fault) {
            LOG.warnf("work item %s: pull request %d could not be marked ready (%s); the next pass tries again",
                    item.workItemId(),execution.pullRequest().number(),fault.getClass().getSimpleName());
            waiting(item,"ready_for_review_pending");return false;
        }
    }
    private dev.codespire.contract.scm.RepoRef repositoryOf(WorkItemEvent item) {
        try(Connection c=dataSource.getConnection()) {
            return sources.get(c,item.sourceId(),false).orElseThrow(()->new IllegalStateException("Work source "+item.sourceId()+" is missing")).repository();
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private Observation observe(WorkItemEvent item,WorkExecution execution) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM review_status WHERE repository_id=? AND pr_id=?")) {
            ps.setObject(1,item.repositoryId());ps.setLong(2,execution.pullRequest().number());
            try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next())return new Observation(null,null,null,"review_result_pending");
                String review=rs.getString("review_id"),reason=null;
                // A review of this head that is still running, or that failed, says so: "the review and its posted
                // result do not both cover this build" is true then, and told the operator nothing (item #41).
                String status=rs.getString("status");
                if(!execution.head().equals(rs.getString("commit_sha")))reason="review_head_not_observed";
                else if("failed".equals(status))reason="review_failed";
                // Only a review on its way is in progress. Refused, cancelled, superseded and observed reviews are
                // over, and saying "reviewing now" about them for ever is what item #41 set out to stop.
                else if(Set.of("received","reviewing").contains(status))reason="review_in_progress";
                else if(!"completed".equals(status))reason="review_"+status;
                else if(!execution.head().equals(rs.getString("last_posted_commit")))reason="review_head_not_observed";
                else if(rs.getBoolean("degraded"))reason="review_result_pending";
                else if(!"OPEN".equals(rs.getString("pr_state")) || rs.getTimestamp("archived_at")!=null)reason="review_pr_not_open";
                else if(!readable(rs.getString("findings_json"),review,false) || !readable(rs.getString("open_findings_json"),review,false)
                        || !readable(rs.getString("reconciliation_json"),review,true))reason="review_evidence_unreadable";
                return new Observation(review,rs.getString("workspace"),rs.getString("slug"),reason);
            }
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private boolean readable(String stored,String id,boolean optional) {
        if(stored==null)return optional;
        String json;
        try {json=encryption.decryptString(stored,id);}catch(RuntimeException legacy){json=stored;}
        try {
            var array=mapper.readTree(json);if(!array.isArray())return false;
            for(var entry:array) {
                if(!entry.isObject() || !entry.path("loc").isTextual() || !entry.path("msg").isTextual()
                        || !Set.of("critical","warning","suggestion","nit").contains(entry.path("sev").asText()))return false;
                if(optional && !Set.of("RESOLVED","STILL_OPEN","ACKNOWLEDGED","SUPERSEDED","UNCHANGED").contains(entry.path("status").asText()))return false;
            }
            return true;
        }catch(Exception unreadable){return false;}
    }
    private void waiting(WorkItemEvent expected,String reason) {
        QuarkusTransaction.requiringNew().run(()-> {
            try(Connection c=dataSource.getConnection()) {
                var source=sources.get(c,expected.sourceId(),true).orElseThrow();policies.get(c,source.repositoryId(),true);
                WorkItemTransitions.lockItem(c,expected.workItemId());var history=store.history(expected.workItemId());var item=(WorkItemEvent)history.getLast().payload();
                if(!"review".equals(item.phase()) || !"active".equals(item.workflowStatus()) || !Objects.equals(item.progress().attemptId(),expected.progress().attemptId()) || reason.equals(item.reason()))return;
                store.appendDecision(c,history,WorkItemLifecycle.state(item,"active",reason,"REVIEW_WAITING",item.gate(),item.progress()),"review-wait:"+UUID.randomUUID());
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot record review wait",failure);}
        });
    }
}

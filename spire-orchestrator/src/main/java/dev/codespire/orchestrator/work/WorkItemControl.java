package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Human takeover commits revocation and invalidation with the aggregate decision. */
@ApplicationScoped
public class WorkItemControl {
    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject WorkItemTransitions transitions;
    @Inject dev.codespire.orchestrator.repository.RepositoryAccounts accounts;
    @Inject dev.codespire.orchestrator.provider.ProviderClients clients;

    public WorkItemTransitions.Outcome resume(String id,long expected,String subject,String note) {
        if(subject==null || subject.isBlank() || note==null || note.isBlank())
            throw new jakarta.ws.rs.BadRequestException("A verified operator and a resume note are required");
        WorkItemEvent reference=store.load(id);
        if(reference==null)throw new jakarta.ws.rs.NotFoundException();
        if(!"suspended".equals(reference.workflowStatus()))return new WorkItemTransitions.Outcome(409,"resume_requires_suspended_item",reference);
        var observed=transitions.observe(reference);
        if(observed.evidence().failure()!=null)return new WorkItemTransitions.Outcome(503,observed.evidence().failure(),reference);
        String head;
        try {
            var account=accounts.resolve(reference.repositoryId(),dev.codespire.orchestrator.provider.ProviderRole.REVIEWER)
                    .or(()->accounts.resolve(reference.repositoryId(),dev.codespire.orchestrator.provider.ProviderRole.FACTORY)).orElseThrow();
            var scm=clients.diffSource(account);scm.assertRepoAccessible(observed.source().repository());
            // Before publication there is no remote work branch; re-observe its configured base.
            var execution=reference.progress().execution();
            String branch=execution!=null && execution.pullRequest()!=null?reference.control().branch()
                    :reference.preparation()==null?null:reference.preparation().baseBranch();
            if(branch==null)return new WorkItemTransitions.Outcome(409,"resume_requires_repository_coordinates",reference);
            head=scm.fetchBranchHead(observed.source().repository(),branch);
        }catch(RuntimeException unavailable){return new WorkItemTransitions.Outcome(503,"resume_head_unavailable",reference);}
        return transitions.resumeControlled(id,expected,observed,subject,note,head);
    }

    /**
     * Retry land on an item land handed over (spec §7.6, A3). The pull request's head is read through the lander before
     * any lock, as every forge read is; the transition compares it with the verified head under the lock.
     */
    public WorkItemTransitions.Outcome retryLand(String id,long expected,String subject,String note) {
        WorkItemEvent reference=landCommand(id,subject,note);
        WorkExecution execution=reference.progress().execution();
        if(execution==null || execution.pullRequest()==null)return new WorkItemTransitions.Outcome(409,"retry_land_unavailable",reference);
        WorkItemTransitions.Observation observed=transitions.observe(reference);
        if(observed.evidence().failure()!=null)return new WorkItemTransitions.Outcome(503,observed.evidence().failure(),reference);
        dev.codespire.contract.port.PullRequestLander.LandState pullRequest=transitions.readPullRequest(reference,execution.pullRequest().number());
        if(pullRequest==null || pullRequest.headSha()==null)return new WorkItemTransitions.Outcome(503,"retry_land_head_unavailable",reference);
        return transitions.retryLandControlled(id,expected,observed,subject,note,pullRequest.headSha());
    }
    /** Stop on an item waiting at land (spec §7.6, review of slice 9). Nothing is read or written on the forge: the pull request stays open. */
    public WorkItemTransitions.Outcome stopLand(String id,long expected,String subject,String note) {
        landCommand(id,subject,note);
        return transitions.stopLandControlled(id,expected,subject,note);
    }
    /** Both land commands are audited, so both need a verified operator and a note, like resume. */
    private WorkItemEvent landCommand(String id,String subject,String note) {
        if(subject==null || subject.isBlank() || note==null || note.isBlank())
            throw new jakarta.ws.rs.BadRequestException("A verified operator and a note are required");
        WorkItemEvent reference=store.load(id);
        if(reference==null)throw new jakarta.ws.rs.NotFoundException();
        return reference;
    }

    public void suspend(String id,String channel,String delivery,String actor,String head) {
        change(id,channel,delivery,actor,head,false);
    }
    public void retire(String id,String delivery,String reason) {
        change(id,"tracker",delivery,null,null,true);
    }
    /**
     * The repository's CURRENT reviewer and factory identities, not only the ones recorded at build claim: a
     * registration rotated after the claim is still the bot, and its activity is still machine activity
     * (review of PR #196, security/H-1; M4 land, A10).
     */
    boolean currentMachine(UUID repository,String actor) {
        if(actor==null || actor.isBlank())return false;
        for(dev.codespire.orchestrator.provider.ProviderRole role:List.of(dev.codespire.orchestrator.provider.ProviderRole.REVIEWER,dev.codespire.orchestrator.provider.ProviderRole.FACTORY))
            if(accounts.resolve(repository,role).map(account->actor.equals(account.botAccountId())).orElse(false))return true;
        return false;
    }
    private void change(String id,String channel,String delivery,String actor,String head,boolean retired) {
        WorkItemEvent reference=store.load(id);if(reference==null)return;
        // Read before the locks, as every forge-account read is; tracker identities are their own namespace.
        boolean currentBot=!retired && !"tracker".equals(channel) && currentMachine(reference.repositoryId(),actor);
        QuarkusTransaction.requiringNew().run(()->{
            try(Connection c=dataSource.getConnection()) {
                var source=sources.get(c,reference.sourceId(),true).orElseThrow();
                policies.get(c,source.repositoryId(),true);
                WorkItemTransitions.lockItem(c,id);
                var history=store.history(id);var item=(WorkItemEvent)history.getLast().payload();
                if(received(c,id,channel,delivery))return;
                if("retired".equals(item.workflowStatus())){receipt(c,id,channel,delivery,"already_retired");return;}
                // A completed item's ticket stays open so testing can follow (spec §7.7); activity on it is not a takeover.
                if(!retired && "completed".equals(item.workflowStatus())){receipt(c,id,channel,delivery,"already_completed");return;}
                // Tracker identities and SCM identities are separate namespaces even on one item.
                if(currentBot || !retired && item.control()!=null && ("tracker".equals(channel)?item.control().trackerMachine(actor):item.control().machine(actor))) {
                    receipt(c,id,channel,delivery,"recorded_machine");return;
                }
                WorkGate gate=item.gate();
                if(gate!=null && "OPEN".equals(gate.state()))gate=gate.resolve("SUPERSEDED",actor,channel,delivery,null);
                WorkControl control=item.control()==null?new WorkControl(null,null,null,null,null,null,null,null):item.control();
                var next=WorkItemLifecycle.state(item,retired?"retired":"suspended",retired?"issue_retired":"human_takeover",
                        retired?"WORK_ITEM_RETIRED":"HUMAN_TAKEOVER",gate,item.progress().reserve(false))
                        .controlled(control.action(actor,retired?"Confirmed issue deletion or transfer":"Human activity observed; publication already in progress may complete",head));
                store.appendDecision(c,history,next,"activity:"+channel+":"+delivery);
                receipt(c,id,channel,delivery,retired?"retired":"suspended");
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot record work control",failure);}
        });
    }
    static boolean received(Connection c,String id,String channel,String delivery)throws SQLException {
        try(var ps=c.prepareStatement("SELECT 1 FROM work_activity_receipt WHERE work_item_id=? AND channel=? AND delivery_id=?")) {
            ps.setString(1,id);ps.setString(2,channel);ps.setString(3,delivery);try(var rs=ps.executeQuery()){return rs.next();}
        }
    }
    static void receipt(Connection c,String id,String channel,String delivery,String outcome)throws SQLException {
        try(var ps=c.prepareStatement("INSERT INTO work_activity_receipt(work_item_id,channel,delivery_id,outcome) VALUES (?,?,?,?) ON CONFLICT DO NOTHING")) {
            ps.setString(1,id);ps.setString(2,channel);ps.setString(3,delivery);ps.setString(4,outcome);ps.executeUpdate();
        }
    }
}

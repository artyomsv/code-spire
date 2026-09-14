package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.*;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.FixPermissionService;
import dev.codespire.orchestrator.provider.*;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Channel authority is measured here; every accepted answer enters the same aggregate command. */
@ApplicationScoped
public class WorkGateChannels {
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;
    @Inject WorkItemControl control;
    @Inject WorkSourceRegistry sources;
    @Inject RepositoryAccounts accounts;
    @Inject ProviderClients clients;
    @Inject FixPermissionService permissions;
    @Inject DataSource dataSource;

    public String tracker(WorkSourceRegistry.Source source,WorkSourceDelivery delivery) {
        var signal=delivery.signal();var activity=signal.activity();
        String id=WorkItemIds.of(source.scm(),source.forgeOrigin(),source.repository(),signal.issue().ref());
        WorkItemEvent item=store.load(id);
        if(item==null || !item.sourceId().equals(source.id()) || !item.issue().ref().equals(signal.issue().ref()))return null;
        String key=activity.id();
        if(received(id,"tracker",key))return id;
        if(activity.retired()){control.retire(id,key,"Confirmed issue deletion or transfer");return id;}
        var answer=activity.answer();
        if(answer!=null && source.allowedActors().contains(activity.actorId())) {
            WorkGate gate;
            try {gate=transitions.gateFromHistory(id,answer.gateId());}
            catch(jakarta.ws.rs.NotFoundException absent){gate=null;}
            if(gate!=null && gate.generation()==answer.generation() && Objects.equals(gate.artifact(),answer.artifact())) {
                var result=transitions.answer(new ResolveGate(gate.id(),gate.version(),key,answer.approve(),null,
                        activity.actorId(),ResolveGate.Channel.TRACKER,answer.generation(),answer.artifact()));
                if(result.status()>=500)throw new jakarta.ws.rs.ServiceUnavailableException(result.reason());
                receipt(id,"tracker",key,"gate_answer:"+result.reason());return id;
            }
        }
        control.suspend(id,"tracker",key,activity.actorId(),null);return id;
    }

    public boolean approvalAvailable(UUID repository) {
        return accounts.resolve(repository,ProviderRole.REVIEWER)
                .map(account->clients.pullRequestApprovalSource(account).available()).orElse(false);
    }

    public void activity(UUID repository,IntegrationEvent.RepositoryActivity activity,String delivery) {
        List<String> ids=new ArrayList<>();
        try(Connection c=dataSource.getConnection();var ps=c.prepareStatement("SELECT id FROM work_item WHERE repository_id=? AND workflow_status NOT IN ('retired','completed')")) {
            ps.setObject(1,repository);try(var rs=ps.executeQuery()){while(rs.next())ids.add(rs.getString(1));}
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        for(String id:ids) {
            WorkItemEvent item=store.load(id);var source=sources.get(item.sourceId()).orElseThrow();
            if(!source.repository().equals(activity.repo()) || !linked(item,activity))continue;
            if(received(id,"scm",delivery))continue;
            if("approval".equals(activity.kind())) {
                approval(item,source,activity,delivery);continue;
            }
            if("comment".equals(activity.kind()) && activity.fixCommand()
                    && permissions.authorize(repository,activity.actorId()).allowed()) {
                receipt(id,"scm",delivery,"authorized_fix");continue;
            }
            control.suspend(id,"scm",delivery,activity.actorId(),activity.head());
        }
    }

    static boolean linked(WorkItemEvent item,IntegrationEvent.RepositoryActivity activity) {
        var execution=item.progress().execution();
        if(activity.prId()>0) {
            if(execution==null || execution.pullRequest()==null || execution.pullRequest().number()!=activity.prId())return false;
            return activity.branch()==null || item.control()!=null && ("refs/heads/"+item.control().branch()).equals(activity.branch());
        }
        return "push".equals(activity.kind()) && item.control()!=null
                && ("refs/heads/"+item.control().branch()).equals(activity.branch());
    }

    private void approval(WorkItemEvent item,WorkSourceRegistry.Source source,IntegrationEvent.RepositoryActivity activity,String delivery) {
        WorkGate gate=item.gate();
        if(!"waiting_approval".equals(item.workflowStatus()) || gate==null || !"OPEN".equals(gate.state()) || !"land".equals(gate.phase()))return;
        var account=accounts.resolve(item.repositoryId(),ProviderRole.REVIEWER).orElse(null);
        if(account==null)return;
        var approvals=clients.pullRequestApprovalSource(account);if(!approvals.available())return;
        // Both observations are live reads. The webhook's approval and head are not authority.
        var review=approvals.read(source.repository(),activity.prId(),activity.reviewId());
        var pr=clients.diffSource(account).fetchPullRequest(source.repository(),activity.prId());
        if(!review.approved() || !review.human() || !Objects.equals(review.actorId(),activity.actorId())
                || item.control()!=null && item.control().machine(review.actorId()))return;
        if(!pr.repo().equals(source.repository()) || pr.prId()!=activity.prId()
                || !Objects.equals(review.head(),pr.headCommit()) || !Objects.equals(gate.artifact(),pr.headCommit())
                || item.control()==null || !item.control().branch().equals(pr.sourceBranch()))return;
        if(!permissions.authorizeApproval(item.repositoryId(),review.actorId()).allowed())return;
        var result=transitions.answer(new ResolveGate(gate.id(),gate.version(),delivery,true,null,review.actorId(),
                ResolveGate.Channel.PR_REVIEW,gate.generation(),pr.headCommit()));
        if(result.status()>=500)throw new jakarta.ws.rs.ServiceUnavailableException(result.reason());
        receipt(item.workItemId(),"scm",delivery,"gate_answer:"+result.reason());
    }

    private boolean received(String id,String channel,String delivery) {
        try(Connection c=dataSource.getConnection()){return WorkItemControl.received(c,id,channel,delivery);}
        catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private void receipt(String id,String channel,String delivery,String outcome) {
        try(Connection c=dataSource.getConnection()){WorkItemControl.receipt(c,id,channel,delivery,outcome);}
        catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
}

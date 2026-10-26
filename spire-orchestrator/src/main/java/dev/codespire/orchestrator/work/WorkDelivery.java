package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.port.PullRequestSink;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.work.*;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.factory.*;
import dev.codespire.orchestrator.provider.*;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Publish a verified retained head, then propose it. Each external write follows a durable claim. */
@ApplicationScoped
public class WorkDelivery {
    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;
    @Inject WorkClock clock;
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject MachineAccounts accounts;
    @Inject RepositoryAccounts repositoryAccounts;
    @Inject ProviderClients clients;
    @Inject RunCredentials credentials;
    @Inject FactoryRunProjection runs;
    @Inject WorkPublicationTransport transport;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;
    @Inject WorkLandFixes landFixes;
    @Inject WorkStepSummaries summaries;
    record Effect(UUID attempt,String item,long generation,String run,String state,Instant expires,byte[] request) {}
    /** @param kept a land fix's pull request, which the push updated: delivery completes on it and opens none (M4 land) */
    record Claim(RunCommand.PublishWorkRun permit,PullRequestSink sink,PullRequestSink.NewPullRequest request,PullRequestRef kept) {
        Claim(RunCommand.PublishWorkRun permit,PullRequestSink sink,PullRequestSink.NewPullRequest request) {this(permit,sink,request,null);}
    }

    public boolean available(WorkItemEvent item,String phase) {
        WorkExecution execution=item.progress().execution();
        if(execution==null)return false;
        if("review".equals(phase))return execution.pullRequest()!=null && repositoryAccounts.resolve(item.repositoryId(),ProviderRole.REVIEWER).isPresent();
        if(!"deliver".equals(phase) || !passed(execution))return false;
        var account=accounts.resolve(item.repositoryId());if(account.isEmpty())return false;
        String mode=item.policy().effective().get(WorkPolicy.Phase.DELIVER);
        return "pr".equals(mode) || "draft_pr".equals(mode) && clients.pullRequestSink(account.get()).supportsDrafts();
    }

    @Scheduled(every="${spire.work-delivery-interval:5s}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void drain() {
        List<UUID> ids=new ArrayList<>();
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT attempt_id FROM work_delivery_effect WHERE state NOT IN ('delivered','refused') ORDER BY created_at LIMIT 20");ResultSet rs=ps.executeQuery()) {
            while(rs.next())ids.add(rs.getObject(1,UUID.class));
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        for(UUID id:ids)advance(id);
    }

    public void advance(UUID id) {
        Effect effect=read(id);if(effect==null || Set.of("delivered","refused").contains(effect.state()))return;
        if("publishing".equals(effect.state())) {
            var run=runs.find(effect.run()).orElseThrow();
            if(run.endedAt()!=null) {
                if(!"succeeded".equals(run.status()) || !Objects.equals("refs/heads/"+run.branch(),run.pushedRef())) {stop(effect,"publication_failed");return;}
                if(!state(effect,"pushed",null))return;effect=read(id);
            } else if(!clock.now().isBefore(effect.expires())) {
                stop(effect,"publication_permit_expired");transport.cancel(effect.run());return;
            } else {
                // A permit is a short lease, not permanent authority. Cancel when a later
                // observation invalidates it; an already executing push may still win that race.
                Effect waiting=effect;
                var authority=transitions.observe(store.load(effect.item()));
                QuarkusTransaction.requiringNew().call(()->claim(waiting,authority));
                if("refused".equals(read(id).state()))transport.cancel(effect.run());
                return;
            }
        }
        WorkItemEvent item=store.load(effect.item());
        if("proposing".equals(effect.state()) && Set.of("suspended","retired").contains(item.workflowStatus())) {
            observeHeldProposal(effect,item);return;
        }
        // A crash after aggregate completion must not repeat the proposal or require REVIEW to still be DELIVER.
        if("proposing".equals(effect.state()) && item.progress().execution()!=null
                && item.progress().execution().pullRequest()!=null && item.progress().execution().runId().equals(effect.run())
                && completedDelivery(effect)) {
            state(effect,"delivered",null);return;
        }
        var observed=transitions.observe(item);Effect before=effect;
        Claim claim=QuarkusTransaction.requiringNew().call(()->claim(before,observed));
        if(claim==null)return;
        if(claim.permit()!=null) {
            RunLaunch.Outcome sent=transport.publish(claim.permit());
            if(sent instanceof RunLaunch.DefiniteMiss)state(before,"pending","publication_dispatch_missed");
            else if(sent instanceof RunLaunch.Uncertain)state(before,"publishing","publication_dispatch_uncertain");
            return;
        }
        if(claim.kept()!=null) {
            // A land fix pushed to its own pull request's branch; the pull request is reused, never opened again (§7.5).
            WorkItemEvent current=store.load(before.item());
            WorkItemTransitions.Outcome complete=transitions.complete(before.item(),new WorkItemTransitions.PhaseResult(before.attempt(),true,0,0,0,true,
                    current.progress().execution().delivered(claim.kept())));
            if(complete.status()==200)state(before,"delivered",null);
            return;
        }
        try {
            PullRequestRef opened="proposing".equals(before.state())
                    ?claim.sink().findByHead(observed.source().repository(),claim.request().headBranch(),claim.request().baseBranch()).orElse(null)
                    :claim.sink().open(observed.source().repository(),claim.request());
            if(opened==null){state(before,"proposing","proposal_outcome_unknown");return;}
            if(!Objects.equals(claim.request().draft(),opened.draft())) {stop(before,"requested_pr_state_not_observed");return;}
            runs.pullRequestOpened(before.run(),opened.number(),opened.url());
            var current=store.load(before.item());
            var complete=transitions.complete(before.item(),new WorkItemTransitions.PhaseResult(before.attempt(),true,0,0,0,true,
                    current.progress().execution().delivered(opened)));
            if(complete.status()==200)state(before,"delivered",null);
            else if(Set.of("suspended","retired").contains(store.load(before.item()).workflowStatus()))observeHeldProposal(before,store.load(before.item()));
        }catch(PullRequestSink.DeliveryUnavailable refusal) {
            stop(before,refusal.getMessage());
        }catch(RuntimeException failure) {
            // Never POST again after an ambiguous response. Recovery reads the forge by both branches.
            state(before,"proposing","proposal_outcome_unknown");
        }
    }

    private void observeHeldProposal(Effect effect,WorkItemEvent item) {
        try {
            byte[] stored=encryption.decrypt(effect.request(),"work-delivery-request:"+effect.attempt());
            PullRequestRef pr;
            // A land fix's row holds the pull request its push updated; a row is proposing only after that push, so there
            // is nothing left to find. A proposal's row holds the request it sent.
            if(!mapper.readTree(stored).has("headBranch"))pr=mapper.readValue(stored,PullRequestRef.class);
            else {
                var account=accounts.resolve(item.repositoryId()).orElseThrow();
                var source=sources.get(item.sourceId()).orElseThrow();
                var request=mapper.readValue(stored,PullRequestSink.NewPullRequest.class);
                // The write was already claimed before takeover. Recovery may only read its outcome.
                var found=clients.pullRequestSink(account).findByHead(source.repository(),request.headBranch(),request.baseBranch());
                if(found.isEmpty())return;
                pr=found.orElseThrow();runs.pullRequestOpened(effect.run(),pr.number(),pr.url());
            }
            QuarkusTransaction.requiringNew().run(()->{
                try(var c=dataSource.getConnection()) {
                    sources.get(c,item.sourceId(),true);policies.get(c,item.repositoryId(),true);WorkItemTransitions.lockItem(c,item.workItemId());
                    var claimed=read(c,effect.attempt(),true);
                    if(claimed==null || !"proposing".equals(claimed.state()))return;
                    var history=store.history(item.workItemId());var current=(WorkItemEvent)history.getLast().payload();
                    if(!Set.of("suspended","retired").contains(current.workflowStatus()))return;
                    var execution=current.progress().execution();
                    if(execution!=null && effect.run().equals(execution.runId()))
                        store.appendDecision(c,history,WorkItemLifecycle.state(current,current.workflowStatus(),current.reason(),"PUBLICATION_OBSERVED",
                                current.gate(),current.progress().withExecution(execution.delivered(pr))),"held-proposal:"+effect.attempt());
                    state(effect,"delivered","publication_observed_after_takeover");
                }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
                catch(java.io.IOException failure){throw new IllegalStateException("Cannot record publication outcome",failure);}
            });
        }catch(RuntimeException | java.io.IOException unavailable) { /* Preserve the proposing row; the next sweep only re-reads. */ }
    }

    private Claim claim(Effect expected,WorkItemTransitions.Observation observed) {
        try(Connection c=dataSource.getConnection()) {
            if(!transitions.current(c,observed))return null;
            WorkItemTransitions.lockItem(c,expected.item());
            Effect effect=read(c,expected.attempt(),true);if(effect==null || !effect.state().equals(expected.state()))return null;
            var history=store.history(effect.item());var item=(WorkItemEvent)history.getLast().payload();
            String refusal=refusal(c,item,effect,history,observed);
            if(refusal!=null){stopLocked(c,history,item,effect,refusal);return null;}
            // Lock the factory identity separately: the tracker source may use a different account.
            try(PreparedStatement ps=c.prepareStatement("SELECT p.id FROM repository_account a JOIN scm_provider p ON p.id=a.account_id WHERE a.repository_id=? AND a.role='FACTORY' FOR UPDATE OF p")) {
                ps.setObject(1,item.repositoryId());try(ResultSet rs=ps.executeQuery()){if(!rs.next()){stopLocked(c,history,item,effect,"factory_account_unavailable");return null;}}
            }
            var account=accounts.resolve(item.repositoryId()).orElse(null);
            // Read under the item lock, like the run dispatcher's: a land fix pushes with the lease on the approved head.
            WorkLandFixes.Fix fix=landFixes.current(c,history).orElse(null);
            if(account==null){stopLocked(c,history,item,effect,"factory_account_unavailable");return null;}
            PullRequestSink sink=clients.pullRequestSink(account);
            var run=runs.find(effect.run()).orElseThrow();
            if("pending".equals(effect.state()) && !"awaiting_delivery".equals(run.status())) {
                stopLocked(c,history,item,effect,"held_run_not_ready");return null;
            }
            // A land fix opens no pull request: its run's base is the item branch itself, which no forge takes as a base.
            PullRequestSink.NewPullRequest request=fix!=null?null:new PullRequestSink.NewPullRequest(run.branch(),run.baseBranch(),"Prepared work: "+item.issue().issueKey(),
                    "Prepared work item: "+item.issue().link()+"\n\nBuilt commit: `"+item.progress().execution().head()+"`"+(item.steps()==null?"":"\n\n"+WorkPullRequestSteps.section(WorkPullRequestSteps.lines(
                            item.steps(),observed.artifacts().steps(),summaries.list(c,item.workItemId(),item.generation())))),
                    "draft_pr".equals(item.policy().effective().get(WorkPolicy.Phase.DELIVER)));
            if(request!=null && request.draft() && !sink.supportsDrafts()){stopLocked(c,history,item,effect,"draft_pr_unsupported");return null;}
            if("pending".equals(effect.state())) {
                // PostgreSQL stores microseconds. Use the same exact boundary in storage and on the wire.
                var execution=item.progress().execution();Instant issued=clock.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),expires=issued.plusSeconds(30);
                var command=new RunCommand.PublishWorkRun(effect.run(),new WorkPublicationPermit(execution.build(),effect.attempt(),execution.head(),
                        issued,expires,item.policy().limits().protectedPaths().stream().sorted().toList()),credentials.packScm(effect.run(),account.botUsername(),account.secret()));
                // The push may land only while the item branch is still at the approved head, and excuses only the base tip the land effect read.
                if(fix!=null)command=new RunCommand.PublishWorkRun(command.runId(),command.permit().onExistingBranch(fix.approvedHead(),fix.baseTip()),command.scmCredential());
                try(PreparedStatement ps=c.prepareStatement("UPDATE work_delivery_effect SET state='publishing',permit=?,permit_expires_at=?,reason=NULL WHERE attempt_id=?")) {
                    ps.setBytes(1,encode(command,"work-delivery-permit:"+effect.attempt()));ps.setTimestamp(2,Timestamp.from(expires));ps.setObject(3,effect.attempt());ps.executeUpdate();
                }
                return new Claim(command,null,null);
            }
            if("proposing".equals(effect.state())) {
                // A land fix's row holds the pull request its push updated: completing delivery on it is all that is left.
                if(fix==null)request=mapper.readValue(encryption.decrypt(effect.request(),"work-delivery-request:"+effect.attempt()),PullRequestSink.NewPullRequest.class);
            }
            else if("pushed".equals(effect.state())) {
                try(PreparedStatement ps=c.prepareStatement("UPDATE work_delivery_effect SET state='proposing',request=?,reason=NULL WHERE attempt_id=?")) {
                    // A proposing row must hold its request (V70); a land fix holds the pull request it updates instead.
                    ps.setBytes(1,encode(request==null?fix.pullRequest():request,"work-delivery-request:"+effect.attempt()));ps.setObject(2,effect.attempt());ps.executeUpdate();
                }
            } else return null;
            return new Claim(null,sink,request,fix==null?null:fix.pullRequest());
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot persist work delivery",failure);}
    }

    private String refusal(Connection c,WorkItemEvent item,Effect effect,List<dev.codespire.contract.event.EventEnvelope> history,
                           WorkItemTransitions.Observation observed) throws SQLException {
        if(item.generation()!=effect.generation() || !Objects.equals(item.progress().attemptId(),effect.attempt())
                || !"deliver".equals(item.phase()) || !"active".equals(item.workflowStatus()))return "work_item_changed";
        if(observed.evidence().failure()!=null)return observed.evidence().failure();
        if(observed.artifacts().failure()!=null)return observed.artifacts().failure();
        var admitted=history.stream().map(event->(WorkItemEvent)event.payload())
                .filter(event->"PHASE_STARTED".equals(event.milestone()) && effect.attempt().equals(event.progress().attemptId())).findFirst().orElseThrow();
        if(!transitions.select(observed,item).equals(admitted.policy()) || observed.policy().revision()!=admitted.policyRevision())return "policy_changed_before_delivery";
        WorkExecution execution=item.progress().execution();
        if(execution==null || !passed(execution) || !execution.runId().equals(effect.run()) || item.preparation()==null
                || !execution.build().preparationBinding().equals(item.preparation().binding()))return "verified_build_required";
        try(PreparedStatement ps=c.prepareStatement("""
                SELECT 1 FROM work_run_effect b JOIN factory_run r ON r.run_id=b.run_id
                JOIN work_phase_attempt v ON v.work_item_id=b.work_item_id AND v.generation=b.generation
                WHERE b.run_id=? AND b.work_item_id=? AND b.generation=? AND b.attempt_id=? AND b.preparation_binding=?
                  AND b.ready_processed AND r.checkpoint_head=? AND v.id=? AND v.phase='verify' AND v.state='completed'
                """)) {
            ps.setString(1,effect.run());ps.setString(2,item.workItemId());ps.setLong(3,item.generation());ps.setObject(4,execution.build().buildAttemptId());
            ps.setString(5,execution.build().preparationBinding());ps.setString(6,execution.head());ps.setObject(7,execution.verificationAttempt());
            try(ResultSet rs=ps.executeQuery()){if(!rs.next())return "verified_checkpoint_not_observed";}
        }
        String mode=item.policy().effective().get(WorkPolicy.Phase.DELIVER);
        return Set.of("pr","draft_pr").contains(mode)?null:"deliver_off";
    }

    /**
     * Only a PASSED result for this exact head authorizes delivery (M4). A bare attempt id from history written
     * before M4, or a failed or unverified result, verified nothing a pull request could rely on.
     */
    static boolean passed(WorkExecution execution) {
        return execution.verificationAttempt()!=null && execution.verification()!=null && execution.verification().passed()
                && execution.verification().attemptId().equals(execution.verificationAttempt());
    }

    private byte[] encode(Object value,String aad) throws java.io.IOException {return encryption.encrypt(mapper.writeValueAsBytes(value),aad);}
    private boolean completedDelivery(Effect effect) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT 1 FROM work_phase_attempt WHERE id=? AND work_item_id=? AND generation=? AND phase='deliver' AND state='completed'")) {
            ps.setObject(1,effect.attempt());ps.setString(2,effect.item());ps.setLong(3,effect.generation());
            try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private Effect read(UUID id) {try(Connection c=dataSource.getConnection()){return read(c,id,false);}catch(SQLException failure){throw WorkSourceRegistry.database(failure);}}
    private Effect read(Connection c,UUID id,boolean lock) throws SQLException {
        String sql=lock?"SELECT * FROM work_delivery_effect WHERE attempt_id=? FOR UPDATE":"SELECT * FROM work_delivery_effect WHERE attempt_id=?";
        try(PreparedStatement ps=c.prepareStatement(sql)){ps.setObject(1,id);try(ResultSet rs=ps.executeQuery()){
            if(!rs.next())return null;Timestamp expires=rs.getTimestamp("permit_expires_at");
            return new Effect(id,rs.getString("work_item_id"),rs.getLong("generation"),rs.getString("run_id"),rs.getString("state"),expires==null?null:expires.toInstant(),rs.getBytes("request"));
        }}
    }
    private boolean state(Effect effect,String state,String reason) {
        String expected=switch(state) {
            case "pushed","pending","publishing" -> "publishing";
            case "proposing","delivered" -> "proposing";
            default -> effect.state();
        };
        // A stale observation must not rewind another caller's durable proposal claim.
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_delivery_effect SET state=?,reason=? WHERE attempt_id=? AND (state=? OR (?='refused' AND state NOT IN ('delivered','refused')))")) {
            ps.setString(1,state);ps.setString(2,reason);ps.setObject(3,effect.attempt());ps.setString(4,expected);ps.setString(5,state);return ps.executeUpdate()==1;
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private void stop(Effect effect,String reason) {
        QuarkusTransaction.requiringNew().run(()-> {
            try(Connection c=dataSource.getConnection()) {
                var reference=store.load(effect.item());
                var source=sources.get(c,reference.sourceId(),true).orElseThrow();policies.get(c,source.repositoryId(),true);
                WorkItemTransitions.lockItem(c,effect.item());var history=store.history(effect.item());var item=(WorkItemEvent)history.getLast().payload();
                stopLocked(c,history,item,effect,reason);
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot record delivery refusal",failure);}
        });
    }
    private void stopLocked(Connection c,List<dev.codespire.contract.event.EventEnvelope> history,WorkItemEvent item,Effect effect,String reason) throws SQLException,java.io.IOException {
        state(effect,"refused",reason);
        // Only a deliver still running is stopped by its row. A merge, a close or a takeover already ended it, and a
        // refusal written over them would hide that; a land fix's pull request can be merged before its delivery completes.
        if(item.generation()==effect.generation() && Objects.equals(item.progress().attemptId(),effect.attempt())
                && "deliver".equals(item.phase()) && "active".equals(item.workflowStatus()))
            transitions.decide(c,new WorkItemTransitions.Decision(history,WorkItemLifecycle.state(item,"stopped",reason,"WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false)),"delivery-refused:"+effect.attempt()));
    }
}

package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import dev.codespire.worksource.WorkIssueLocation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import javax.sql.DataSource;
import java.time.Instant;
import java.sql.*;
import java.util.*;

/** Every continuation observes remote authority before acquiring registry locks. */
@ApplicationScoped
public class WorkItemTransitions {
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject WorkItemStore store;
    @Inject DataSource dataSource;
    @Inject WorkClock clock;
    @Inject WorkPhaseCapability capability;
    @Inject WorkArtifacts artifacts;
    @Inject dev.codespire.orchestrator.factory.WorkRunAssembly runAssembly;
    public record Observation(WorkSourceRegistry.Source source, WorkPolicyRegistry.Policy policy, WorkEvidence evidence, WorkArtifacts.Evidence artifacts,WorkPreparation preparation) {
        public Observation(WorkSourceRegistry.Source source,WorkPolicyRegistry.Policy policy,WorkEvidence evidence) {
            this(source,policy,evidence,WorkArtifacts.Evidence.absent(),null);
        }
    }
    public record Outcome(int status,String reason,WorkItemEvent item) {}
    public record PhaseResult(UUID attemptId,boolean successful,long wallSeconds,long costMillicents,long calls,boolean usageKnown,WorkExecution execution) {
        public PhaseResult(UUID attemptId,boolean successful,long wallSeconds,long costMillicents,long calls,boolean usageKnown) {
            this(attemptId,successful,wallSeconds,costMillicents,calls,usageKnown,null);
        }
        public PhaseResult(UUID attemptId,boolean successful,long wallSeconds,long costMillicents,long calls) {
            this(attemptId,successful,wallSeconds,costMillicents,calls,true);
        }
    }

    WorkItemEvent admission(WorkItemEvent next,long historySize) {
        if(next.gate()==null && "capability_unavailable".equals(next.workflowStatus())
                && "approve".equals(next.policy().effective().get(WorkPolicy.Phase.valueOf(next.phase().toUpperCase(Locale.ROOT)))))
            return enter(next,historySize,false,clock.now());
        return next;
    }

    public Outcome resume(String id,long expectedRevision,boolean readmit) {
        WorkItemEvent item=require(id);
        return advance(id,expectedRevision,observe(item),readmit,null);
    }

    /** Called by a bound integration result, never exposed as a dashboard success switch. */
    public Outcome complete(String id,PhaseResult result) {
        WorkItemEvent item=require(id);
        return advance(id,-1,observe(item),false,Objects.requireNonNull(result));
    }

    /** A stopped or superseded build still bought usage. Account it once without authorizing a phase. */
    Outcome recordLateBuild(String id,PhaseResult result) {
        return QuarkusTransaction.requiringNew().call(()->{
            try(Connection c=dataSource.getConnection()) {
                lockItem(c,id);var history=store.history(id);var item=(WorkItemEvent)history.getLast().payload();
                String key="late-build-result:"+result.attemptId();
                if(history.stream().anyMatch(event->key.equals(event.correlationId())))return new Outcome(200,"late_result_already_accounted",item);
                try(PreparedStatement ps=c.prepareStatement("SELECT a.state FROM work_phase_attempt a JOIN work_run_effect b ON b.attempt_id=a.id WHERE a.id=? AND a.work_item_id=? AND a.phase='build'")) {
                    ps.setObject(1,result.attemptId());ps.setString(2,id);try(ResultSet rs=ps.executeQuery()) {
                        if(!rs.next())return new Outcome(409,"build_attempt_missing",item);
                        if("completed".equals(rs.getString(1)))return new Outcome(200,"result_already_applied",item);
                    }
                }
                if("active".equals(item.workflowStatus()) && Objects.equals(result.attemptId(),item.progress().attemptId()))
                    return new Outcome(409,"build_attempt_active",item);
                WorkProgress progress=item.progress().account(result.wallSeconds(),result.costMillicents(),result.calls());
                if(!result.usageKnown())progress=progress.unknownUsage();
                var next=state(item,item.workflowStatus(),item.reason(),"LATE_BUILD_RESULT",item.gate(),progress);
                store.appendDecision(c,history,next,key);
                return new Outcome(200,"late_result_accounted",next);
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot account late build result",failure);}
        });
    }

    Outcome advance(String id,long expected,Observation observed,boolean readmit,PhaseResult result) {
        return QuarkusTransaction.requiringNew().call(()-> {
            try(Connection c=dataSource.getConnection()) {
                if(!current(c,observed))return new Outcome(503,"authority_changed_during_read",require(id));
                lockItem(c,id);
                var history=store.history(id);WorkItemEvent item=(WorkItemEvent)history.getLast().payload();
                if(expected>=0 && history.size()!=expected)return new Outcome(409,"work_item_changed",item);
                if(!Objects.equals(item.preparation(),observed.preparation()))return new Outcome(503,"artifacts_changed_during_read",item);
                if(result!=null) {
                    try(PreparedStatement ps=c.prepareStatement("SELECT state FROM work_phase_attempt WHERE id=? AND work_item_id=?")) {
                        ps.setObject(1,result.attemptId());ps.setString(2,id);try(ResultSet rs=ps.executeQuery()) {
                            if(rs.next() && "completed".equals(rs.getString(1)))return new Outcome(200,"result_already_applied",item);
                        }
                    }
                    if(!Objects.equals(result.attemptId(),item.progress().attemptId()))return new Outcome(409,"attempt_changed",item);
                    if("completed".equals(item.progress().attemptState()))return new Outcome(200,"result_already_applied",item);
                    if(!"active".equals(item.workflowStatus()))return new Outcome(409,"item_not_active",item);
                    if(!validExecution(item,result))return new Outcome(409,"phase_evidence_mismatch",item);
                } else if(!readmit && Set.of("active","waiting_approval","not_eligible","stopped","failed","retired","completed").contains(item.workflowStatus()))
                    return new Outcome(409,"explicit_readmission_required",item);
                if(readmit && Set.of("active","waiting_approval","retired").contains(item.workflowStatus()))return new Outcome(409,"readmission_unavailable",item);
                WorkPolicy.Selection selection=select(observed,readmit?null:item);
                WorkItemEvent next=item.decision(observed.policy().revision(),authority(observed.source()),selection,item.phase(),item.workflowStatus(),item.reason(),"POLICY_CHECKED",item.gate(),item.progress());
                if(readmit) {
                    if(observed.evidence().failure()!=null)return new Outcome(503,observed.evidence().failure(),item);
                    next=next.readmit();
                    store.appendDecision(c,history,next,"readmit:"+UUID.randomUUID());
                    history=store.history(id);
                }
                if(result!=null) {
                    WorkProgress progress=next.progress().finish(result.wallSeconds(),result.costMillicents(),result.calls());
                    if(result.execution()!=null)progress=progress.withExecution(result.execution());
                    if(!result.usageKnown())progress=progress.unknownUsage();
                    next=state(next,result.successful()?"awaiting_input":"failed",result.successful()?"phase_completed":"phase_failed",
                            result.successful()?"PHASE_COMPLETED":"PHASE_FAILED",next.gate(),progress);
                    store.appendDecision(c,history,next,"phase-result:"+result.attemptId());
                    if(!result.successful())return new Outcome(200,next.reason(),next);
                    history=store.history(id);
                    next=next.decision(next.policyRevision(),next.authority(),next.policy(),nextPhase(item.phase()),next.workflowStatus(),next.reason(),next.milestone(),next.gate(),next.progress());
                }
                if(observed.evidence().failure()!=null)next=state(next,"awaiting_input",observed.evidence().failure(),"AUTHORITY_UNAVAILABLE",next.gate(),next.progress().reserve(false));
                else if(observed.artifacts().failure()!=null)next=state(next,"awaiting_input",observed.artifacts().failure(),"ARTIFACTS_REQUIRED",next.gate(),next.progress().reserve(false));
                else {
                    next=enterPrepared(c,history,next,false,clock.now());
                    history=store.history(id);
                }
                store.appendDecision(c,history,next,"transition:"+UUID.randomUUID());
                return new Outcome(200,next.reason(),next);
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode phase decision",failure);}
        });
    }

    private WorkItemEvent enter(WorkItemEvent item,long historySize,boolean approved,Instant now) {
        return WorkItemLifecycle.enter(item,store.load(item.workItemId()),historySize,approved,now,
                capability.available(item,"intake".equals(item.phase())?"spec":item.phase()),UUID.randomUUID());
    }

    private static boolean validExecution(WorkItemEvent item,PhaseResult result) {
        WorkExecution proof=result.execution(),previous=item.progress().execution();
        // Older policy-only histories carry no build execution. They cannot authorize a delivery;
        // the capability/effect checks require its evidence independently.
        if(proof==null)return !result.successful() || previous==null || !Set.of("verify","deliver","review").contains(item.phase());
        return switch(item.phase()) {
            case "build" -> proof.build().workItemId().equals(item.workItemId()) && proof.build().generation()==item.generation()
                    && proof.build().buildAttemptId().equals(result.attemptId()) && item.preparation()!=null
                    && proof.build().preparationBinding().equals(item.preparation().binding())
                    && proof.verificationAttempt()==null && proof.pullRequest()==null && proof.reviewId()==null;
            case "verify" -> previous!=null && proof.equals(previous.verified(result.attemptId()));
            case "deliver" -> previous!=null && previous.verificationAttempt()!=null && proof.pullRequest()!=null
                    && proof.equals(previous.delivered(proof.pullRequest()));
            case "review" -> previous!=null && previous.pullRequest()!=null && proof.reviewId()!=null
                    && proof.equals(previous.reviewed(proof.reviewId()));
            default -> false;
        };
    }

    /** Accept fetched manual evidence through each actual policy branch; never invent an executor result. */
    private WorkItemEvent enterPrepared(Connection c,List<dev.codespire.contract.event.EventEnvelope> history,
                                        WorkItemEvent item,boolean approved,Instant now) throws SQLException,java.io.IOException {
        WorkItemEvent next=enter(item,history.size(),approved,now);
        while("ARTIFACT_ACCEPTED".equals(next.milestone())) {
            store.appendDecision(c,history,next,"artifact:"+next.preparation().binding()+":"+next.phase());
            history=store.history(next.workItemId());
            next=next.decision(next.policyRevision(),next.authority(),next.policy(),nextPhase(next.phase()),next.workflowStatus(),next.reason(),next.milestone(),next.gate(),next.progress());
            next=enter(next,history.size(),false,now);
        }
        return next;
    }

    public Outcome answer(UUID gateId,long expectedVersion,String key,boolean approve,String note,String resolver) {
        if(key==null || key.isBlank() || resolver==null || resolver.isBlank())throw new IllegalArgumentException("A decision identity is required");
        String id=gateItem(gateId);WorkItemEvent item=require(id);
        Observation observed=observe(item);
        return QuarkusTransaction.requiringNew().call(()-> {
            try(Connection c=dataSource.getConnection()) {
                if(!current(c,observed))return new Outcome(503,"authority_changed_during_read",require(id));
                lockItem(c,id);var history=store.history(id);WorkItemEvent current=(WorkItemEvent)history.getLast().payload();
                WorkGate gate=gateFromHistory(id,gateId);
                if(key.equals(gate.answerKey()) && resolver.equals(gate.resolver()) && Objects.equals(note,gate.note())
                        && (approve?"APPROVED":"REJECTED").equals(gate.state()))return new Outcome(200,"answer_already_applied",current);
                if(gate.version()!=expectedVersion || !"OPEN".equals(gate.state()) || current.gate()==null || !gateId.equals(current.gate().id()))
                    return new Outcome(409,"gate_changed",current);
                Instant now=clock.now();
                WorkItemEvent next;int status=200;
                if(!now.isBefore(gate.expiresAt())) { next=expire(current,gate);status=409; }
                else if(observed.evidence().failure()!=null)return new Outcome(503,observed.evidence().failure(),current);
                else if("artifacts_unavailable".equals(observed.artifacts().failure()))return new Outcome(503,"artifacts_unavailable",current);
                else if(observed.artifacts().failure()!=null || !Objects.equals(gate.artifact(),current.preparation()==null?null:current.preparation().binding())) {
                    next=state(current,"awaiting_input","artifacts_changed_requires_new_decision","GATE_SUPERSEDED",gate.resolve("SUPERSEDED",resolver,key,note),current.progress().reserve(false));status=409;
                }
                else if(gate.policyRevision()!=observed.policy().revision() || !gate.authority().equals(authority(observed.source()))
                        || gate.itemRevision()!=history.size() || gate.generation()!=current.generation() || !gate.phase().equals(current.phase())) {
                    next=state(current,"stopped","policy_changed_requires_new_decision","GATE_SUPERSEDED",gate.resolve("SUPERSEDED",resolver,key,note),current.progress().reserve(false));status=409;
                } else {
                    WorkPolicy.Selection selection=select(observed,current);
                    // Immutable profile pins and the policy binding above fix the vectors. Re-observe every current applier.
                    if(!selection.applied().equals(current.policy().applied())) {
                        next=state(current,"stopped","labels_changed_requires_new_decision","GATE_SUPERSEDED",gate.resolve("SUPERSEDED",resolver,key,note),current.progress().reserve(false));
                        store.appendDecision(c,history,next,"gate-labels:"+gateId+":"+key);
                        return new Outcome(409,next.reason(),next);
                    }
                    next=current.decision(observed.policy().revision(),authority(observed.source()),selection,current.phase(),current.workflowStatus(),current.reason(),"GATE_RESOLVED",
                            gate.resolve(approve?"APPROVED":"REJECTED",resolver,key,note),current.progress().reserve(false));
                    if(approve) {
                        store.appendDecision(c,history,next,"gate-answer:"+gateId+":"+key);
                        history=store.history(id);
                        next=enterPrepared(c,history,next,true,now);
                        history=store.history(id);
                    }
                    else next=state(next,"stopped","gate_rejected","GATE_RESOLVED",next.gate(),next.progress());
                }
                store.appendDecision(c,history,next,"gate:"+gateId+":"+key);
                return new Outcome(status,next.reason(),next);
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode gate decision",failure);}
        });
    }

    public void expire(UUID gateId) {
        String id=gateItem(gateId);WorkItemEvent reference=require(id);
        QuarkusTransaction.requiringNew().run(()-> {
            try(Connection c=dataSource.getConnection()) {
                // Projection foreign keys also acquire registry locks. Keep the same order as an answer.
                // Expiry needs local serialization, never a successful remote authority read.
                var source=sources.get(c,reference.sourceId(),true).orElseThrow(NotFoundException::new);
                policies.get(c,source.repositoryId(),true);
                lockItem(c,id);var history=store.history(id);WorkItemEvent item=(WorkItemEvent)history.getLast().payload();
                WorkGate gate=item.gate();
                if(gate==null || !gate.id().equals(gateId) || !"OPEN".equals(gate.state()) || clock.now().isBefore(gate.expiresAt()))return;
                store.appendDecision(c,history,expire(item,gate),"gate-expiry:"+gateId);
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode gate expiry",failure);}
        });
    }

    private WorkItemEvent expire(WorkItemEvent item,WorkGate gate) {
        return state(item,"stopped","gate_expired","WORK_ITEM_REFUSED",gate.resolve("EXPIRED",null,null,null),item.progress().reserve(false));
    }
    public WorkGate gateFromHistory(String id,UUID gateId) {
        return store.history(id).reversed().stream().map(event->((WorkItemEvent)event.payload()).gate())
                .filter(gate->gate!=null && gateId.equals(gate.id())).findFirst().orElseThrow(NotFoundException::new);
    }
    public String gateItem(UUID gate) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT work_item_id FROM work_item_gate WHERE id=?")) {
            ps.setObject(1,gate);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new NotFoundException();return rs.getString(1);}
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private WorkItemEvent require(String id) {
        WorkItemEvent item=store.load(id);if(item==null)throw new NotFoundException();return item;
    }
    private static WorkItemEvent.Authority authority(WorkSourceRegistry.Source source) {
        return new WorkItemEvent.Authority(source.accountId(),source.version().source(),source.version().account(),source.version().repository());
    }
    private static WorkItemEvent state(WorkItemEvent item,String status,String reason,String milestone,WorkGate gate,WorkProgress progress) {
        return item.decision(item.policyRevision(),item.authority(),item.policy(),item.phase(),status,reason,milestone,gate,progress);
    }
    private static String nextPhase(String phase) {
        return switch(phase){case "intake"->"spec";case "spec"->"plan";case "plan"->"build";case "build"->"verify";
            case "verify"->"deliver";case "deliver"->"review";case "review"->"land";case "land"->"complete";default->throw new IllegalArgumentException("Unknown work phase");};
    }

    public Observation observe(UUID sourceId, WorkIssueLocation issue) {
        WorkSourceRegistry.Source source=sources.get(sourceId).orElseThrow(()->new ServiceUnavailableException("Work source missing"));
        WorkPolicyRegistry.Policy policy=policies.get(source.repositoryId());
        WorkEvidence evidence=source.enabled()?WorkEvidence.collect(()->sources.client(source),issue,null)
                :new WorkEvidence(issue,List.of(),"source_unavailable");
        return new Observation(source,policy,evidence);
    }

    public Observation observe(WorkItemEvent item) {
        Observation observed=observe(item.sourceId(),item.issue());
        return new Observation(observed.source(),observed.policy(),observed.evidence(),
                observed.evidence().failure()==null?artifacts.observe(observed.source(),item.preparation()):WorkArtifacts.Evidence.absent(),item.preparation());
    }

    public Outcome prepare(String id,long expectedRevision,WorkPreparation preparation) {
        WorkItemEvent item=require(id);
        Observation observed=observe(item.sourceId(),item.issue());
        WorkArtifacts.Evidence prepared=artifacts.observe(observed.source(),preparation);
        if(observed.evidence().failure()!=null)return new Outcome(503,observed.evidence().failure(),item);
        if(prepared.failure()!=null)return new Outcome("artifacts_unavailable".equals(prepared.failure())?503:409,prepared.failure(),item);
        runAssembly.validate(observed.source(),preparation,prepared);
        return QuarkusTransaction.requiringNew().call(()-> {
            try(Connection c=dataSource.getConnection()) {
                if(!current(c,observed))return new Outcome(503,"authority_changed_during_read",require(id));
                lockItem(c,id);var history=store.history(id);WorkItemEvent current=(WorkItemEvent)history.getLast().payload();
                if(history.size()!=expectedRevision)return new Outcome(409,"work_item_changed",current);
                if(Set.of("active","retired","completed").contains(current.workflowStatus()))return new Outcome(409,"preparation_unavailable",current);
                try(PreparedStatement ps=c.prepareStatement("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=? AND generation=?")) {
                    ps.setString(1,id);ps.setLong(2,current.generation());try(ResultSet rs=ps.executeQuery()) {
                        rs.next();if(rs.getLong(1)>0)return new Outcome(409,"explicit_readmission_required",current);
                    }
                }
                if(current.gate()!=null && "OPEN".equals(current.gate().state())) {
                    current=state(current,"awaiting_input","artifacts_replaced","GATE_SUPERSEDED",current.gate().resolve("SUPERSEDED",preparation.registeredBy(),null,null),current.progress().reserve(false));
                    store.appendDecision(c,history,current,"preparation-replaced:"+UUID.randomUUID());history=store.history(id);
                }
                WorkItemEvent next=current.decision(observed.policy().revision(),authority(observed.source()),select(observed,current),"intake","awaiting_input","artifacts_registered","ARTIFACTS_REGISTERED",null,current.progress().reserve(false)).prepared(preparation);
                store.appendDecision(c,history,next,"preparation:"+UUID.randomUUID());history=store.history(id);
                next=enterPrepared(c,history,next,false,clock.now());history=store.history(id);
                store.appendDecision(c,history,next,"prepared-transition:"+UUID.randomUUID());
                return new Outcome(200,next.reason(),next);
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode preparation",failure);}
        });
    }

    public WorkPolicy.Selection select(Observation observed, WorkItemEvent item) {
        return select(observed.source(),observed.policy(),observed.evidence(),item);
    }

    public WorkPolicy.Selection select(WorkSourceRegistry.Source source, WorkPolicyRegistry.Policy policy,
                                       WorkEvidence evidence, WorkItemEvent item) {
        return WorkPolicy.select(evidence.labels(),source.allowedActors(),policy.mappings(),policy.ceiling(),
                item==null || item.admittedProfile()==null?null:item.admittedModes(),
                item==null || item.admittedProfile()==null?null:item.admittedLimits());
    }

    /** Same lock order as registration and intake: repository/account/source, policy, item. */
    public boolean current(Connection c, Observation observed) throws SQLException {
        WorkSourceRegistry.Source current=sources.get(c,observed.source().id(),true).orElse(null);
        return current!=null && current.version().equals(observed.source().version())
                && current.enabled()==observed.source().enabled()
                && policies.get(c,current.repositoryId(),true).revision()==observed.policy().revision();
    }

    static void lockItem(Connection c,String id) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            ps.setString(1,id);ps.execute();
        }
    }
}

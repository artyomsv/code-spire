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
    @Inject WorkDraftRegistration registration;
    public record Observation(WorkSourceRegistry.Source source, WorkPolicyRegistry.Policy policy, WorkEvidence evidence, WorkArtifacts.Evidence artifacts,WorkPreparation preparation) {
        public Observation(WorkSourceRegistry.Source source,WorkPolicyRegistry.Policy policy,WorkEvidence evidence) {
            this(source,policy,evidence,WorkArtifacts.Evidence.absent(),null);
        }
    }
    /**
     * {@code detail} names which rule refused a registration, for the one caller that must tell an
     * operator what to change. Transitions that have no rule to name keep the two-argument form.
     */
    public record Outcome(int status,String reason,String detail,WorkItemEvent item) {
        public Outcome(int status,String reason,WorkItemEvent item) { this(status,reason,null,item); }
        /** The response body every operator-facing endpoint returns: the reason, and the rule when one refused. */
        public java.util.Map<String,String> body() {
            return detail==null?java.util.Map.of("reason",reason):java.util.Map.of("reason",reason,"detail",detail);
        }
    }
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
            return enter(next,historySize,false,clock.now(),WorkItemLifecycle.EntryMode.ORDINARY);
        return next;
    }

    public Outcome resume(String id,long expectedRevision,boolean readmit) {
        WorkItemEvent item=require(id);
        return advance(id,expectedRevision,observe(item),readmit,null);
    }

    Outcome resumeControlled(String id,long expected,Observation observed,String subject,String note,String head) {
        return QuarkusTransaction.requiringNew().call(()->{
            try(Connection c=dataSource.getConnection()) {
                if(!current(c,observed))return new Outcome(503,"authority_changed_during_read",require(id));
                lockItem(c,id);var history=store.history(id);var item=(WorkItemEvent)history.getLast().payload();
                if(history.size()!=expected)return new Outcome(409,"work_item_changed",item);
                if(!"suspended".equals(item.workflowStatus()))return new Outcome(409,"resume_requires_suspended_item",item);
                // Resume starts a new generation too, so a merged pull request refuses it as readmission does (spec §7.9).
                if(landed(history,item))return new Outcome(409,"readmission_landed",item);
                if(mergeUnconfirmed(c,item))return new Outcome(409,"readmission_merge_unconfirmed",item);
                if(!Objects.equals(item.preparation(),observed.preparation()))return new Outcome(503,"artifacts_changed_during_read",item);
                var p=item.progress();
                var fresh=new WorkProgress(null,null,null,false,null,p.runs(),p.steps(),p.wallSeconds(),p.costMillicents(),p.calls(),p.usageUnknown(),null);
                var recorded=item.control()==null?new WorkControl(null,null,null,null,null,null,null,null):item.control();
                var next=item.decision(observed.policy().revision(),authority(observed.source()),select(observed,item),"intake",
                        "awaiting_input","operator_resumed","OPERATOR_RESUMED",null,fresh).readmit()
                        .controlled(recorded.action(subject,note,head)).withMilestone("OPERATOR_RESUMED");
                String expectedHead=item.progress().execution()!=null && item.progress().execution().pullRequest()!=null
                        ?item.progress().execution().head():item.preparation()==null?null:item.preparation().baseCommit();
                if(!Objects.equals(head,expectedHead) || observed.artifacts().failure()!=null)
                    next=next.prepared(null).withMilestone("OPERATOR_RESUMED");
                store.appendDecision(c,history,next,"operator-resume:"+UUID.randomUUID());history=store.history(id);
                next=enterPrepared(c,history,next,false,clock.now(),WorkItemLifecycle.EntryMode.ORDINARY);history=store.history(id);
                store.appendDecision(c,history,next,"resume-transition:"+UUID.randomUUID());
                return new Outcome(200,next.reason(),next);
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot persist operator resume",failure);}
        });
    }

    /** Called by a bound integration result, never exposed as a dashboard success switch. */
    public Outcome complete(String id,PhaseResult result) {
        WorkItemEvent item=require(id);
        return advance(id,-1,observe(item),false,Objects.requireNonNull(result));
    }

    /**
     * Called only by the verify result consumer (M4). PASSED completes verify; any other outcome opens the
     * result gate. The evidence is this execution verified by that result, so a result for another run or head
     * is refused as phase_evidence_mismatch.
     */
    public Outcome verified(String id,dev.codespire.contract.event.RunVerification.RunWorkVerified result) {
        WorkItemEvent item=require(id);WorkVerification verification=result.verification();
        WorkExecution execution=item.progress().execution();
        if(execution==null || !execution.runId().equals(result.runId()) || !execution.head().equals(verification.head()))
            return new Outcome(409,"phase_evidence_mismatch",item);
        // Passed vouches for the approved checks only: the commands that ran must be the bound ones, in order.
        if(verification.passed() && (item.preparation()==null || !verification.checks().stream().map(WorkVerification.CheckResult::command).toList()
                .equals(item.preparation().verifyCommands())))
            return new Outcome(409,"phase_evidence_mismatch",item);
        // Verify time counts toward the item's wall clock (spec §3.4), so a long verify shortens what a retry may use.
        long wallSeconds=(verification.checks().stream().mapToLong(WorkVerification.CheckResult::wallMillis).sum()+999)/1000;
        PhaseResult phaseResult=new PhaseResult(verification.attemptId(),verification.passed(),wallSeconds,0,0,true,execution.verified(verification));
        Outcome outcome=complete(id,phaseResult);
        // A verify that finished after a hand merge or a close still ran (A6): its time is the item's, not a failure.
        if(outcome.status()==409 && Set.of("item_not_active","attempt_changed").contains(outcome.reason()))return recordLateResult(id,phaseResult);
        return outcome;
    }

    /**
     * A stopped or superseded build, or a verify that finished after the item landed or stopped, still bought usage.
     * Account it once without authorizing a phase.
     */
    Outcome recordLateResult(String id,PhaseResult result) {
        return QuarkusTransaction.requiringNew().call(()->{
            try(Connection c=dataSource.getConnection()) {
                lockItem(c,id);var history=store.history(id);var item=(WorkItemEvent)history.getLast().payload();
                String key="late-build-result:"+result.attemptId();
                if(history.stream().anyMatch(event->key.equals(event.correlationId())))return new Outcome(200,"late_result_already_accounted",item);
                String phase;
                try(PreparedStatement ps=c.prepareStatement("""
                        SELECT a.state,a.phase FROM work_phase_attempt a WHERE a.id=? AND a.work_item_id=?
                          AND (a.phase='build' AND EXISTS (SELECT 1 FROM work_run_effect b WHERE b.attempt_id=a.id)
                            OR a.phase='verify' AND EXISTS (SELECT 1 FROM work_verify_effect v WHERE v.attempt_id=a.id))
                        """)) {
                    ps.setObject(1,result.attemptId());ps.setString(2,id);try(ResultSet rs=ps.executeQuery()) {
                        if(!rs.next())return new Outcome(409,"build_attempt_missing",item);
                        if("completed".equals(rs.getString(1)))return new Outcome(200,"result_already_applied",item);
                        phase=rs.getString(2);
                    }
                }
                if("active".equals(item.workflowStatus()) && Objects.equals(result.attemptId(),item.progress().attemptId()))
                    return new Outcome(409,"build_attempt_active",item);
                WorkProgress progress=item.progress().account(result.wallSeconds(),result.costMillicents(),result.calls());
                if(!result.usageKnown())progress=progress.unknownUsage();
                var next=state(item,item.workflowStatus(),item.reason(),"verify".equals(phase)?"LATE_VERIFY_RESULT":"LATE_BUILD_RESULT",item.gate(),progress);
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
                // Derived here, under the lock, because the decider sees no history (A9).
                WorkItemLifecycle.EntryMode mode=entryMode(history);boolean landFix=mode==WorkItemLifecycle.EntryMode.LAND_FIX;
                if(result!=null) {
                    try(PreparedStatement ps=c.prepareStatement("SELECT state FROM work_phase_attempt WHERE id=? AND work_item_id=?")) {
                        ps.setObject(1,result.attemptId());ps.setString(2,id);try(ResultSet rs=ps.executeQuery()) {
                            if(rs.next() && "completed".equals(rs.getString(1)))return new Outcome(200,"result_already_applied",item);
                        }
                    }
                    if(!Objects.equals(result.attemptId(),item.progress().attemptId()))return new Outcome(409,"attempt_changed",item);
                    if("completed".equals(item.progress().attemptState()))return new Outcome(200,"result_already_applied",item);
                    if(!"active".equals(item.workflowStatus()))return new Outcome(409,"item_not_active",item);
                    if(!validExecution(item,result,mode))return new Outcome(409,"phase_evidence_mismatch",item);
                } else {
                    Outcome refused=readmit?readmitRefusal(c,history,item):recheckRefusal(item);
                    if(refused!=null)return refused;
                }
                WorkPolicy.Selection selection=select(observed,readmit?null:item);
                WorkItemEvent next=item.decision(observed.policy().revision(),authority(observed.source()),selection,item.phase(),item.workflowStatus(),item.reason(),"POLICY_CHECKED",item.gate(),item.progress());
                if(readmit) {
                    if(observed.evidence().failure()!=null)return new Outcome(503,observed.evidence().failure(),item);
                    next=next.readmit();
                    store.appendDecision(c,history,next,"readmit:"+UUID.randomUUID());
                    history=store.history(id);
                }
                if(result!=null && !result.successful() && "verify".equals(item.phase())
                        && result.execution()!=null && result.execution().verification()!=null) {
                    // Failed or unverified checks fail a step, never the item (FR-F20): a human decides.
                    WorkProgress finished=next.progress().finish(result.wallSeconds(),result.costMillicents(),result.calls()).withExecution(result.execution());
                    // Eligibility first, as the passed path decides it in enter(). A tracker that cannot be read
                    // now is retried by the result sweep (503); an item no longer selected, or whose verify is now
                    // off, ends not eligible. Opening the gate regardless threw "Verify is off" and wedged the item
                    // with its attempt started (review of PR #184).
                    if(observed.evidence().failure()!=null)return new Outcome(503,observed.evidence().failure(),item);
                    if(next.policy().selected()==null || "off".equals(next.policy().effective().getOrDefault(WorkPolicy.Phase.VERIFY,"off"))) {
                        next=state(next,"not_eligible",next.policy().selected()==null?next.policy().reason():"verify_off","WORK_ITEM_REFUSED",
                                next.gate(),finished.reserve(false));
                        next=decide(c,new Decision(history,next,"phase-result:"+result.attemptId()));
                        return new Outcome(200,next.reason(),next);
                    }
                    next=WorkItemLifecycle.verifyResultGate(state(next,next.workflowStatus(),next.reason(),next.milestone(),next.gate(),finished),
                            item,history.size(),clock.now(),UUID.randomUUID(),result.execution().verification());
                    store.appendDecision(c,history,next,"phase-result:"+result.attemptId());
                    return new Outcome(200,next.reason(),next);
                }
                if(result!=null) {
                    WorkProgress progress=next.progress().finish(result.wallSeconds(),result.costMillicents(),result.calls());
                    if(result.execution()!=null)progress=progress.withExecution(result.execution());
                    if(!result.usageKnown())progress=progress.unknownUsage();
                    if(!result.successful() && landFix && "build".equals(item.phase())) {
                        // A failed fix run fails the fix, not the item: its pull request is still open (Review Focus 16).
                        next=WorkItemLifecycle.landHanded(state(next,next.workflowStatus(),next.reason(),next.milestone(),next.gate(),progress),"land_fix_run_failed");
                        store.appendDecision(c,history,next,"phase-result:"+result.attemptId());
                        return new Outcome(200,next.reason(),next);
                    }
                    next=state(next,result.successful()?"awaiting_input":"failed",result.successful()?"phase_completed":"phase_failed",
                            result.successful()?"PHASE_COMPLETED":"PHASE_FAILED",next.gate(),progress);
                    next=decide(c,new Decision(history,next,"phase-result:"+result.attemptId()));
                    if(!result.successful())return new Outcome(200,next.reason(),next);
                    history=store.history(id);
                    next=next.decision(next.policyRevision(),next.authority(),next.policy(),nextPhase(item.phase()),next.workflowStatus(),next.reason(),next.milestone(),next.gate(),next.progress());
                }
                if(observed.evidence().failure()!=null)next=state(next,"awaiting_input",observed.evidence().failure(),"AUTHORITY_UNAVAILABLE",next.gate(),next.progress().reserve(false));
                else if(observed.artifacts().failure()!=null)next=state(next,"awaiting_input",observed.artifacts().failure(),"ARTIFACTS_REQUIRED",next.gate(),next.progress().reserve(false));
                else {
                    next=enterPrepared(c,history,next,false,clock.now(),mode);
                    history=store.history(id);
                }
                next=decide(c,new Decision(history,next,"transition:"+UUID.randomUUID()));
                // The rule travels only when the artifacts are what stopped the item; an authority
                // outage checked first owns the reason, and naming a plan rule there would mislead.
                String detail=observed.evidence().failure()==null && observed.artifacts().failure()!=null?observed.artifacts().detail():null;
                return new Outcome(200,next.reason(),detail,next);
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode phase decision",failure);}
        });
    }

    /**
     * Appends a decision, and when it stops a land fix in progress, the hand-off after it (review of slice 7b): the
     * fix's pull request is still open, so a stopped item would refuse readmit and recheck alike and wait for good. The
     * stop stays in history, and the fix's land row is handed with the stop's reason as its note.
     *
     * @return the item as it now stands
     */
    WorkItemEvent decide(Connection c,Decision decision) throws SQLException,java.io.IOException {
        List<dev.codespire.contract.event.EventEnvelope> history=decision.history();
        WorkItemEvent next=decision.next();
        String cause=decision.cause();
        store.appendDecision(c,history,next,cause);
        List<WorkItemEvent> before=facts(history);
        WorkItemEvent handed=WorkItemLifecycle.landFixStopped(before,next);
        if(handed==null)return next;
        store.appendDecision(c,store.history(next.workItemId()),handed,cause+":land-fix-stopped");
        UUID effect=before.reversed().stream().filter(event->"LAND_FIX_REQUESTED".equals(event.milestone())).findFirst().orElseThrow().progress().attemptId();
        try(PreparedStatement ps=c.prepareStatement("UPDATE work_land_effect SET state='handed',reason='land_fix_stopped',note=?,updated_at=now() WHERE effect_id=? AND state='fixing'")) {
            ps.setString(1,next.reason());ps.setObject(2,effect);ps.executeUpdate();
        }
        return handed;
    }
    /** A decision to append: the history it was made from, the item it records and the cause it is recorded under. */
    record Decision(List<dev.codespire.contract.event.EventEnvelope> history,WorkItemEvent next,String cause) {}
    static List<WorkItemEvent> facts(List<dev.codespire.contract.event.EventEnvelope> history) {
        return history.stream().map(event->(WorkItemEvent)event.payload()).toList();
    }
    /** {@link WorkItemLifecycle.EntryMode#LAND_FIX} while this generation's latest land fix is in progress (A9). */
    static WorkItemLifecycle.EntryMode entryMode(List<dev.codespire.contract.event.EventEnvelope> history) {
        return WorkItemLifecycle.landFixInProgress(facts(history))?WorkItemLifecycle.EntryMode.LAND_FIX:WorkItemLifecycle.EntryMode.ORDINARY;
    }
    /**
     * Why a recheck may not continue this item: it stopped or is running, so only readmission may; or land handed it
     * over, where only Retry land goes on, with a note and the verified head (A3). Null when it may.
     */
    private static Outcome recheckRefusal(WorkItemEvent item) {
        if(Set.of("active","waiting_approval","not_eligible","stopped","failed","retired","suspended","completed").contains(item.workflowStatus()))
            return new Outcome(409,"explicit_readmission_required",item);
        if(WorkItemLifecycle.landHandedOver(item))return new Outcome(409,"retry_land_required",item);
        return null;
    }
    /**
     * Why a readmission may not start a new generation of this item, under the item lock; null when it may. The order
     * names the strongest reason first: a merged pull request cannot be built again beside itself (spec §7.9), then
     * anything that still holds the delivered pull request open, then a merge whose outcome is not recorded yet.
     */
    private static Outcome readmitRefusal(Connection c,List<dev.codespire.contract.event.EventEnvelope> history,WorkItemEvent item) throws SQLException {
        WorkItemLifecycle.EntryMode mode=entryMode(history);
        if(Set.of("active","waiting_approval","retired","suspended").contains(item.workflowStatus()))return new Outcome(409,"readmission_unavailable",item);
        if(landed(history,item))return new Outcome(409,"readmission_landed",item);
        // Waiting at land for any reason, a hand-off, a land that cannot merge here or one switched off, leaves the
        // delivered pull request open: a close stops the item, so only a stopped or failed land may build again (A8).
        if("land".equals(item.phase()) && Set.of("awaiting_input","capability_unavailable","not_eligible").contains(item.workflowStatus()))
            return new Outcome(409,"readmission_land_pending",item);
        // A land fix waiting for a person still has its pull request open; readmit would build a second one beside it.
        if(mode==WorkItemLifecycle.EntryMode.LAND_FIX)return new Outcome(409,"readmission_land_pending",item);
        if(mergeUnconfirmed(c,item))return new Outcome(409,"readmission_merge_unconfirmed",item);
        return null;
    }
    interface Locked {
        Outcome apply(Connection c,List<dev.codespire.contract.event.EventEnvelope> history,WorkItemEvent item) throws SQLException,java.io.IOException;
    }
    /** Local serialization only, in the same lock order as an answer; no remote authority read decides a forge fact. */
    Outcome underLock(String id,Locked body) {
        WorkItemEvent reference=require(id);
        return QuarkusTransaction.requiringNew().call(()->{
            try(Connection c=dataSource.getConnection()) {
                WorkSourceRegistry.Source source=sources.get(c,reference.sourceId(),true).orElseThrow(NotFoundException::new);
                policies.get(c,source.repositoryId(),true);
                lockItem(c,id);List<dev.codespire.contract.event.EventEnvelope> history=store.history(id);
                return body.apply(c,history,(WorkItemEvent)history.getLast().payload());
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot record the land outcome",failure);}
        });
    }

    private static boolean landed(List<dev.codespire.contract.event.EventEnvelope> history,WorkItemEvent item) {
        WorkExecution execution=item.progress().execution();
        return execution!=null && execution.pullRequest()!=null && WorkItemLifecycle.landedIn(
                history.stream().map(dev.codespire.contract.event.EventEnvelope::payload).filter(WorkItemEvent.class::isInstance).map(WorkItemEvent.class::cast).toList(),item.generation(),execution.pullRequest().number());
    }

    /**
     * Whether this generation sent a merge whose outcome is not recorded yet (M4 land, review of slice 4). The land
     * effect records it only within this generation, so a resume or readmit now would leave a merge GitHub finished
     * unrecorded and build the merged work again beside it (spec §7.9). It clears once the effect records the merge
     * (then readmission_landed refuses) or proves none happened.
     */
    private static boolean mergeUnconfirmed(Connection c,WorkItemEvent item) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM work_land_effect WHERE work_item_id=? AND generation=? AND state IN ('merging','uncertain','merged','cleaning')")) {
            ps.setString(1,item.workItemId());ps.setLong(2,item.generation());
            try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }
    }

    private WorkItemEvent enter(WorkItemEvent item,long historySize,boolean approved,Instant now,WorkItemLifecycle.EntryMode mode) {
        return WorkItemLifecycle.enter(item,store.load(item.workItemId()),historySize,approved,now,
                capability.available(item,"intake".equals(item.phase())?"spec":item.phase()),UUID.randomUUID(),mode);
    }

    /** Outside a land fix a build proves a fresh checkpoint, which no pull request holds yet. */
    private static boolean builtFresh(WorkExecution proof) {
        return proof.pullRequest()==null;
    }
    /** A land fix's build carries the pull request it fixes. */
    private static boolean builtOnFixedPullRequest(WorkExecution proof,WorkExecution previous) {
        return previous!=null && previous.pullRequest()!=null && previous.pullRequest().equals(proof.pullRequest());
    }

    private static boolean validExecution(WorkItemEvent item,PhaseResult result,WorkItemLifecycle.EntryMode mode) {
        WorkExecution proof=result.execution(),previous=item.progress().execution();
        // Older policy-only histories carry no build execution. They cannot authorize a delivery;
        // the capability/effect checks require its evidence independently.
        if(proof==null)return !result.successful() || previous==null || !Set.of("verify","deliver","review").contains(item.phase());
        return switch(item.phase()) {
            case "build" -> proof.build().workItemId().equals(item.workItemId()) && proof.build().generation()==item.generation()
                    && proof.build().buildAttemptId().equals(result.attemptId()) && item.preparation()!=null
                    && proof.build().preparationBinding().equals(item.preparation().binding())
                    && proof.verificationAttempt()==null && proof.reviewId()==null
                    && (mode==WorkItemLifecycle.EntryMode.LAND_FIX?builtOnFixedPullRequest(proof,previous):builtFresh(proof));
            // The M4 result, never a bare attempt id: a success flag without an outcome verified nothing.
            case "verify" -> previous!=null && proof.verification()!=null && proof.verificationAttempt().equals(result.attemptId())
                    && proof.verification().passed()==result.successful() && proof.equals(previous.verified(proof.verification()));
            case "deliver" -> previous!=null && previous.verificationAttempt()!=null && proof.pullRequest()!=null
                    && proof.equals(previous.delivered(proof.pullRequest()));
            case "review" -> previous!=null && previous.pullRequest()!=null && proof.reviewId()!=null
                    && proof.equals(previous.reviewed(proof.reviewId()));
            default -> false;
        };
    }

    /**
     * Accept fetched manual evidence through each actual policy branch; never invent an executor result.
     *
     * @param mode {@link WorkItemLifecycle.EntryMode#LAND_FIX} while a land fix is in progress, which skips build and deliver gates (A9)
     */
    WorkItemEvent enterPrepared(Connection c,List<dev.codespire.contract.event.EventEnvelope> history,
                                WorkItemEvent item,boolean approved,Instant now,WorkItemLifecycle.EntryMode mode) throws SQLException,java.io.IOException {
        WorkItemEvent next=enter(item,history.size(),approved,now,mode);
        while("ARTIFACT_ACCEPTED".equals(next.milestone())) {
            store.appendDecision(c,history,next,"artifact:"+next.preparation().binding()+":"+next.phase());
            history=store.history(next.workItemId());
            next=next.decision(next.policyRevision(),next.authority(),next.policy(),nextPhase(next.phase()),next.workflowStatus(),next.reason(),next.milestone(),next.gate(),next.progress());
            next=enter(next,history.size(),false,now,mode);
        }
        return next;
    }

    public Outcome answer(UUID gateId,long expectedVersion,String key,boolean approve,String note,String resolver) {
        WorkGate gate=gateFromHistory(gateItem(gateId),gateId);
        return answer(new ResolveGate(gateId,expectedVersion,key,approve,note,resolver,ResolveGate.Channel.DASHBOARD,gate.generation(),gate.artifact()));
    }

    public Outcome answer(ResolveGate command) {
        UUID gateId=command.gateId();long expectedVersion=command.expectedVersion();String key=command.key();
        boolean approve=command.approve();String note=command.note(),resolver=command.resolver(),channel=command.channelName();
        String id=gateItem(gateId);WorkItemEvent item=require(id);
        Observation observed=observe(item);
        return QuarkusTransaction.requiringNew().call(()-> {
            try(Connection c=dataSource.getConnection()) {
                if(!current(c,observed))return new Outcome(503,"authority_changed_during_read",require(id));
                lockItem(c,id);var history=store.history(id);WorkItemEvent current=(WorkItemEvent)history.getLast().payload();
                WorkGate gate=gateFromHistory(id,gateId);
                if(key.equals(gate.answerKey()) && resolver.equals(gate.resolver()) && channel.equals(gate.channel()) && Objects.equals(note,gate.note())
                        && (approve?"APPROVED":"REJECTED").equals(gate.state()))return new Outcome(200,"answer_already_applied",current);
                if(Set.of("suspended","retired").contains(current.workflowStatus()))return new Outcome(409,"item_not_active",current);
                if(command.channel()==ResolveGate.Channel.TRACKER && !observed.source().allowedActors().contains(resolver))
                    return new Outcome(403,"tracker_actor_not_allowed",current);
                if(command.generation()!=gate.generation() || !Objects.equals(command.artifact(),gate.artifact()))return new Outcome(409,"gate_binding_mismatch",current);
                if(command.channel()==ResolveGate.Channel.PR_REVIEW && !"land".equals(gate.phase()))return new Outcome(409,"pr_review_requires_land_gate",current);
                if(gate.version()!=expectedVersion || !"OPEN".equals(gate.state()) || current.gate()==null || !gateId.equals(current.gate().id()))
                    return new Outcome(409,"gate_changed",current);
                Instant now=clock.now();
                WorkItemEvent next;int status=200;
                if(!now.isBefore(gate.expiresAt())) { next=expire(current,gate);status=409; }
                else if(observed.evidence().failure()!=null)return new Outcome(503,observed.evidence().failure(),current);
                else if("artifacts_unavailable".equals(observed.artifacts().failure()))return new Outcome(503,"artifacts_unavailable",current);
                else if(observed.artifacts().failure()!=null || !Objects.equals(gate.artifact(),WorkGate.artifactOf(current))) {
                    next=state(current,"awaiting_input","artifacts_changed_requires_new_decision","GATE_SUPERSEDED",gate.resolve("SUPERSEDED",resolver,channel,key,note),current.progress().reserve(false));
                    store.appendDecision(c,history,next,"gate:"+gateId+":"+key);
                    // Which ticket moved is what the approver has to re-read before a new decision.
                    return new Outcome(409,next.reason(),observed.artifacts().detail(),next);
                }
                else if(gate.policyRevision()!=observed.policy().revision() || !gate.authority().equals(authority(observed.source()))
                        || gate.itemRevision()!=history.size() || gate.generation()!=current.generation() || !gate.phase().equals(current.phase())) {
                    next=state(current,"stopped","policy_changed_requires_new_decision","GATE_SUPERSEDED",gate.resolve("SUPERSEDED",resolver,channel,key,note),current.progress().reserve(false));status=409;
                } else {
                    WorkPolicy.Selection selection=select(observed,current);
                    // Immutable profile pins and the policy binding above fix the vectors. Re-observe every current applier.
                    if(!selection.applied().equals(current.policy().applied())) {
                        next=state(current,"stopped","labels_changed_requires_new_decision","GATE_SUPERSEDED",gate.resolve("SUPERSEDED",resolver,channel,key,note),current.progress().reserve(false));
                        next=decide(c,new Decision(history,next,"gate-labels:"+gateId+":"+key));
                        return new Outcome(409,next.reason(),next);
                    }
                    // A result gate asks about a verify result, not permission to run verify (M4, spec §4.2).
                    boolean verifyResult="verify".equals(gate.phase()) && WorkItemLifecycle.VERIFY_RESULT_REASONS.contains(current.reason());
                    // So does a generated item's spec or plan gate: it asks about the written artifact (M4 slice 3, A6).
                    boolean draftResult=WorkDraftLifecycle.resultGate(current,gate);
                    // Retry build rewinds to build: the human chose to build again, which also answers a build: approve gate.
                    next=current.decision(observed.policy().revision(),authority(observed.source()),selection,verifyResult && approve?"build":current.phase(),current.workflowStatus(),current.reason(),"GATE_RESOLVED",
                            gate.resolve(approve?"APPROVED":"REJECTED",resolver,channel,key,note),current.progress().reserve(false));
                    if(approve) {
                        // A Retry build inside a land fix stays on its pull request (Review Focus 8).
                        WorkItemLifecycle.EntryMode mode=entryMode(history);
                        store.appendDecision(c,history,next,"gate-answer:"+gateId+":"+key);
                        history=store.history(id);
                        if(draftResult){next=WorkDraftLifecycle.approved(next);store.appendDecision(c,history,next,"draft-approved:"+gateId+":"+key);history=store.history(id);}
                        // An approved plan is registered before anything builds (M4 slice 3, A4).
                        next=draftResult?registration.afterApproval(c,history,next):enterPrepared(c,history,next,true,now,mode);
                        history=store.history(id);
                    }
                    else next=state(next,"stopped",verifyResult?"verify_stopped_by_operator":"gate_rejected","GATE_RESOLVED",next.gate(),next.progress());
                }
                next=decide(c,new Decision(history,next,"gate:"+gateId+":"+key));
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
                decide(c,new Decision(history,expire(item,gate),"gate-expiry:"+gateId));
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
    WorkItemEvent require(String id) {
        WorkItemEvent item=store.load(id);if(item==null)throw new NotFoundException();return item;
    }
    static WorkItemEvent.Authority authority(WorkSourceRegistry.Source source) {
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
                observed.evidence().failure()==null?artifacts.observe(observed.source(),item.workItemId(),item.preparation()):WorkArtifacts.Evidence.absent(),item.preparation());
    }

    /**
     * One more condition to test under the item lock, beside revision, status, attempts and gate.
     *
     * <p>The automatic path needs it: its coordinates come from a repository's saved build setup, read
     * before a forge call it then waits on. Comparing that setup's revision anywhere but inside this
     * lock is a comparison of two things that were never true at the same moment.
     *
     * @return the refusal reason, or null to continue
     */
    public interface Precondition { String refuse(Connection c) throws SQLException; }

    public Outcome prepare(String id,long expectedRevision,WorkPreparation preparation) {
        return prepare(id,expectedRevision,preparation,c->null);
    }

    public Outcome prepare(String id,long expectedRevision,WorkPreparation preparation,Precondition precondition) {
        WorkItemEvent item=require(id);
        Observation observed=observe(item.sourceId(),item.issue());
        WorkArtifacts.Evidence prepared=artifacts.observe(observed.source(),id,preparation);
        if(observed.evidence().failure()!=null)return new Outcome(503,observed.evidence().failure(),item);
        if(prepared.failure()!=null)return new Outcome("artifacts_unavailable".equals(prepared.failure())?503:409,prepared.failure(),prepared.detail(),item);
        runAssembly.validate(observed.source(),preparation,prepared);
        return QuarkusTransaction.requiringNew().call(()-> {
            try(Connection c=dataSource.getConnection()) {
                if(!current(c,observed))return new Outcome(503,"authority_changed_during_read",require(id));
                lockItem(c,id);var history=store.history(id);WorkItemEvent current=(WorkItemEvent)history.getLast().payload();
                if(history.size()!=expectedRevision)return new Outcome(409,"work_item_changed",current);
                if(Set.of("active","retired","suspended","completed").contains(current.workflowStatus()))return new Outcome(409,"preparation_unavailable",current);
                try(PreparedStatement ps=c.prepareStatement("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=? AND generation=?")) {
                    ps.setString(1,id);ps.setLong(2,current.generation());try(ResultSet rs=ps.executeQuery()) {
                        rs.next();if(rs.getLong(1)>0)return new Outcome(409,"explicit_readmission_required",current);
                    }
                }
                String refused=precondition.refuse(c);
                if(refused!=null)return new Outcome(409,refused,current);
                if(current.gate()!=null && "OPEN".equals(current.gate().state())) {
                    current=state(current,"awaiting_input","artifacts_replaced","GATE_SUPERSEDED",current.gate().resolve("SUPERSEDED",preparation.registeredBy(),null,null),current.progress().reserve(false));
                    store.appendDecision(c,history,current,"preparation-replaced:"+UUID.randomUUID());history=store.history(id);
                }
                WorkItemEvent next=current.decision(observed.policy().revision(),authority(observed.source()),select(observed,current),"intake","awaiting_input","artifacts_registered","ARTIFACTS_REGISTERED",null,current.progress().reserve(false)).prepared(preparation);
                store.appendDecision(c,history,next,"preparation:"+UUID.randomUUID());history=store.history(id);
                next=enterPrepared(c,history,next,false,clock.now(),WorkItemLifecycle.EntryMode.ORDINARY);history=store.history(id);
                store.appendDecision(c,history,next,"prepared-transition:"+UUID.randomUUID());
                // In THIS transaction, so a registration that rolls back does not leave the screen
                // claiming the item is healthy. Every registration clears it, not only the sweep's:
                // preparation health answers "why has nothing been prepared", and something now is.
                try(PreparedStatement ps=c.prepareStatement(
                        "DELETE FROM work_item_preparation_attempt WHERE work_item_id=? AND generation=?")) {
                    ps.setString(1,id);ps.setLong(2,next.generation());ps.executeUpdate();
                }
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

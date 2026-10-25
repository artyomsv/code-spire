package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.work.WorkControl;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkLanding;
import dev.codespire.contract.work.WorkPolicy;
import dev.codespire.orchestrator.factory.LandSettings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The land transitions (M4 land, spec §7.3-§7.8): what the land effect, the operator's land commands and GitHub's
 * merge and close webhooks decide. Every one appends through the decider under the item lock, in the lock order
 * {@link WorkItemTransitions} keeps, and moves the land row it concerns in the same transaction. The forge reads they
 * need are {@link WorkLandReads}', made before the lock; the ticket after land is {@link WorkLandTicket}'s.
 */
@ApplicationScoped
public class WorkLandTransitions {
    @Inject WorkItemTransitions transitions;
    @Inject WorkItemStore store;
    @Inject DataSource dataSource;
    @Inject WorkClock clock;
    @Inject LandSettings landSettings;
    @Inject WorkLandReads reads;
    @Inject WorkLandTicket ticket;

    /** A merge GitHub reported by webhook: the delivery, the pull request and the merger it named, if any. */
    record HandMerge(String delivery,long pullRequest,String mergedBy) {}
    /** A land fix the land effect requests: its row, the refusal it fixes and the encrypted payload the fix reads. */
    record LandFix(UUID effect,String reason,byte[] payload) {}
    /** Why a land fix hands the item over instead, and the refusal or limit it names. */
    private record HandOff(String reason,String cause) {}

    /**
     * The land effect saw the pull request merged (M4 land, spec §7.3). Recorded once per generation and pull
     * request, whoever saw it first (A2), and recorded on a suspended item too: the merge may have happened
     * after a takeover, and a real merge that is never recorded is worse than a late one (Review Focus 13).
     * The ticket rule's effects are enqueued in the same transaction (spec §7.7, A1).
     */
    WorkItemTransitions.Outcome landed(String id,UUID effect,WorkLanding landing) {
        // Who merged is a forge read, and a forge read never runs under the item lock.
        String handle=WorkLanding.FACTORY.equals(landing.mergedBy())?null:reads.mergerHandle(transitions.require(id),landing.mergedBy());
        return transitions.underLock(id,(c,history,item)->{
            List<WorkItemEvent> facts=WorkItemTransitions.facts(history);
            if(WorkItemLifecycle.landedIn(facts,item.generation(),landing.pullRequest().number()))return new WorkItemTransitions.Outcome(200,"landing_already_recorded",item);
            WorkExecution execution=item.progress().execution();
            boolean attempted=facts.stream().anyMatch(event->event.generation()==item.generation() && "PHASE_STARTED".equals(event.milestone())
                    && "land".equals(event.phase()) && effect.equals(event.progress().attemptId()));
            if(!attempted || !"land".equals(item.phase()) || execution==null || execution.pullRequest()==null
                    || execution.pullRequest().number()!=landing.pullRequest().number())return new WorkItemTransitions.Outcome(409,"work_item_changed",item);
            WorkItemEvent next=WorkItemLifecycle.landed(item,landing);
            store.appendDecision(c,history,next,"land-landed:"+effect);
            if(ticket.enqueue(c,next,handle))noteCloseUnsupported(c,effect);
            return new WorkItemTransitions.Outcome(200,next.reason(),next);
        });
    }
    /** A Jira ticket under the close rule keeps its status; the land row says so, apart from its own note. */
    private static void noteCloseUnsupported(Connection c,UUID effect) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("UPDATE work_land_effect SET ticket_note='ticket_close_unsupported',updated_at=now() WHERE effect_id=?")) {
            ps.setObject(1,effect);ps.executeUpdate();
        }
    }
    /** The land effect hands the item to the operator (spec §7.6), only while this land attempt is the active one. */
    WorkItemTransitions.Outcome landHandOff(String id,UUID effect,String reason) {
        return transitions.underLock(id,(c,history,item)->{
            if(!landAttempt(item,effect))return new WorkItemTransitions.Outcome(409,"work_item_changed",item);
            WorkItemEvent next=WorkItemLifecycle.landHanded(item,reason);
            store.appendDecision(c,history,next,"land-handed:"+effect);
            return new WorkItemTransitions.Outcome(200,next.reason(),next);
        });
    }
    /**
     * Retry land (spec §7.6, A3) under the lock: only an item land handed over, only at the revision the operator saw,
     * and only while the pull request still holds the head that was verified and reviewed, so no gate opens on a head
     * nobody reviewed. The policy and authority are the ones observed now, so the gate binds the current policy
     * revision. The operator, the note and the head read are recorded on the same event.
     *
     * @param head the pull request's head as the forge reported it, read before the lock
     */
    WorkItemTransitions.Outcome retryLandControlled(WorkItemControl.LandRequest request,WorkItemTransitions.Observation observed,String head) {
        String id=request.id();
        return QuarkusTransaction.requiringNew().call(()->{
            try(Connection c=dataSource.getConnection()) {
                if(!transitions.current(c,observed))return new WorkItemTransitions.Outcome(503,"authority_changed_during_read",transitions.require(id));
                WorkItemTransitions.lockItem(c,id);List<EventEnvelope> history=store.history(id);WorkItemEvent item=(WorkItemEvent)history.getLast().payload();
                if(history.size()!=request.expected())return new WorkItemTransitions.Outcome(409,"work_item_changed",item);
                if(!WorkItemLifecycle.landHandedOver(item) || item.progress().execution()==null)return new WorkItemTransitions.Outcome(409,"retry_land_unavailable",item);
                if(!Objects.equals(head,item.progress().execution().head()))return new WorkItemTransitions.Outcome(409,"land_head_moved",item);
                WorkPolicy.Selection selection=transitions.select(observed,item);
                // Land approval is the only land mode that merges; a policy that no longer asks for it has nothing to retry.
                if(selection.selected()==null || !"approve".equals(selection.effective().get(WorkPolicy.Phase.LAND)))return new WorkItemTransitions.Outcome(409,"retry_land_unavailable",item);
                WorkItemEvent observedItem=item.decision(observed.policy().revision(),WorkItemTransitions.authority(observed.source()),selection,item.phase(),item.workflowStatus(),item.reason(),
                        item.milestone(),item.gate(),item.progress()).controlled(recorded(item).action(request.subject(),request.note(),head));
                WorkItemEvent next=WorkItemLifecycle.retryLandGate(observedItem,item,new WorkItemLifecycle.GateOpening(history.size(),clock.now(),UUID.randomUUID()));
                store.appendDecision(c,history,next,"retry-land:"+UUID.randomUUID());
                return new WorkItemTransitions.Outcome(200,next.reason(),next);
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(IOException failure){throw new IllegalStateException("Cannot persist retry land",failure);}
        });
    }
    /**
     * Stop land (spec §7.6): on an item land handed over, on one waiting at land where land cannot merge or is off,
     * which nothing else moves on (review of slice 9), or on a land that has sent no merge yet, which may wait hours
     * for its checks (review of the branch). That last row is refused in this transaction, before its merge call can
     * be claimed. The item ends and the pull request is left as it is.
     */
    WorkItemTransitions.Outcome stopLandControlled(WorkItemControl.LandRequest request) {
        return transitions.underLock(request.id(),(c,history,item)->{
            if(history.size()!=request.expected())return new WorkItemTransitions.Outcome(409,"work_item_changed",item);
            WorkItemEvent stopped;
            if(WorkItemLifecycle.landStoppable(item))stopped=WorkItemLifecycle.landStopped(item);
            else if("land".equals(item.phase()) && "active".equals(item.workflowStatus()) && item.progress().attemptId()!=null
                    && WorkLandEffects.refuseBeforeMerge(c,item.progress().attemptId(),"land_stopped_by_operator"))
                stopped=WorkItemLifecycle.landStoppedBeforeMerge(item);
            else return new WorkItemTransitions.Outcome(409,"retry_land_unavailable",item);
            WorkItemEvent next=stopped.controlled(recorded(item).action(request.subject(),request.note(),null));
            store.appendDecision(c,history,next,"stop-land:"+UUID.randomUUID());
            return new WorkItemTransitions.Outcome(200,next.reason(),next);
        });
    }
    private static WorkControl recorded(WorkItemEvent item) {
        return item.control()==null?new WorkControl(null,null,null,null,null,null,null,null):item.control();
    }
    /**
     * The land effect met a refusal code can fix (spec §7.5): a land fix is requested and build is entered on the same
     * pull request, unless {@link #handOff} says the item is handed over instead, naming what it would have fixed or
     * which limit. The effect row moves in the same transaction, so the fix's payload exists before its build can be
     * dispatched.
     */
    WorkItemTransitions.Outcome landFix(String id,LandFix fix) {
        return transitions.underLock(id,(c,history,item)->{
            if(!landAttempt(item,fix.effect()))return new WorkItemTransitions.Outcome(409,"work_item_changed",item);
            HandOff handOff=handOff(c,history,fix.reason());
            if(handOff!=null) {
                WorkItemEvent next=WorkItemLifecycle.landHanded(item,handOff.reason());
                store.appendDecision(c,history,next,"land-handed:"+fix.effect());
                landEffect(c,fix.effect(),new EffectMove("handed",handOff.reason(),handOff.cause(),null));
                return new WorkItemTransitions.Outcome(200,next.reason(),handOff.cause(),next);
            }
            WorkItemEvent requested=WorkItemLifecycle.landFixRequested(item,fix.reason());
            store.appendDecision(c,history,requested,"land-fix:"+fix.effect());
            landEffect(c,fix.effect(),new EffectMove("fixing",fix.reason(),null,fix.payload()));
            List<EventEnvelope> after=store.history(id);
            WorkItemEvent next=transitions.enterPrepared(c,after,requested,false,clock.now(),WorkItemLifecycle.EntryMode.LAND_FIX);
            next=transitions.decide(c,new WorkItemTransitions.Decision(store.history(id),next,"land-fix-transition:"+fix.effect()));
            return new WorkItemTransitions.Outcome(200,next.reason(),next);
        });
    }
    /**
     * Whether a fix for {@code reason} hands the item over instead: the repository's attempts are spent (a branch behind
     * its base spends none), or a build would cross one of the item's limits. Checked before the fix is requested, so
     * the operator hears which limit, not policy_cap_reached deeper in build. Null when the fix may run.
     */
    private HandOff handOff(Connection c,List<EventEnvelope> history,String reason) throws SQLException {
        WorkItemEvent item=(WorkItemEvent)history.getLast().payload();
        long used=WorkItemTransitions.facts(history).stream().filter(event->event.generation()==item.generation() && "LAND_FIX_REQUESTED".equals(event.milestone())
                && WorkItemLifecycle.LAND_FIX_COUNTED.contains(event.reason())).count();
        long counted=WorkItemLifecycle.LAND_FIX_COUNTED.contains(reason)?1:0;
        if(used+counted>landSettings.get(c,item.repositoryId()).fixAttempts())return new HandOff("land_fix_attempts_exhausted",reason);
        String limit=item.progress().limitReached(item.policy().limits(),"build");
        return limit==null?null:new HandOff("land_item_limit_reached",limit);
    }
    /** Where a land row goes when the decider hands its item over or starts a fix; null fields keep what the row holds. */
    private record EffectMove(String state,String reason,String note,byte[] payload) {}
    private static void landEffect(Connection c,UUID effect,EffectMove move) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("""
                UPDATE work_land_effect SET state=?,reason=?,note=COALESCE(?,note),fix_payload=COALESCE(?,fix_payload),next_check_at=NULL,failing_since=NULL,updated_at=now()
                WHERE effect_id=? AND state IN ('pending','checking','waiting_checks','uncertain')
                """)) {
            ps.setString(1,move.state());ps.setString(2,move.reason());ps.setString(3,move.note());ps.setBytes(4,move.payload());ps.setObject(5,effect);
            ps.executeUpdate();
        }
    }
    /**
     * The reviewer holds a land fix with open blockers (operator, 2026-10-21): the item is handed over with the review,
     * and no agent fixes the findings. Outside a land fix the review step keeps waiting as before.
     */
    WorkItemTransitions.Outcome landFixReviewBlocked(String id,UUID attempt) {
        return transitions.underLock(id,(c,history,item)->{
            if(!"review".equals(item.phase()) || !"active".equals(item.workflowStatus()) || !Objects.equals(attempt,item.progress().attemptId())
                    || !WorkItemLifecycle.landFixInProgress(WorkItemTransitions.facts(history)))return new WorkItemTransitions.Outcome(409,"work_item_changed",item);
            WorkItemEvent next=WorkItemLifecycle.landHanded(item,"land_fix_review_blocked");
            store.appendDecision(c,history,next,"land-fix-review-blocked:"+attempt);
            return new WorkItemTransitions.Outcome(200,next.reason(),next);
        });
    }
    /** The land effect found the pull request closed without a merge (spec §7.8). */
    WorkItemTransitions.Outcome landClosed(String id,UUID effect) {
        return transitions.underLock(id,(c,history,item)->{
            if(!landAttempt(item,effect))return new WorkItemTransitions.Outcome(409,"work_item_changed",item);
            WorkItemEvent next=WorkItemLifecycle.pullRequestClosed(item);
            store.appendDecision(c,history,next,"land-closed:"+effect);
            return new WorkItemTransitions.Outcome(200,next.reason(),next);
        });
    }
    /**
     * A person merged the pull request on GitHub (spec §7.8, A2). The webhook says a merge happened; the merge commit
     * is read back from GitHub before anything is recorded. Recorded once per generation and pull request, whichever
     * observer saw it first, at any phase after the pull request exists and on a suspended item too. The branch is the
     * factory's, so the land effect still deletes it at the merged head ({@link #landingEffect}). The merger is the one
     * GitHub named in the webhook (merged_by, never the sender); the read's when absent.
     */
    WorkItemTransitions.Outcome landedByHuman(String id,HandMerge merge) {
        WorkItemEvent reference=transitions.require(id);
        long pullRequest=merge.pullRequest();
        // Forge reads never run under the item lock.
        PullRequestLander.LandState pr=reads.readPullRequest(reference,pullRequest);
        if(pr==null)return new WorkItemTransitions.Outcome(503,"land_read_failed",reference);
        String merger=merge.mergedBy()==null || merge.mergedBy().isBlank()?pr.mergedBy():merge.mergedBy();
        if(!pr.merged() || pr.mergeCommitSha()==null || !pr.mergeCommitSha().matches("[0-9a-f]{40}") || merger==null || merger.isBlank())
            return new WorkItemTransitions.Outcome(409,"merge_not_confirmed",reference);
        String handle=reads.mergerHandle(reference,merger);
        return transitions.underLock(id,(c,history,item)->{
            if("retired".equals(item.workflowStatus()))return new WorkItemTransitions.Outcome(409,"item_not_active",item);
            if(WorkItemLifecycle.landedIn(WorkItemTransitions.facts(history),item.generation(),pullRequest))return new WorkItemTransitions.Outcome(200,"landing_already_recorded",item);
            WorkExecution execution=item.progress().execution();
            if(execution==null || execution.pullRequest()==null || execution.pullRequest().number()!=pullRequest)return new WorkItemTransitions.Outcome(409,"work_item_changed",item);
            // GitHub's pull request read does not say which method a person used, and the saved setting is no evidence of it.
            WorkLanding landing=new WorkLanding(execution.pullRequest(),pr.mergeCommitSha(),WorkLanding.UNKNOWN_METHOD,merger,clock.now());
            WorkItemEvent next=WorkItemLifecycle.landed(item,landing);
            store.appendDecision(c,history,next,"land-webhook:"+merge.delivery());
            UUID effect=landingEffect(c,next,pr.headSha()==null?execution.head():pr.headSha());
            if(ticket.enqueue(c,next,handle))noteCloseUnsupported(c,effect);
            return new WorkItemTransitions.Outcome(200,next.reason(),next);
        });
    }
    /**
     * The land effect row that deletes the branch after a merge someone else made. A row already holding a merge is
     * the effect's own observation of it, which records the same landing and only advances; a row that sent no merge
     * yet, or whose merge answer was lost, moves to cleaning without a merge; with neither, a row starts at cleaning.
     */
    private static UUID landingEffect(Connection c,WorkItemEvent landed,String mergedHead) throws SQLException {
        WorkLanding landing=landed.progress().execution().landing();
        String item=landed.workItemId();long generation=landed.generation(),number=landing.pullRequest().number();
        try(PreparedStatement ps=c.prepareStatement("SELECT effect_id FROM work_land_effect WHERE work_item_id=? AND generation=? AND pr_number=? AND merge_commit IS NOT NULL")) {
            ps.setString(1,item);ps.setLong(2,generation);ps.setLong(3,number);
            try(ResultSet rs=ps.executeQuery()){if(rs.next())return rs.getObject(1,UUID.class);}
        }
        try(PreparedStatement ps=c.prepareStatement("""
                UPDATE work_land_effect SET state='cleaning',reason=NULL,merge_commit=?,merged_by=?,method=?,merged_head=?,next_check_at=NULL,failing_since=NULL,updated_at=now()
                WHERE effect_id=(SELECT effect_id FROM work_land_effect WHERE work_item_id=? AND generation=? AND pr_number=?
                AND state IN ('pending','checking','waiting_checks','merging','uncertain') ORDER BY created_at DESC LIMIT 1) RETURNING effect_id
                """)) {
            ps.setString(1,landing.mergeCommit());ps.setString(2,landing.mergedBy());ps.setString(3,landing.method());ps.setString(4,mergedHead);
            ps.setString(5,item);ps.setLong(6,generation);ps.setLong(7,number);
            try(ResultSet rs=ps.executeQuery()){if(rs.next())return rs.getObject(1,UUID.class);}
        }
        UUID effect=UUID.randomUUID();
        try(PreparedStatement ps=c.prepareStatement("""
                INSERT INTO work_land_effect(effect_id,work_item_id,generation,item_revision,pr_number,approved_head,state,merge_commit,merged_by,method,merged_head)
                VALUES (?,?,?,(SELECT revision FROM work_item WHERE id=?),?,?,'cleaning',?,?,?,?)
                """)) {
            ps.setObject(1,effect);ps.setString(2,item);ps.setLong(3,generation);ps.setString(4,item);ps.setLong(5,number);ps.setString(6,mergedHead);
            ps.setString(7,landing.mergeCommit());ps.setString(8,landing.mergedBy());ps.setString(9,landing.method());ps.setString(10,mergedHead);
            ps.executeUpdate();
        }
        return effect;
    }
    /**
     * A person closed the pull request on GitHub without merging it (spec §7.8): the item stops, nothing is deleted
     * and the ticket is not told, since nothing landed. A land that has not sent its merge ends with it; one whose
     * merge answer was lost keeps reading, and reads the same close (§7.3).
     */
    WorkItemTransitions.Outcome closedByHuman(String id,String delivery,long pullRequest) {
        return transitions.underLock(id,(c,history,item)->{
            if(Set.of("retired","completed").contains(item.workflowStatus()))return new WorkItemTransitions.Outcome(409,"item_not_active",item);
            if("stopped".equals(item.workflowStatus()) && "pr_closed_by_human".equals(item.reason()))return new WorkItemTransitions.Outcome(200,"close_already_recorded",item);
            WorkExecution execution=item.progress().execution();
            if(execution==null || execution.pullRequest()==null || execution.pullRequest().number()!=pullRequest)return new WorkItemTransitions.Outcome(409,"work_item_changed",item);
            WorkItemEvent next=WorkItemLifecycle.pullRequestClosed(item);
            store.appendDecision(c,history,next,"pr-closed:"+delivery);
            try(PreparedStatement ps=c.prepareStatement(
                    "UPDATE work_land_effect SET state='closed',reason=NULL,next_check_at=NULL,updated_at=now() WHERE work_item_id=? AND generation=? AND pr_number=? AND state IN ('pending','checking','waiting_checks')")) {
                ps.setString(1,id);ps.setLong(2,item.generation());ps.setLong(3,pullRequest);ps.executeUpdate();
            }
            return new WorkItemTransitions.Outcome(200,next.reason(),next);
        });
    }
    private static boolean landAttempt(WorkItemEvent item,UUID effect) {
        return "land".equals(item.phase()) && "active".equals(item.workflowStatus()) && effect.equals(item.progress().attemptId());
    }
}

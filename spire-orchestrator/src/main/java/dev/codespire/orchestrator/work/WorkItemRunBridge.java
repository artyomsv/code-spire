package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunWorkArtifact;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/** Durable result inbox plus idempotent attempt completion; a crash between the two safely redelivers. */
@ApplicationScoped
public class WorkItemRunBridge {
    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;
    @Inject FactoryRunProjection runs;
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;
    @Inject WorkStepSummaries summaries;
    @Inject WorkStepTransitions steps;
    /** The failure an off-chain ready is routed as: no failure cause, so no checkpoint is recorded lost. */
    private static final String OFF_THE_CHAIN="phase_evidence_mismatch";

    /** Validate the immutable dispatch binding before this message can project or charge a run. */
    public boolean acceptsBinding(RunResult result) {
        if(!(result instanceof RunResult.RunWorkReady ready))return true;
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("""
                SELECT 1 FROM work_run_effect WHERE run_id=? AND work_item_id=? AND generation=?
                  AND attempt_id=? AND preparation_binding=?
                """)) {
            ps.setString(1,ready.runId());ps.setString(2,ready.work().workItemId());ps.setLong(3,ready.work().generation());
            ps.setObject(4,ready.work().buildAttemptId());ps.setString(5,ready.work().preparationBinding());
            try(ResultSet rs=ps.executeQuery()){if(rs.next())return true;}
            try(PreparedStatement note=c.prepareStatement("UPDATE work_run_effect SET reason='work_ready_binding_mismatch' WHERE run_id=?")) {
                note.setString(1,ready.runId());note.executeUpdate();
            }
            return false;
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }

    public boolean accept(RunResult result) {
        if(!record(result))return false;
        if(!(result instanceof RunResult.RunStarted))apply(result.runId());
        return true;
    }
    boolean record(RunResult result) {
        // A draft run's artifact is not a work-run result: it has no work_run_effect row (M4 slice 3 gives it its own).
        if(result instanceof RunWorkArtifact)return false;
        if(result instanceof RunResult.RunWorkReady) {
            if(!acceptsBinding(result))return false;
            try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET state='sent',reason=NULL,ready_payload=COALESCE(ready_payload,?) WHERE run_id=?")) {
                ps.setBytes(1,encryption.encrypt(mapper.writeValueAsBytes(result),"work-run-ready:"+result.runId()));
                ps.setString(2,result.runId());return ps.executeUpdate()==1;
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode work readiness",failure);}
        }
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET state='sent',reason=NULL,result_payload=COALESCE(result_payload,?) WHERE run_id=?")) {
            ps.setBytes(1,result instanceof RunResult.RunStarted?null:encryption.encrypt(mapper.writeValueAsBytes(result),"work-run-result:"+result.runId()));
            ps.setString(2,result.runId());return ps.executeUpdate()==1;
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode work run result",failure);}
    }
    @Scheduled(every="${spire.work-run-interval:5s}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void recover() {
        List<String> ids=new ArrayList<>();
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT run_id FROM work_run_effect WHERE (ready_payload IS NOT NULL AND NOT ready_processed) OR (result_payload IS NOT NULL AND NOT result_processed) ORDER BY created_at LIMIT 20");ResultSet rs=ps.executeQuery()) {
            while(rs.next())ids.add(rs.getString(1));
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        for(String id:ids)apply(id);
    }
    public void apply(String runId) {
        String itemId;UUID attempt;RunResult ready,terminal;boolean readyProcessed,resultProcessed;
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT work_item_id,attempt_id,ready_payload,result_payload,ready_processed,result_processed FROM work_run_effect WHERE run_id=?")) {
            ps.setString(1,runId);try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next())return;itemId=rs.getString(1);attempt=rs.getObject(2,UUID.class);
                ready=decode(rs.getBytes(3),"work-run-ready:"+runId);terminal=decode(rs.getBytes(4),"work-run-result:"+runId);
                readyProcessed=rs.getBoolean(5);resultProcessed=rs.getBoolean(6);
            }
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot decode work run result",failure);}
        if(ready instanceof RunResult.RunWorkReady checkpoint && !readyProcessed) {
            // A plan step's ready that names another start than its dispatch required is refused (M4 slice 3, A2), and so
            // is an empty ready anywhere but on a plan step's start (decision 14): the step fails as a failed run does, so
            // a person decides, and its summary is not the step's.
            boolean offTheChain=steps.offTheChain(checkpoint) || steps.refusedEmpty(checkpoint);
            if(offTheChain?!refuseOffTheChain(itemId,attempt,checkpoint):!finishBuild(itemId,attempt,checkpoint,true,checkpoint.activeWallSeconds()))return;
            // The summary (or that there was none, M4 slice 8) commits with the acknowledgement, so a crash before it
            // replays both, and the summary's key makes the replay insert nothing. Recorded under the step the run built.
            try(Connection c=dataSource.getConnection()) {
                c.setAutoCommit(false);
                if(!offTheChain)summaries.recordBuilt(c,checkpoint,store.load(itemId));
                try(PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET ready_processed=true WHERE run_id=?")){ps.setString(1,runId);ps.executeUpdate();}
                c.commit();
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        }
        if(terminal==null || resultProcessed)return;
        if(ready==null) {
            // A successful publication can arrive before readiness. It cannot stand in for the
            // build checkpoint or charge its waiting time as active compute.
            if(terminal instanceof RunResult.RunFinished finished && finished.pushedRef()!=null && !finished.refused() && !finished.agentUnobserved())return;
            var row=runs.find(runId).orElseThrow(()->new IllegalStateException("Associated run is missing"));
            long wall=row.endedAt()==null?0:Math.max(0,Duration.between(row.startedAt(),row.endedAt()).toSeconds());
            if(!finishBuild(itemId,attempt,terminal,false,wall))return;
        }
        // The delivery coordinator observes this durable terminal payload independently. BUILD
        // was already completed by readiness; final publication must never add its usage again.
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET result_processed=true WHERE run_id=?")) {
            ps.setString(1,runId);ps.executeUpdate();
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private RunResult decode(byte[] bytes,String aad) throws java.io.IOException {
        return bytes==null?null:mapper.readValue(encryption.decrypt(bytes,aad),RunResult.class);
    }
    /**
     * Only a land fix's build keeps an execution (M4 land, spec §7.5), so its checkpoint proves the pull request it fixes;
     * every other build carries none. The decider still checks the pull request is the one this generation's fix keeps.
     */
    private dev.codespire.contract.scm.PullRequestRef keptPullRequest(String itemId,UUID attempt) {
        dev.codespire.contract.work.WorkItemEvent item=store.load(itemId);
        dev.codespire.contract.work.WorkExecution execution=item.progress().execution();
        return "build".equals(item.phase()) && attempt.equals(item.progress().attemptId()) && execution!=null?execution.pullRequest():null;
    }
    private boolean finishBuild(String itemId,UUID attempt,RunResult result,boolean success,long wall) {
        var usage=usage(new Spent(attempt,success,wall),result);
        if(result instanceof RunResult.RunWorkReady ready)usage=new WorkItemTransitions.PhaseResult(attempt,success,wall,
                usage.costMillicents(),usage.calls(),usage.usageKnown(),new dev.codespire.contract.work.WorkExecution(
                        ready.runId(),ready.work(),ready.head(),null,null,keptPullRequest(itemId,attempt),null,null));
        // A later plan step's failed run opens the verify gate rather than failing the item (M4 slice 3, decision 7).
        var routed=result instanceof RunResult.RunFailed failed?steps.runFailed(itemId,new WorkStepTransitions.Failure(failed.runId(),failed.cause(),usage)):null;
        return settled(itemId,routed!=null?routed:transitions.complete(itemId,usage),usage);
    }
    /**
     * A ready off the chain fails its step as a failed run would (A2, decision 7), with what the run bought and no
     * execution: the refused head is never put under a gate. Left at build, the item had no gate and its row was read
     * again on every recover pass.
     */
    private boolean refuseOffTheChain(String itemId,UUID attempt,RunResult.RunWorkReady ready) {
        var usage=usage(new Spent(attempt,false,ready.activeWallSeconds()),ready);
        var routed=steps.runFailed(itemId,new WorkStepTransitions.Failure(ready.runId(),OFF_THE_CHAIN,usage));
        return settled(itemId,routed!=null?routed:transitions.complete(itemId,usage),usage);
    }
    private boolean settled(String itemId,WorkItemTransitions.Outcome outcome,WorkItemTransitions.PhaseResult usage) {
        if(outcome.status()==409 && Set.of("attempt_changed","item_not_active").contains(outcome.reason()))
            outcome=transitions.recordLateResult(itemId,usage);
        return outcome.status()==200;
    }
    /** The attempt a run result settles, whether it succeeded, and the wall time it is charged. */
    private record Spent(UUID attempt,boolean success,long wall) {}
    private WorkItemTransitions.PhaseResult usage(Spent spent,RunResult result) {
        UUID attempt=spent.attempt();boolean success=spent.success();long wall=spent.wall();
        var row=runs.find(result.runId()).orElseThrow(()->new IllegalStateException("Associated run is missing"));
        // Reuse M2's cause-and-usage classification. A proven pre-agent failure bought no call;
        // a missing measurement after execution is unknown, never an invented zero.
        // A seat is not billed per token, so a seat run's spending is known to be 0 even when it reported no
        // token counts. Read as unknown, it blocked item #40 for ever after a refused seat (2026-10-08).
        boolean seat=dev.codespire.contract.work.PayWith.SUBSCRIPTION.equals(runs.paidByOf(result.runId()).orElse(null));
        return dev.codespire.orchestrator.factory.RunCharges.nothingWasBought(result)
                ? new WorkItemTransitions.PhaseResult(attempt,success,wall,0,0,true)
                : seat ? new WorkItemTransitions.PhaseResult(attempt,success,wall,0,1,true)
                : new WorkItemTransitions.PhaseResult(attempt,success,wall,
                    row.cost().isKnown()?row.cost().millicents():0,1,row.cost().isKnown());
    }
}

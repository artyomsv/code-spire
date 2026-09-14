package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunResult;
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
            if(!finishBuild(itemId,attempt,checkpoint,true,checkpoint.activeWallSeconds()))return;
            try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET ready_processed=true WHERE run_id=?")) {
                ps.setString(1,runId);ps.executeUpdate();
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
    private boolean finishBuild(String itemId,UUID attempt,RunResult result,boolean success,long wall) {
        var row=runs.find(result.runId()).orElseThrow(()->new IllegalStateException("Associated run is missing"));
        // Reuse M2's cause-and-usage classification. A proven pre-agent failure bought no call;
        // a missing measurement after execution is unknown, never an invented zero.
        var usage=dev.codespire.orchestrator.factory.RunCharges.nothingWasBought(result)
                ? new WorkItemTransitions.PhaseResult(attempt,success,wall,0,0,true)
                : new WorkItemTransitions.PhaseResult(attempt,success,wall,
                    row.cost().isKnown()?row.cost().millicents():0,1,row.cost().isKnown());
        if(result instanceof RunResult.RunWorkReady ready)usage=new WorkItemTransitions.PhaseResult(attempt,success,wall,
                usage.costMillicents(),usage.calls(),usage.usageKnown(),new dev.codespire.contract.work.WorkExecution(
                        ready.runId(),ready.work(),ready.head(),null,null,null));
        var outcome=transitions.complete(itemId,usage);
        if(outcome.status()==409 && Set.of("attempt_changed","item_not_active").contains(outcome.reason()))
            outcome=transitions.recordLateBuild(itemId,usage);
        return outcome.status()==200;
    }
}

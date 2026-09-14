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

    public boolean accept(RunResult result) {
        if(!record(result))return false;
        if(!(result instanceof RunResult.RunStarted))apply(result.runId());
        return true;
    }
    boolean record(RunResult result) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET state='sent',reason=NULL,result_payload=COALESCE(result_payload,?) WHERE run_id=?")) {
            ps.setBytes(1,result instanceof RunResult.RunStarted?null:encryption.encrypt(mapper.writeValueAsBytes(result),"work-run-result:"+result.runId()));
            ps.setString(2,result.runId());return ps.executeUpdate()==1;
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode work run result",failure);}
    }
    @Scheduled(every="${spire.work-run-interval:5s}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void recover() {
        List<String> ids=new ArrayList<>();
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT run_id FROM work_run_effect WHERE result_payload IS NOT NULL AND NOT result_processed ORDER BY created_at LIMIT 20");ResultSet rs=ps.executeQuery()) {
            while(rs.next())ids.add(rs.getString(1));
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        for(String id:ids)apply(id);
    }
    public void apply(String runId) {
        String itemId;UUID attempt;RunResult result;
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT work_item_id,attempt_id,result_payload FROM work_run_effect WHERE run_id=? AND result_payload IS NOT NULL AND NOT result_processed")) {
            ps.setString(1,runId);try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next())return;itemId=rs.getString(1);attempt=rs.getObject(2,UUID.class);
                result=mapper.readValue(encryption.decrypt(rs.getBytes(3),"work-run-result:"+runId),RunResult.class);
            }
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot decode work run result",failure);}
        var row=runs.find(runId).orElseThrow(()->new IllegalStateException("Associated run is missing"));
        boolean success=result instanceof RunResult.RunFinished finished && !finished.agentUnobserved() && !finished.refused();
        long wall=row.endedAt()==null?0:Math.max(0,Duration.between(row.startedAt(),row.endedAt()).toSeconds());
        // Reuse M2's cause-and-usage classification. A proven pre-agent failure bought no call;
        // a missing measurement after execution is unknown, never an invented zero.
        var usage=dev.codespire.orchestrator.factory.RunCharges.nothingWasBought(result)
                ? new WorkItemTransitions.PhaseResult(attempt,success,wall,0,0,true)
                : new WorkItemTransitions.PhaseResult(attempt,success,wall,
                    row.cost().isKnown()?row.cost().millicents():0,1,row.cost().isKnown());
        var outcome=transitions.complete(itemId,usage);
        if(outcome.status()!=200)return;
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET result_processed=true WHERE run_id=?")) {
            ps.setString(1,runId);ps.executeUpdate();
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
}

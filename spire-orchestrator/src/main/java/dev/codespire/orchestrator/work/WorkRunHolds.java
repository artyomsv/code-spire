package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.orchestrator.factory.RunCommandEmitter;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Repeat idempotent revocations until a terminal run is observed, including after a lost control delivery. */
@ApplicationScoped
public class WorkRunHolds {
    @Inject DataSource dataSource;
    @Inject RunCommandEmitter emitter;
    @Scheduled(every="${spire.work-delivery-interval:5s}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void drain() {
        List<RunCommand.HoldWorkRun> pending=new ArrayList<>();
        try(Connection c=dataSource.getConnection();var ps=c.prepareStatement("""
                SELECT h.* FROM work_run_hold_outbox h JOIN factory_run r ON r.run_id=h.run_id
                WHERE r.ended_at IS NULL ORDER BY h.last_sent_at NULLS FIRST,h.created_at LIMIT 20
                """);var rs=ps.executeQuery()) {
            while(rs.next())pending.add(new RunCommand.HoldWorkRun(rs.getString("run_id"),new WorkRunBinding(
                    rs.getString("work_item_id"),rs.getLong("generation"),rs.getObject("build_attempt_id",UUID.class),rs.getString("preparation_binding"))));
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        for(var hold:pending) {
            try {emitter.control(hold);}
            catch(RuntimeException unavailable){continue;}
            try(Connection c=dataSource.getConnection();var ps=c.prepareStatement("UPDATE work_run_hold_outbox SET last_sent_at=now() WHERE run_id=?")) {
                ps.setString(1,hold.runId());ps.executeUpdate();
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        }
    }
}

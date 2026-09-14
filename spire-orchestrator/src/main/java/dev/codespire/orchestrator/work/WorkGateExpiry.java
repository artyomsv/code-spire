package dev.codespire.orchestrator.work;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@ApplicationScoped
public class WorkGateExpiry {
    @Inject DataSource dataSource;
    @Inject WorkClock clock;
    @Inject WorkItemTransitions transitions;
    @Scheduled(every="${spire.work-gate-expiry-interval:5s}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        List<UUID> ids=new ArrayList<>();
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT id FROM work_item_gate WHERE state='OPEN' AND expires_at<=? ORDER BY expires_at,id LIMIT 100")) {
            ps.setTimestamp(1,Timestamp.from(clock.now()));try(ResultSet rs=ps.executeQuery()){while(rs.next())ids.add(rs.getObject(1,UUID.class));}
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        ids.forEach(transitions::expire);
    }
}

package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.WorkSourceDelivery;
import dev.codespire.worksource.*;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Poll authenticated tracker comments where signed delivery is unavailable, without retaining prose. */
@ApplicationScoped
public class WorkActivityPoller {
    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkSourceRegistry sources;
    @Inject WorkGateChannels gates;
    @Scheduled(every="${spire.work-activity-interval:30s}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void drain() {
        List<String> ids=new ArrayList<>();
        try(var c=dataSource.getConnection();var ps=c.prepareStatement("SELECT id FROM work_item WHERE workflow_status NOT IN ('retired','completed') ORDER BY activity_polled_at NULLS FIRST,id LIMIT 20");var rs=ps.executeQuery()) {
            while(rs.next())ids.add(rs.getString(1));
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        for(String id:ids)try {poll(id);}catch(RuntimeException unavailable){
            var item=store.load(id);if(item!=null)sources.health(item.sourceId(),"activity_poll_unavailable");
        }
        finally {try(var c=dataSource.getConnection();var ps=c.prepareStatement("UPDATE work_item SET activity_polled_at=now() WHERE id=?")) {
            ps.setString(1,id);ps.executeUpdate();
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}}
    }
    public void poll(String id) {
        var history=store.history(id);if(history.isEmpty())return;
        var item=store.load(id);var source=sources.get(item.sourceId()).orElseThrow();if(!source.enabled())return;
        var client=sources.client(source);if(!client.pollsActivities())return;
        String cursor=null;Set<String> seen=new HashSet<>();
        for(int page=0;page<20;page++) {
            var result=client.activities(item.issue(),cursor);
            for(var activity:result.items()) {
                if(activity.occurredAt()==null || activity.occurredAt().isBefore(history.getFirst().occurredAt()))continue;
                gates.tracker(source,new WorkSourceDelivery(source.repositoryId(),source.id(),null,0,source.scm().providerType(),source.forgeOrigin(),
                        source.repository(),"poll:"+activity.id(),new WorkSourceSignal(source.scope(),item.issue(),null,activity)));
            }
            cursor=result.nextCursor();if(cursor==null)return;
            if(!seen.add(cursor))throw new WorkSourceException("Tracker activity pagination did not progress");
        }
        throw new WorkSourceException("Tracker activity pagination exceeded its bound");
    }
}

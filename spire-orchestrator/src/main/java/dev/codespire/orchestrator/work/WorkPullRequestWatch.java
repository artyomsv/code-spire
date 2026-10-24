package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkItemEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * Hand merges and closes of a delivered pull request that no FACTORY webhook reports (live land acceptance, issue #64
 * on spire-test: the repository had only a REVIEWER webhook, and the item waited at its land gate after the merge).
 * Two ways in, both ending in the same land observation as the factory's own {@code merged}/{@code closed} activity
 * ({@link WorkGateChannels}): the REVIEWER webhook's close, and a periodic read of the pull request for an item no
 * webhook reached at all.
 *
 * <p>An item with a live land effect is left to it: the effect reads the pull request itself and records the factory's
 * own merge as the factory's. A merge the factory account made is never recorded as a person's. One landing per
 * generation and pull request is the transitions' rule, so a merge seen by two observers lands once.
 */
@ApplicationScoped
public class WorkPullRequestWatch {
    private static final Logger LOG = Logger.getLogger(WorkPullRequestWatch.class);
    /** Pull requests read per periodic check, so a large backlog never floods the forge; the next check goes on from there. */
    static final int BATCH = 20;
    static final String LAND_EFFECT_OWNS = "land_effect_owns";
    static final String RECORDED_MACHINE = "recorded_machine";

    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkSourceRegistry sources;
    @Inject WorkItemControl control;
    @Inject WorkLandReads reads;
    @Inject WorkLandTransitions landTransitions;
    @Inject WorkLandObservations observations;
    /** The last item id the periodic check read; the next one starts after it and wraps around. */
    private final AtomicReference<String> cursor = new AtomicReference<>("");

    /**
     * The REVIEWER webhook reported the close of a pull request (spec §7.8). It names no sender and no merger, so the
     * merger is read from the pull request; a read that fails is kept for the land sweep, as the factory webhook's is.
     */
    public void reviewerClosed(UUID repository,IntegrationEvent.PullRequestClosed closed,String delivery) {
        String key="reviewer:"+delivery;
        for(WorkItemEvent item:deliveredBy(repository,closed)) {
            String id=item.workItemId();
            if(received(id,key))continue;
            if(liveEffect(item)){receipt(id,key,LAND_EFFECT_OWNS);continue;}
            if(closed.reason()==IntegrationEvent.CloseReason.MERGED)merged(item,key);
            else closedUnmerged(item,key);
        }
    }

    /** No webhook at all: reads the pull request of each delivered item land does not own, a bounded batch per check. */
    @Scheduled(every="${spire.work-pr-state-interval:2m}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void check() {
        for(String id:batch()) {
            try { converge(id); }
            catch(RuntimeException failure) {
                // The next check reads this item again; one unreadable item must not starve the rest of the batch.
                LOG.warnf(failure,"Work item %s: the periodic pull request read failed",id);
            }
        }
    }

    private void merged(WorkItemEvent item,String key) {
        String id=item.workItemId();long number=item.progress().execution().pullRequest().number();
        PullRequestLander.LandState pr=reads.readPullRequest(item,number);
        if(pr!=null && pr.merged() && machine(item,pr.mergedBy())){receipt(id,key,RECORDED_MACHINE);return;}
        WorkLandTransitions.HandMerge merge=new WorkLandTransitions.HandMerge(key,number,null);
        WorkItemTransitions.Outcome result=pr==null?new WorkItemTransitions.Outcome(503,"land_read_failed",null,item):landTransitions.landedByHuman(id,merge);
        if(WorkLandObservations.unsettled(result)){observations.observe(item,merge,result.reason());return;}
        receipt(id,key,"landed:"+result.reason());
    }

    private void closedUnmerged(WorkItemEvent item,String key) {
        String id=item.workItemId();
        WorkItemTransitions.Outcome result=landTransitions.closedByHuman(id,key,item.progress().execution().pullRequest().number());
        if(result.status()>=500)throw new jakarta.ws.rs.ServiceUnavailableException(result.reason());
        receipt(id,key,"closed:"+result.reason());
    }

    private void converge(String id) {
        WorkItemEvent item=store.load(id);
        WorkExecution execution=item==null?null:item.progress().execution();
        if(execution==null || execution.pullRequest()==null || liveEffect(item))return;
        long number=execution.pullRequest().number();
        PullRequestLander.LandState pr=reads.readPullRequest(item,number);
        // Unreadable now: the next check reads it again.
        if(pr==null)return;
        String key="pr-state:"+item.generation()+":"+number;
        WorkItemTransitions.Outcome result;
        if(pr.merged() && !machine(item,pr.mergedBy()))result=landTransitions.landedByHuman(id,new WorkLandTransitions.HandMerge(key,number,null));
        else if(!pr.merged() && "closed".equals(pr.state()))result=landTransitions.closedByHuman(id,key,number);
        else return;
        LOG.infof("Work item %s: pull request #%d was %s by hand with no webhook reporting it (%d %s)",id,number,pr.merged()?"merged":"closed",result.status(),result.reason());
    }

    /** The open items of this repository whose delivered pull request is the one closed. */
    private List<WorkItemEvent> deliveredBy(UUID repository,IntegrationEvent.PullRequestClosed closed) {
        List<WorkItemEvent> delivered=new ArrayList<>();
        for(String id:ids("SELECT id FROM work_item WHERE repository_id=? AND workflow_status NOT IN ('retired','completed') ORDER BY id",repository)) {
            WorkItemEvent item=store.load(id);
            WorkExecution execution=item==null?null:item.progress().execution();
            if(execution==null || execution.pullRequest()==null || execution.pullRequest().number()!=closed.prId())continue;
            if(sources.get(item.sourceId()).map(source->source.repository().equals(closed.repo())).orElse(false))delivered.add(item);
        }
        return delivered;
    }

    /**
     * The next items after the cursor that may hold a delivered pull request, wrapping to the start when the end is
     * reached. A stopped item ended on purpose and is not read for ever; a webhook still reaches it.
     */
    private List<String> batch() {
        String sql="SELECT id FROM work_item WHERE workflow_status NOT IN ('retired','completed','stopped') AND phase IN ('deliver','review','land')"
                +" AND id > ? ORDER BY id LIMIT "+BATCH;
        String after=cursor.get();
        List<String> ids=ids(sql,after);
        if(ids.size()<BATCH && !after.isEmpty())wrap(ids,ids(sql,""));
        cursor.set(ids.size()<BATCH?"":ids.getLast());
        return ids;
    }
    /** Fills the batch from the start of the list, without reading an item twice in one check. */
    private static void wrap(List<String> ids,List<String> fromStart) {
        for(String id:fromStart) {
            if(ids.size()>=BATCH)return;
            if(!ids.contains(id))ids.add(id);
        }
    }

    private List<String> ids(String sql,Object parameter) {
        List<String> ids=new ArrayList<>();
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement(sql)) {
            ps.setObject(1,parameter);
            try(ResultSet rs=ps.executeQuery()){while(rs.next())ids.add(rs.getString(1));}
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        return ids;
    }

    /** Whether a land effect of this generation still drains; it reads the pull request itself. */
    private boolean liveEffect(WorkItemEvent item) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement(
                "SELECT 1 FROM work_land_effect WHERE work_item_id=? AND generation=? AND state=ANY(?) LIMIT 1")) {
            ps.setString(1,item.workItemId());ps.setLong(2,item.generation());ps.setArray(3,c.createArrayOf("text",WorkLandEffects.LIVE.toArray()));
            try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }

    /** The recorded or the current bot: the factory's own merge belongs to its land effect (A2). */
    private boolean machine(WorkItemEvent item,String actor) {
        return item.control()!=null && item.control().machine(actor) || control.currentMachine(item.repositoryId(),actor);
    }

    private boolean received(String id,String delivery) {
        try(Connection c=dataSource.getConnection()){return WorkItemControl.received(c,id,"scm",delivery);}
        catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private void receipt(String id,String delivery,String outcome) {
        try(Connection c=dataSource.getConnection()){WorkItemControl.receipt(c,id,"scm",delivery,outcome);}
        catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
}

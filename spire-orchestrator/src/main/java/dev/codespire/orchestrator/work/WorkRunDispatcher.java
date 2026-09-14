package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** The committed claim precedes broker dispatch. An uncertain outcome never authorizes an automatic resend. */
@ApplicationScoped
public class WorkRunDispatcher {
    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;
    @Inject WorkRunAssembly assembly;
    @Inject FactoryRunProjection runs;
    @Inject WorkRunTransport transport;

    @Scheduled(every="${spire.work-run-interval:5s}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void drain() {
        List<UUID> pending=new ArrayList<>();
        // M2's admin dispatch-resolution endpoint records a proven never-ran outcome as DISPATCH_FAILED.
        // Only that explicit proof (or a definite broker miss) may re-arm an uncertain item claim.
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect e SET state='pending',reason='dispatch_resolved_never_ran' FROM factory_run r WHERE e.run_id=r.run_id AND e.state='uncertain' AND e.result_payload IS NULL AND r.status='failed' AND r.failure_cause='DISPATCH_FAILED'")) {
            ps.executeUpdate();
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT attempt_id FROM work_run_effect WHERE state='pending' ORDER BY created_at LIMIT 20");ResultSet rs=ps.executeQuery()) {
            while(rs.next())pending.add(rs.getObject(1,UUID.class));
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        for(UUID id:pending)dispatch(id);
    }
    public void dispatch(UUID attempt) {
        String itemId;
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT work_item_id FROM work_run_effect WHERE attempt_id=?")) {
            ps.setObject(1,attempt);try(ResultSet rs=ps.executeQuery()){if(!rs.next())return;itemId=rs.getString(1);}
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        WorkItemEvent read=store.load(itemId);
        var observed=transitions.observe(read);
        WorkRunAssembly.Prepared prepared=QuarkusTransaction.requiringNew().call(()->claim(itemId,attempt,read,observed));
        if(prepared==null)return;
        RunLaunch.Outcome outcome;
        try { outcome=transport.dispatch(prepared.command()); }
        catch(RuntimeException unknown) { outcome=new RunLaunch.Uncertain(new IllegalStateException("Item dispatch outcome is unknown",unknown)); }
        String state=switch(outcome){case RunLaunch.Dispatched ignored->"sent";case RunLaunch.DefiniteMiss ignored->"pending";case RunLaunch.Uncertain ignored->"uncertain";};
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET state=?,reason=? WHERE attempt_id=? AND state='uncertain'")) {
            ps.setString(1,state);ps.setString(2,"uncertain".equals(state)?"dispatch_uncertain":null);ps.setObject(3,attempt);ps.executeUpdate();
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private WorkRunAssembly.Prepared claim(String id,UUID attempt,WorkItemEvent read,WorkItemTransitions.Observation observed) {
        try(Connection c=dataSource.getConnection()) {
            if(!transitions.current(c,observed))return null;
            WorkItemTransitions.lockItem(c,id);
            var history=store.history(id);WorkItemEvent item=(WorkItemEvent)history.getLast().payload();
            try(PreparedStatement ps=c.prepareStatement("SELECT state,generation FROM work_run_effect WHERE attempt_id=? FOR UPDATE")) {
                ps.setObject(1,attempt);try(ResultSet rs=ps.executeQuery()) {
                    if(!rs.next() || !"pending".equals(rs.getString(1)))return null;
                    if(rs.getLong(2)!=item.generation() || !attempt.equals(item.progress().attemptId()) || !"active".equals(item.workflowStatus()) || !"build".equals(item.phase())) {
                        refuse(c,attempt,"work_item_changed");return null;
                    }
                }
            }
            if(!Objects.equals(read.preparation(),item.preparation()))return null;
            // Intake may have observed a newer policy while this effect was pending. Compare against
            // the decision which admitted this attempt, not that newer observational projection.
            WorkItemEvent admitted=history.stream().map(event->(WorkItemEvent)event.payload())
                    .filter(event->"PHASE_STARTED".equals(event.milestone()) && attempt.equals(event.progress().attemptId()))
                    .findFirst().orElseThrow(()->new IllegalStateException("Build attempt has no admission decision"));
            String refusal=observed.evidence().failure()!=null?observed.evidence().failure():observed.artifacts().failure();
            if(refusal==null && (!transitions.select(observed,item).equals(admitted.policy()) || observed.policy().revision()!=admitted.policyRevision()))refusal="policy_changed_before_dispatch";
            if(refusal==null && !transport.available())refusal="publication_hold_unavailable";
            if(refusal!=null) { stop(c,history,item,attempt,refusal);return null; }
            WorkRunAssembly.Prepared prepared;
            try { prepared=assembly.assemble(c,observed.source(),item,observed.artifacts()); }
            catch(IllegalStateException | jakarta.ws.rs.BadRequestException unavailable) { stop(c,history,item,attempt,"build_configuration_unavailable");return null; }
            if(!runs.queued(prepared.row(),"Prepared work item "+item.issue().issueKey(),item.repositoryId())) {
                refuse(c,attempt,"run_already_recorded");return null;
            }
            try(PreparedStatement ps=c.prepareStatement("UPDATE factory_run SET work_item_id=?,work_phase_attempt_id=? WHERE run_id=? AND (work_item_id IS NULL OR (work_item_id=? AND work_phase_attempt_id=?))")) {
                ps.setString(1,id);ps.setObject(2,attempt);ps.setString(3,prepared.command().runId());
                ps.setString(4,id);ps.setObject(5,attempt);
                if(ps.executeUpdate()!=1)throw new IllegalStateException("Run association already exists");
            }
            try(PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET state='uncertain',reason='dispatch_claimed',run_id=?,preparation_binding=? WHERE attempt_id=?")) {
                ps.setString(1,prepared.command().runId());ps.setString(2,prepared.command().work().preparationBinding());ps.setObject(3,attempt);ps.executeUpdate();
            }
            String factoryActor=null,reviewerActor=null;
            try(var ps=c.prepareStatement("SELECT a.role,p.bot_account_id FROM repository_account a JOIN scm_provider p ON p.id=a.account_id WHERE a.repository_id=? AND a.role IN ('FACTORY','REVIEWER') FOR SHARE OF p")) {
                ps.setObject(1,item.repositoryId());try(var rs=ps.executeQuery()) {
                    while(rs.next())if("FACTORY".equals(rs.getString(1)))factoryActor=rs.getString(2);else reviewerActor=rs.getString(2);
                }
            }
            WorkControl control=new WorkControl(prepared.command().runId(),prepared.command().work(),prepared.command().execution().branch(),factoryActor,reviewerActor,null,null,null,
                    item.control()==null?null:item.control().trackerActor());
            store.appendDecision(c,history,item.controlled(control).withMilestone("BUILD_DISPATCH_CLAIMED"),"build-claim:"+attempt);
            return prepared;
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot persist build dispatch",failure);}
    }
    private void stop(Connection c,List<dev.codespire.contract.event.EventEnvelope> history,WorkItemEvent item,UUID attempt,String reason) throws SQLException,java.io.IOException {
        refuse(c,attempt,reason);
        store.appendDecision(c,history,WorkItemLifecycle.state(item,"stopped",reason,"WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false)),"build-refused:"+attempt);
    }
    private void refuse(Connection c,UUID attempt,String reason) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET state='refused',reason=? WHERE attempt_id=? AND state='pending'")) {
            ps.setString(1,reason);ps.setObject(2,attempt);ps.executeUpdate();
        }
    }
}

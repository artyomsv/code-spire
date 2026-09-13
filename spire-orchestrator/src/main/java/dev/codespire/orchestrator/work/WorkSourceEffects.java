package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.work.*;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.worksource.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** One durable write claim. Uncertain outcomes are inspected, never blindly sent a second time. */
@ApplicationScoped
public class WorkSourceEffects {
    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject WorkItemTransitions transitions;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;
    public enum Kind { COMMENT, TRANSITION }
    public record Intent(Kind kind, String value) {}
    public record Status(UUID id, String state, String remoteId, String reason) {}
    private record Pending(UUID id, String item, long generation, long revision, String phase, Intent intent, String state) {}

    /** Called by a phase decision's transaction; it is not an HTTP permission bypass. */
    @Transactional
    public void enqueue(UUID id, String itemId, long revision, Kind kind, String value) {
        if (id == null || kind == null || value == null || value.isBlank()) throw new IllegalArgumentException("A tracker effect identity and value are required");
        WorkItemEvent item = store.load(itemId);
        if (item == null) throw new IllegalArgumentException("The work item does not exist");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO work_tracker_outbox(effect_id,work_item_id,generation,item_revision,phase,payload)
                SELECT ?,id,generation,revision,phase,? FROM work_item WHERE id=? AND revision=?
                ON CONFLICT(effect_id) DO NOTHING
                """)) {
            ps.setObject(1,id); ps.setBytes(2,encryption.encrypt(mapper.writeValueAsBytes(new Intent(kind,value)),aad(id)));
            ps.setString(3,itemId); ps.setLong(4,revision);
            if (ps.executeUpdate() == 0) {
                Pending existing = load(id);
                if (existing == null || !existing.item().equals(itemId) || existing.revision() != revision || !existing.intent().equals(new Intent(kind,value)))
                    throw new IllegalArgumentException("Effect identity or work-item revision changed");
            }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Cannot encode tracker effect",invalid); }
    }

    @Scheduled(every="${spire.work-effects-interval:2s}", concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        List<UUID> ids = new ArrayList<>();
        try (Connection c=dataSource.getConnection(); PreparedStatement ps=c.prepareStatement("SELECT effect_id FROM work_tracker_outbox WHERE state IN ('pending','uncertain') AND (checked_at IS NULL OR checked_at < now()-interval '10 seconds') ORDER BY created_at LIMIT 10"); ResultSet rs=ps.executeQuery()) {
            while(rs.next())ids.add(rs.getObject(1,UUID.class));
        } catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        ids.forEach(this::dispatch);
    }

    public boolean dispatch(UUID id) {
        Pending effect=load(id);
        if(effect==null || !Set.of("pending","uncertain").contains(effect.state()))return false;
        WorkItemEvent item=store.load(effect.item());
        if(item==null)return false;
        WorkSourceRegistry.Source source=sources.get(item.sourceId()).orElseThrow();
        if(!source.enabled()) { mark(id,effect.state(),effect.state().equals("pending")?"refused":"uncertain",null,"source_unavailable");return false; }
        if(effect.state().equals("uncertain")) return recover(effect,item,source);

        WorkPolicyRegistry.Policy policy=policies.get(source.repositoryId());
        WorkEvidence evidence=WorkEvidence.collect(()->sources.client(source),item.issue(),null);
        WorkItemTransitions.Observation observed=new WorkItemTransitions.Observation(source,policy,evidence);
        WorkPolicy.Selection selection=transitions.select(observed,item);
        String mode=selection.effective().get(WorkPolicy.Phase.valueOf(item.phase().toUpperCase(Locale.ROOT)));
        if(evidence.failure()!=null || selection.selected()==null || mode==null || mode.equals("off")
                || effect.intent().kind()==Kind.TRANSITION && (!mode.equals("auto") || !item.workflowStatus().equals("active"))) {
            mark(id,"pending","refused",null,"current_policy_refused");return false;
        }
        boolean claimed=QuarkusTransaction.requiringNew().call(()-> {
            try(Connection c=dataSource.getConnection()) {
                if(!transitions.current(c,observed))return false;
                try(PreparedStatement ps=c.prepareStatement("SELECT generation,revision,phase FROM work_item WHERE id=? FOR UPDATE")) {
                    ps.setString(1,effect.item());try(ResultSet rs=ps.executeQuery()) {
                        if(!rs.next() || rs.getLong(1)!=effect.generation() || rs.getLong(2)!=effect.revision() || !rs.getString(3).equals(effect.phase())) {
                            mark(id,"pending","refused",null,"work_item_changed");return false;
                        }
                    }
                }
                // Commit uncertainty BEFORE the HTTP call: a killed process cannot later resend it.
                return mark(id,"pending","uncertain",null,"write_outcome_unknown");
            }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        });
        if(!claimed)return false;
        try {
            String remote=bounded(()-> {
                WorkSource client=sources.client(source);
                if(effect.intent().kind()==Kind.COMMENT)return client.comment(item.issue(),effect.intent().value(),id.toString());
                client.transition(item.issue(),effect.intent().value(),id.toString());return "transition-confirmed";
            });
            return mark(id,"uncertain","sent",remote,null);
        }catch(RuntimeException unknown){mark(id,"uncertain","uncertain",null,"write_outcome_unknown");return false;}
    }

    private boolean recover(Pending effect,WorkItemEvent item,WorkSourceRegistry.Source source) {
        try {
            String found=bounded(()-> {
                WorkSource client=sources.client(source);
                if(effect.intent().kind()==Kind.COMMENT)return client.findComment(item.issue(),effect.intent().value(),effect.id().toString());
                return client.transitionApplied(item.issue(),effect.intent().value(),effect.id().toString())?"transition-confirmed":null;
            });
            if(found!=null)return mark(effect.id(),"uncertain","sent",found,null);
        }catch(RuntimeException unavailable){ /* Preserve uncertainty; absence is not proof a timed-out write failed. */ }
        mark(effect.id(),"uncertain","uncertain",null,"write_outcome_unknown");return false;
    }
    public Status status(UUID id) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT state,remote_id,reason FROM work_tracker_outbox WHERE effect_id=?")) {
            ps.setObject(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?new Status(id,rs.getString(1),rs.getString(2),rs.getString(3)):null;}
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private Pending load(UUID id) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT work_item_id,generation,item_revision,phase,payload,state FROM work_tracker_outbox WHERE effect_id=?")) {
            ps.setObject(1,id);try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next())return null;
                return new Pending(id,rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getString(4),mapper.readValue(encryption.decrypt(rs.getBytes(5),aad(id)),Intent.class),rs.getString(6));
            }
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        catch(java.io.IOException invalid){throw new IllegalStateException("Cannot decode tracker effect",invalid);}
    }
    private boolean mark(UUID id,String expected,String state,String remote,String reason) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_tracker_outbox SET state=?,remote_id=?,reason=?,checked_at=now() WHERE effect_id=? AND state=?")) {
            ps.setString(1,state);ps.setString(2,remote);ps.setString(3,reason);ps.setObject(4,id);ps.setString(5,expected);return ps.executeUpdate()==1;
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private static String aad(UUID id){return "work-tracker-effect:"+id;}
    private static <T> T bounded(Callable<T> call) {
        FutureTask<T> task=new FutureTask<>(call);Thread.ofVirtual().start(task);
        try{return task.get(20,TimeUnit.SECONDS);}catch(Exception failure){task.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new WorkSourceException("Tracker effect outcome is unknown");}
    }
}

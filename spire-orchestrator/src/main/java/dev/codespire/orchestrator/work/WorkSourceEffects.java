package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.work.*;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.factory.LandSettings;
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

/**
 * One durable write claim. Uncertain outcomes are inspected, never blindly sent a second time.
 *
 * <p>Two classes of row (M4 land, spec §7.7, A1). A {@code phase} row is bound to the revision and phase that
 * enqueued it and to that phase's current policy mode. A {@code post_land} row is the ticket after land: enqueued
 * with WORK_ITEM_LANDED and sent whatever the item's phase or status afterwards, checked against the repository's
 * ticket rule instead of a phase mode.
 */
@ApplicationScoped
public class WorkSourceEffects {
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(WorkSourceEffects.class);
    static final String PHASE="phase", POST_LAND="post_land";
    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject WorkItemTransitions transitions;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;
    @Inject LandSettings landSettings;
    public enum Kind { COMMENT, TRANSITION }
    public record Intent(Kind kind, String value) {}
    public record Status(UUID id, String state, String remoteId, String reason) {}
    private record Pending(UUID id, String item, long generation, long revision, String phase, Intent intent, String state, String effectClass) {}
    /** Checks the item under its row lock before the claim commits; false leaves the row unclaimed. */
    private interface Claim { boolean holds(Connection c) throws SQLException; }

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
            ps.setObject(1,id); ps.setBytes(2,encode(id,new Intent(kind,value)));
            ps.setString(3,itemId); ps.setLong(4,revision);
            if (ps.executeUpdate() == 0) {
                Pending existing = load(id);
                if (existing == null || !existing.item().equals(itemId) || existing.revision() != revision || !existing.intent().equals(new Intent(kind,value)))
                    throw new IllegalArgumentException("Effect identity or work-item revision changed");
            }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    /**
     * The ticket after land, on the caller's connection: inside the transaction that appends WORK_ITEM_LANDED and
     * after it, so the projection row already holds that revision. Ids are {@link #postLandId derived}, so a replayed
     * landing finds its row instead of adding one.
     */
    void enqueuePostLand(Connection c, UUID id, String itemId, long revision, Kind kind, String value) throws SQLException {
        if (id == null || kind == null || value == null || value.isBlank()) throw new IllegalArgumentException("A post-land effect identity and value are required");
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO work_tracker_outbox(effect_id,work_item_id,generation,item_revision,phase,payload,effect_class)
                SELECT ?,id,generation,revision,phase,?,'post_land' FROM work_item WHERE id=? AND revision=?
                ON CONFLICT(effect_id) DO NOTHING
                """)) {
            ps.setObject(1,id); ps.setBytes(2,encode(id,new Intent(kind,value))); ps.setString(3,itemId); ps.setLong(4,revision);
            if (ps.executeUpdate() == 1) return;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM work_tracker_outbox WHERE effect_id=? AND work_item_id=? AND effect_class='post_land'")) {
            ps.setObject(1,id); ps.setString(2,itemId);
            try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalStateException("Work item "+itemId+" is not at revision "+revision+"; the post-land effect was not enqueued"); }
        }
    }

    /** One post-land row per item, generation and kind: a landing is recorded once per generation (spec §7.9). */
    static UUID postLandId(String itemId, long generation, Kind kind) {
        return UUID.nameUUIDFromBytes(("post-land:"+itemId+":"+generation+":"+kind).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The ticket comment after land (spec §7.7). {@code handle} is the merger's observed handle, null when it could
     * not be resolved: a stable id means nothing to a reader, so it is never printed. Under the close rule the close
     * follows, and the sentence that the status is unchanged would contradict it.
     */
    static String landedComment(WorkLanding landing, String handle, boolean closing) {
        String merger = WorkLanding.FACTORY.equals(landing.mergedBy()) ? "the factory" : handle == null || handle.isBlank() ? "a person" : handle;
        String link = landing.pullRequest().url() == null ? "pull request #"+landing.pullRequest().number() : landing.pullRequest().url();
        String merged = "Merged by "+merger+": "+link+" ("+landing.mergeCommit().substring(0,7)+").";
        return closing ? merged : merged+" The ticket status is unchanged so testing can follow.";
    }

    /** The transition that closes a ticket of this source type; the per-type words live in the composition root. */
    static Optional<String> closeTransition(WorkSourceType type) {
        return dev.codespire.orchestrator.provider.ProviderClients.closeTransition(type);
    }

    @Scheduled(every="${spire.work-effects-interval:2s}", concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        List<UUID> ids = new ArrayList<>();
        try (Connection c=dataSource.getConnection(); PreparedStatement ps=c.prepareStatement("SELECT effect_id FROM work_tracker_outbox WHERE state IN ('pending','uncertain') AND (checked_at IS NULL OR checked_at < now()-interval '10 seconds') ORDER BY created_at LIMIT 10"); ResultSet rs=ps.executeQuery()) {
            while(rs.next())ids.add(rs.getObject(1,UUID.class));
        } catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        for(UUID id:ids) {
            try { dispatch(id); }
            catch(RuntimeException failure) {
                // One row the outbox cannot handle must not hold back the rows behind it (A1). It stays as it was and
                // is tried again after the recheck interval. The message may quote a remote body, so only the type is logged.
                LOG.warnf("Tracker effect %s could not be dispatched (%s); it is tried again later",id,failure.getClass().getSimpleName());
                touch(id);
            }
        }
    }

    public boolean dispatch(UUID id) {
        Pending effect=load(id);
        if(effect==null || !Set.of("pending","uncertain").contains(effect.state()))return false;
        WorkItemEvent item=store.load(effect.item());
        if(item==null)return false;
        WorkSourceRegistry.Source source=sources.get(item.sourceId()).orElseThrow();
        if(!source.enabled()) { mark(id,effect.state(),effect.state().equals("pending")?"refused":"uncertain",null,"source_unavailable");return false; }
        if(effect.state().equals("uncertain")) return recover(effect,item,source);
        if(POST_LAND.equals(effect.effectClass())) return dispatchPostLand(effect,item,source);
        Optional<WorkPolicy.Phase> phase=phase(item.phase());
        // A phase row on an item past its phases (complete) has no mode to check; it is refused, never thrown on every pass.

        WorkPolicyRegistry.Policy policy=policies.get(source.repositoryId());
        WorkEvidence evidence=WorkEvidence.collect(()->sources.client(source),item.issue(),null);
        WorkItemTransitions.Observation observed=new WorkItemTransitions.Observation(source,policy,evidence);
        WorkPolicy.Selection selection=transitions.select(observed,item);
        String mode=selection.effective().get(phase.get());
        if(evidence.failure()!=null || selection.selected()==null || mode==null || mode.equals("off")
                || effect.intent().kind()==Kind.TRANSITION && (!mode.equals("auto") || !item.workflowStatus().equals("active"))) {
            mark(id,"pending","refused",null,"current_policy_refused");return false;
        }
        return send(effect,item,source,c-> {
            if(!transitions.current(c,observed))return false;
            try(PreparedStatement ps=c.prepareStatement("SELECT generation,revision,phase FROM work_item WHERE id=? FOR UPDATE")) {
                ps.setString(1,effect.item());try(ResultSet rs=ps.executeQuery()) {
                    if(!rs.next() || rs.getLong(1)!=effect.generation() || rs.getLong(2)!=effect.revision() || !rs.getString(3).equals(effect.phase())) {
                        mark(id,"pending","refused",null,"work_item_changed");return false;
                    }
                }
            }
            return true;
        });
    }

    /**
     * The ticket after land (A1): no policy or phase mode, and whatever the item's phase now, since it is complete by
     * then. Only the generation must still match. A close is sent while the current ticket rule is still close, and
     * only once the comment that says why is sent; a refused or missing comment refuses it {@code comment_not_sent}.
     */
    private boolean dispatchPostLand(Pending effect,WorkItemEvent item,WorkSourceRegistry.Source source) {
        if(item.generation()!=effect.generation()) { mark(effect.id(),"pending","refused",null,"work_item_changed");return false; }
        if(effect.intent().kind()==Kind.TRANSITION) {
            if(landSettings.get(source.repositoryId()).ticketRule()!=LandSettings.TicketRule.CLOSE) {
                mark(effect.id(),"pending","refused",null,"ticket_rule_changed");return false;
            }
            Status comment=status(postLandId(effect.item(),effect.generation(),Kind.COMMENT));
            // A comment that will never be sent ends the close too; one still in flight defers it like any other row.
            if(comment==null || "refused".equals(comment.state())) { mark(effect.id(),"pending","refused",null,"comment_not_sent");return false; }
            if(!"sent".equals(comment.state())) { touch(effect.id());return false; }
        }
        return send(effect,item,source,c-> {
            try(PreparedStatement ps=c.prepareStatement("SELECT generation FROM work_item WHERE id=? FOR UPDATE")) {
                ps.setString(1,effect.item());try(ResultSet rs=ps.executeQuery()) {
                    if(!rs.next() || rs.getLong(1)!=effect.generation()) { mark(effect.id(),"pending","refused",null,"work_item_changed");return false; }
                }
            }
            return true;
        });
    }

    private boolean send(Pending effect,WorkItemEvent item,WorkSourceRegistry.Source source,Claim claim) {
        UUID id=effect.id();
        boolean claimed=QuarkusTransaction.requiringNew().call(()-> {
            try(Connection c=dataSource.getConnection()) {
                if(!claim.holds(c))return false;
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
    /** The policy phase a stored name stands for; empty for a name that is none, such as complete. */
    private static Optional<WorkPolicy.Phase> phase(String name) {
        return Arrays.stream(WorkPolicy.Phase.values()).filter(phase->phase.name().equalsIgnoreCase(name)).findFirst();
    }
    private Pending load(UUID id) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT work_item_id,generation,item_revision,phase,payload,state,effect_class FROM work_tracker_outbox WHERE effect_id=?")) {
            ps.setObject(1,id);try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next())return null;
                return new Pending(id,rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getString(4),mapper.readValue(encryption.decrypt(rs.getBytes(5),aad(id)),Intent.class),rs.getString(6),rs.getString(7));
            }
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        catch(java.io.IOException invalid){throw new IllegalStateException("Cannot decode tracker effect",invalid);}
    }
    private boolean mark(UUID id,String expected,String state,String remote,String reason) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_tracker_outbox SET state=?,remote_id=?,reason=?,checked_at=now() WHERE effect_id=? AND state=?")) {
            ps.setString(1,state);ps.setString(2,remote);ps.setString(3,reason);ps.setObject(4,id);ps.setString(5,expected);return ps.executeUpdate()==1;
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    /** Defers a row that failed, so it waits out the recheck interval instead of leading every batch. */
    private void touch(UUID id) {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE work_tracker_outbox SET checked_at=now() WHERE effect_id=?")) {
            ps.setObject(1,id);ps.executeUpdate();
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
    }
    private byte[] encode(UUID id,Intent intent) {
        try { return encryption.encrypt(mapper.writeValueAsBytes(intent),aad(id)); }
        catch(java.io.IOException invalid){throw new IllegalStateException("Cannot encode tracker effect",invalid);}
    }
    private static String aad(UUID id){return "work-tracker-effect:"+id;}
    private static <T> T bounded(Callable<T> call) {
        FutureTask<T> task=new FutureTask<>(call);Thread.ofVirtual().start(task);
        try{return task.get(20,TimeUnit.SECONDS);}catch(Exception failure){task.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new WorkSourceException("Tracker effect outcome is unknown");}
    }
}

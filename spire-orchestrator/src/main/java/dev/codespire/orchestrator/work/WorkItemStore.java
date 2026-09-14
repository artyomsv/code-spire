package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.WorkItemIds;
import dev.codespire.contract.port.EventStore;
import dev.codespire.contract.work.*;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** One JTA transaction enlists every JDBC connection, including the existing encrypted event store. */
@ApplicationScoped
public class WorkItemStore {
    @Inject DataSource dataSource;
    @Inject EventStore events;
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject WorkItemTransitions transitions;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;

    public List<EventEnvelope> history(String id) {
        if (!id.matches("work-v1-[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid work item id");
        List<EventEnvelope> history = events.load(id);
        if (history.stream().anyMatch(event -> !(event.payload() instanceof WorkItemEvent)))
            throw new IllegalStateException("Non-work event in a work stream");
        return history;
    }
    public WorkItemEvent load(String id) {
        return WorkItemLifecycle.fold(history(id).stream().map(event -> (WorkItemEvent) event.payload()).toList());
    }

    @Transactional
    public String reconcile(WorkSourceRegistry.Source observed, long policyRevision, WorkEvidence evidence, String deliveryId) {
        if (evidence.failure() != null) { sources.health(observed.id(), evidence.failure()); return null; }
        String id = WorkItemIds.of(observed.scm(), observed.forgeOrigin(), observed.repository(), evidence.issue().ref());
        try (Connection c = dataSource.getConnection()) {
            WorkSourceRegistry.Source current = sources.get(c, observed.id(), true).orElse(null);
            if (current == null || !current.enabled() || !current.version().equals(observed.version())) {
                sources.health(observed.id(), "source_changed_during_read"); return null;
            }
            if (!current.origin().equals(evidence.issue().ref().origin()) || current.type() != evidence.issue().ref().type()
                    || !current.projectId().equals(evidence.issue().ref().projectId()))
                throw new IllegalArgumentException("Work evidence is outside the registered source");
            WorkPolicyRegistry.Policy policy = policies.get(c, current.repositoryId(), true);
            if (policy.revision() != policyRevision) { sources.health(current.id(), "policy_changed_during_read"); return null; }
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                ps.setString(1, id); ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO work_item_delivery(work_item_id,delivery_id) VALUES (?,?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, id); ps.setString(2, deliveryId);
                if (ps.executeUpdate() == 0) return id;
            }
            List<EventEnvelope> history = history(id);
            WorkItemEvent previous = WorkItemLifecycle.fold(history.stream().map(event -> (WorkItemEvent) event.payload()).toList());
            WorkPolicy.Selection selection = transitions.select(current, policy, evidence, previous);
            WorkItemEvent.Authority authority = new WorkItemEvent.Authority(current.accountId(), current.version().source(),
                    current.version().account(), current.version().repository());
            WorkItemEvent next = WorkItemLifecycle.reconcile(id, current.id(), current.repositoryId(), evidence.issue(), policy.revision(), authority, selection, previous);
            next = transitions.admission(next, history.size());
            if (next.equals(previous)) { sources.health(current.id(), "healthy"); return id; }
            appendDecision(c, history, next, deliveryId);
            sources.health(current.id(), "healthy");
            return id;
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot encode work event", failure); }
    }

    void appendDecision(Connection c, List<EventEnvelope> history, WorkItemEvent next, String cause) throws SQLException, java.io.IOException {
        WorkItemEvent previous = history.isEmpty() ? null : (WorkItemEvent) history.getLast().payload();
        List<EventEnvelope> appended = new ArrayList<>();
        if (WorkItemLifecycle.clampChanged(previous, next))
            appended.add(EventEnvelope.domain(next.workItemId(), history.size(), cause, null, next.withMilestone("POLICY_CLAMPED")));
        appended.add(EventEnvelope.domain(next.workItemId(), history.size() + appended.size(), cause, null, next));
        events.append(next.workItemId(), history.size(), appended);
        for (EventEnvelope envelope : appended) {
            project(c, (WorkItemEvent) envelope.payload(), envelope);
            projectExecution(c, (WorkItemEvent) envelope.payload());
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO work_item_outbox(effect_id,work_item_id,effect_type,payload) VALUES (?,?,'WORK_EVENT',?)")) {
                ps.setObject(1, envelope.eventId()); ps.setString(2, next.workItemId());
                ps.setBytes(3, encryption.encrypt(mapper.writeValueAsBytes(envelope), "work-effect:" + envelope.eventId())); ps.executeUpdate();
            }
        }
    }

    private void project(Connection c, WorkItemEvent item, EventEnvelope envelope) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO work_item(id,source_id,repository_id,issue_id,issue_key,tracker_url,generation,profile_id,profile_version,
                policy_revision,phase,workflow_status,reason,revision,updated_at,policy_clamped) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET issue_key=excluded.issue_key,tracker_url=excluded.tracker_url,
                profile_id=excluded.profile_id,profile_version=excluded.profile_version,policy_revision=excluded.policy_revision,
                generation=excluded.generation,phase=excluded.phase,workflow_status=excluded.workflow_status,reason=excluded.reason,revision=excluded.revision,updated_at=excluded.updated_at,
                policy_clamped=CASE WHEN excluded.policy_clamped THEN true WHEN ? THEN work_item.policy_clamped ELSE false END
                """)) {
            ps.setString(1, item.workItemId()); ps.setObject(2, item.sourceId()); ps.setObject(3, item.repositoryId());
            ps.setString(4, item.issue().ref().issueId()); ps.setString(5, item.issue().issueKey()); ps.setString(6, item.issue().link().toString());
            ps.setLong(7, item.generation());
            ps.setObject(8, item.admittedProfile() == null ? null : item.admittedProfile().id());
            ps.setObject(9, item.admittedProfile() == null ? null : item.admittedProfile().version());
            ps.setLong(10, item.policyRevision()); ps.setString(11, item.phase()); ps.setString(12, item.workflowStatus());
            ps.setString(13, item.reason()); ps.setLong(14, envelope.sequence() + 1); ps.setTimestamp(15, Timestamp.from(envelope.occurredAt()));
            ps.setBoolean(16, "POLICY_CLAMPED".equals(item.milestone())); ps.setBoolean(17, "policy_clamped".equals(item.policy().reason()));
            ps.executeUpdate();
        }
    }

    private void projectExecution(Connection c,WorkItemEvent item) throws SQLException {
        if(Set.of("WORK_ITEM_REFUSED","GATE_SUPERSEDED","READMITTED","PHASE_FAILED","ARTIFACTS_REQUIRED").contains(item.milestone())) {
            try(PreparedStatement ps=c.prepareStatement("UPDATE work_tracker_outbox SET state='refused',reason='work_item_invalidated' WHERE work_item_id=? AND state='pending'")) {
                ps.setString(1,item.workItemId());ps.executeUpdate();
            }
            try(PreparedStatement ps=c.prepareStatement("UPDATE work_run_effect SET state='refused',reason='work_item_invalidated' WHERE work_item_id=? AND state='pending'")) {
                ps.setString(1,item.workItemId());ps.executeUpdate();
            }
        }
        try(PreparedStatement ps=c.prepareStatement("UPDATE work_item SET slot_reserved=? WHERE id=?")) {
            ps.setBoolean(1,item.progress().reserved());ps.setString(2,item.workItemId());ps.executeUpdate();
        }
        WorkGate gate=item.gate();
        if(gate!=null)try(PreparedStatement ps=c.prepareStatement("""
                INSERT INTO work_item_gate(id,work_item_id,generation,phase,state,version,item_revision,policy_revision,artifact,
                opened_at,expires_at,resolver,channel,answer_key,note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET state=excluded.state,version=excluded.version,resolver=excluded.resolver,
                channel=excluded.channel,answer_key=excluded.answer_key,note=excluded.note
                """)) {
            ps.setObject(1,gate.id());ps.setString(2,item.workItemId());ps.setLong(3,gate.generation());ps.setString(4,gate.phase());
            ps.setString(5,gate.state());ps.setLong(6,gate.version());ps.setLong(7,gate.itemRevision());ps.setLong(8,gate.policyRevision());
            ps.setString(9,gate.artifact());ps.setTimestamp(10,Timestamp.from(gate.openedAt()));ps.setTimestamp(11,Timestamp.from(gate.expiresAt()));
            ps.setString(12,gate.resolver());ps.setString(13,gate.channel());ps.setString(14,gate.answerKey());
            ps.setBytes(15,gate.note()==null?null:encryption.encrypt(gate.note().getBytes(java.nio.charset.StandardCharsets.UTF_8),"work-gate:"+gate.id()));ps.executeUpdate();
        }
        WorkProgress progress=item.progress();
        if(progress.attemptId()!=null)try(PreparedStatement ps=c.prepareStatement("""
                INSERT INTO work_phase_attempt(id,work_item_id,generation,phase,state,started_at) VALUES (?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET state=excluded.state
                """)) {
            ps.setObject(1,progress.attemptId());ps.setString(2,item.workItemId());ps.setLong(3,item.generation());
            ps.setString(4,progress.attemptPhase());ps.setString(5,progress.attemptState());ps.setTimestamp(6,Timestamp.from(progress.startedAt()));ps.executeUpdate();
        }
        if(item.preparation()!=null && "build".equals(item.phase()) && "PHASE_STARTED".equals(item.milestone()))
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO work_run_effect(attempt_id,work_item_id,generation,state) VALUES (?,?,?,'pending') ON CONFLICT DO NOTHING")) {
                ps.setObject(1,progress.attemptId());ps.setString(2,item.workItemId());ps.setLong(3,item.generation());ps.executeUpdate();
            }
        if("deliver".equals(item.phase()) && "PHASE_STARTED".equals(item.milestone()) && progress.execution()!=null)
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO work_delivery_effect(attempt_id,work_item_id,generation,run_id,state) VALUES (?,?,?,?,'pending') ON CONFLICT DO NOTHING")) {
                ps.setObject(1,progress.attemptId());ps.setString(2,item.workItemId());ps.setLong(3,item.generation());ps.setString(4,progress.execution().runId());ps.executeUpdate();
            }
    }
}

package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.orchestrator.factory.BuildDefaults;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunLaunch;
import dev.codespire.orchestrator.factory.WorkDraftAssembly;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Dispatches the pending specification runs of generated items (M4 slice 3, A4), the pattern of {@link WorkRunDispatcher}:
 * the claim commits {@code pending → uncertain} before the broker send, a definite miss re-arms the row, and an
 * uncertain send is never repeated. Only M2's explicit dispatch resolution, which records a proven never-ran run as
 * DISPATCH_FAILED, re-arms an uncertain row. The ticket and the base head are read before the claim, outside any lock;
 * the claim re-checks the item, the authority, the policy the attempt was admitted under and the setup under the lock.
 *
 * <p>Plan rows wait for slice 7, which writes the plan prompt; only SPEC rows are drained here.
 */
@ApplicationScoped
public class WorkPrepareDispatcher {
    private static final Set<String> STARTS = Set.of("PHASE_STARTED", "DRAFT_RETRY_REQUESTED");

    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;
    @Inject WorkDraftAssembly assembly;
    @Inject FactoryRunProjection runs;
    @Inject WorkPrepareTransport transport;
    @Inject WorkPrepareReads reads;
    @Inject BuildDefaults defaults;

    /** What a claim works from: the effect row, the authority observed, and the remote reads. */
    private record Claim(WorkPrepareReads.Effect effect, WorkItemTransitions.Observation observed, WorkPrepareReads.Reads remote) {}

    @Scheduled(every = "${spire.work-prepare-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void drain() {
        execute("""
                UPDATE work_prepare_effect e SET state='pending',reason='dispatch_resolved_never_ran',updated_at=now() FROM factory_run r
                 WHERE e.run_id=r.run_id AND e.state='uncertain' AND e.result_payload IS NULL AND r.status='failed' AND r.failure_cause='DISPATCH_FAILED'
                """);
        List<UUID> pending = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT attempt_id FROM work_prepare_effect WHERE state='pending' AND kind='SPEC' ORDER BY created_at LIMIT 20");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) pending.add(rs.getObject(1, UUID.class));
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        for (UUID attempt : pending) dispatch(attempt);
    }

    public void dispatch(UUID attempt) {
        WorkPrepareReads.Effect effect = reads.effect(attempt);
        if (effect == null) return;
        WorkItemEvent read = store.load(effect.itemId());
        WorkItemTransitions.Observation observed = transitions.observe(read.sourceId(), read.issue());
        WorkPrepareReads.Reads remote = reads.read(read, observed);
        WorkDraftAssembly.Prepared prepared = QuarkusTransaction.requiringNew().call(() -> claim(new Claim(effect, observed, remote)));
        if (prepared == null) return;
        RunLaunch.Outcome outcome;
        try { outcome = transport.dispatch(prepared.command()); }
        catch (RuntimeException unknown) { outcome = new RunLaunch.Uncertain(new IllegalStateException("Draft dispatch outcome is unknown", unknown)); }
        String state = switch (outcome) {
            case RunLaunch.Dispatched ignored -> "sent";
            case RunLaunch.DefiniteMiss ignored -> "pending";
            case RunLaunch.Uncertain ignored -> "uncertain";
        };
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE work_prepare_effect SET state=?,reason=?,updated_at=now() WHERE attempt_id=? AND state='uncertain'")) {
            ps.setString(1, state);
            ps.setString(2, "uncertain".equals(state) ? "dispatch_uncertain" : null);
            ps.setObject(3, attempt);
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    private WorkDraftAssembly.Prepared claim(Claim claim) {
        UUID attempt = claim.effect().attempt();
        try (Connection c = dataSource.getConnection()) {
            if (!transitions.current(c, claim.observed())) return null;
            WorkItemTransitions.lockItem(c, claim.effect().itemId());
            List<EventEnvelope> history = store.history(claim.effect().itemId());
            WorkItemEvent item = (WorkItemEvent) history.getLast().payload();
            if (!claimable(c, claim.effect(), item)) return null;
            String refusal = refusal(claim, history, item);
            if (refusal != null) { stop(c, history, item, attempt, refusal); return null; }
            // The setup changed after it was read: the next drain reads it again, with the head of its base branch.
            if (defaults.get(c, item.repositoryId(), false).revision() != claim.remote().setup().revision()) return null;
            return claimed(c, claim, item);
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot persist the draft dispatch", failure); }
    }

    /** The row is still pending and the item still runs this attempt; a stale row is refused, never dispatched. */
    private boolean claimable(Connection c, WorkPrepareReads.Effect effect, WorkItemEvent item) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT state,generation FROM work_prepare_effect WHERE attempt_id=? FOR UPDATE")) {
            ps.setObject(1, effect.attempt());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"pending".equals(rs.getString(1))) return false;
                String phase = effect.kind() == WorkDraftBinding.Kind.SPEC ? "spec" : "plan";
                if (rs.getLong(2) != item.generation() || !effect.attempt().equals(item.progress().attemptId())
                        || !"active".equals(item.workflowStatus()) || !phase.equals(item.phase()) || item.draft() == null) {
                    refuse(c, effect.attempt(), "work_item_changed");
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Why this run may not be bought: a read that failed, or a policy that changed since the decision which admitted
     * this attempt (not the newer observational projection intake may have written meanwhile). Null when it may.
     */
    private String refusal(Claim claim, List<EventEnvelope> history, WorkItemEvent item) {
        if (claim.remote().refusal() != null) return claim.remote().refusal();
        UUID attempt = claim.effect().attempt();
        WorkItemEvent admitted = WorkItemTransitions.facts(history).stream()
                .filter(event -> STARTS.contains(event.milestone()) && attempt.equals(event.progress().attemptId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Draft attempt " + attempt + " has no admission decision"));
        WorkItemTransitions.Observation observed = claim.observed();
        if (!transitions.select(observed, item).equals(admitted.policy()) || observed.policy().revision() != admitted.policyRevision())
            return "policy_changed_before_dispatch";
        return null;
    }

    private WorkDraftAssembly.Prepared claimed(Connection c, Claim claim, WorkItemEvent item) throws SQLException, java.io.IOException {
        WorkPrepareReads.Effect effect = claim.effect();
        WorkPrepareReads.Reads remote = claim.remote();
        WorkDraftBinding binding = new WorkDraftBinding(item.workItemId(), item.generation(), effect.attempt(), effect.kind(),
                WorkPrepareReads.provisionalDigest(remote.setup().revision(), remote.ticket(), effect.kind()));
        String prompt = WorkDraftPrompts.specification(remote.ticket(), effect.retryError(), effect.note());
        WorkDraftAssembly.Prepared prepared;
        try { prepared = assembly.assemble(c, new WorkDraftAssembly.Request(claim.observed().source(), item, remote.setup(), prompt, remote.head(), binding)); }
        catch (IllegalStateException | jakarta.ws.rs.BadRequestException unavailable) {
            stop(c, store.history(item.workItemId()), item, effect.attempt(), WorkRunAssemblyRefusals.reasonOf(unavailable));
            return null;
        }
        String runId = prepared.command().runId();
        if (!runs.queued(prepared.row(), "Specification for work item " + item.issue().issueKey(), item.repositoryId())) {
            refuse(c, effect.attempt(), "run_already_recorded");
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE factory_run SET work_item_id=?,work_phase_attempt_id=? WHERE run_id=?"
                + " AND (work_item_id IS NULL OR (work_item_id=? AND work_phase_attempt_id=?))")) {
            ps.setString(1, item.workItemId()); ps.setObject(2, effect.attempt()); ps.setString(3, runId);
            ps.setString(4, item.workItemId()); ps.setObject(5, effect.attempt());
            if (ps.executeUpdate() != 1) throw new IllegalStateException("Run association already exists");
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE work_prepare_effect SET state='uncertain',reason='dispatch_claimed',run_id=?,"
                + "provisional_digest=?,setup_revision=?,updated_at=now() WHERE attempt_id=?")) {
            ps.setString(1, runId); ps.setString(2, binding.provisionalDigest()); ps.setLong(3, remote.setup().revision());
            ps.setObject(4, effect.attempt());
            ps.executeUpdate();
        }
        return prepared;
    }

    /** The run cannot be bought: the row is refused and the item stops with the rule's own reason, as a build's does. */
    private void stop(Connection c, List<EventEnvelope> history, WorkItemEvent item, UUID attempt, String reason) throws SQLException, java.io.IOException {
        refuse(c, attempt, reason);
        WorkItemEvent stopped = WorkItemLifecycle.state(item, "stopped", reason, "WORK_ITEM_REFUSED", item.gate(), item.progress().reserve(false));
        transitions.decide(c, new WorkItemTransitions.Decision(history, stopped, "draft-refused:" + attempt));
    }

    private void refuse(Connection c, UUID attempt, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE work_prepare_effect SET state='refused',reason=?,updated_at=now() WHERE attempt_id=? AND state='pending'")) {
            ps.setString(1, reason); ps.setObject(2, attempt);
            ps.executeUpdate();
        }
    }

    private void execute(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) { ps.executeUpdate(); }
        catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }
}

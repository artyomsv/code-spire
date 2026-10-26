package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.WorkDraftLifecycle;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkPolicy;
import dev.codespire.contract.work.WorkStepLifecycle;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The transitions of a plan step that the hard-coded phase order does not know (M4 slice 3, §6.6, A2, decision 7), as
 * {@link WorkLandTransitions} holds land's: each decision is {@link WorkStepLifecycle}'s, made under the item lock.
 */
@ApplicationScoped
public class WorkStepTransitions {
    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;
    @Inject WorkClock clock;

    /** What a failed run of the current step bought, and the run it failed for. */
    record Failure(String runId, String cause, WorkItemTransitions.PhaseResult usage) {}

    /**
     * A failed run of a later step whose earlier steps hold a bundle (decision 7): the verify gate opens on the chain
     * run's head instead of failing the item. Null when the failure is not such a step's: the ordinary completion
     * decides it then, as it decides every other build.
     */
    WorkItemTransitions.Outcome runFailed(String id, Failure failure) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                WorkItemTransitions.lockItem(c, id);
                List<EventEnvelope> history = store.history(id);
                WorkItemEvent item = (WorkItemEvent) history.getLast().payload();
                boolean landFix = WorkItemLifecycle.landFixInProgress(WorkItemTransitions.facts(history));
                UUID attempt = failure.usage().attemptId();
                if (!WorkStepLifecycle.routesRunFailure(item, landFix) || !"active".equals(item.workflowStatus())
                        || !Objects.equals(attempt, item.progress().attemptId()) || verifyOff(item)) return null;
                if (completed(c, attempt)) return new WorkItemTransitions.Outcome(200, "result_already_applied", item);
                WorkItemEvent next = WorkStepLifecycle.stepRunFailed(item, runFailure(c, failure),
                        new WorkItemLifecycle.GateOpening(history.size(), clock.now(), UUID.randomUUID()));
                next = transitions.decide(c, new WorkItemTransitions.Decision(history, next, "phase-result:" + attempt));
                return new WorkItemTransitions.Outcome(200, next.reason(), next);
            } catch (SQLException failed) {
                throw WorkSourceRegistry.database(failed);
            } catch (java.io.IOException failed) {
                throw new IllegalStateException("Cannot encode a plan step's failure", failed);
            }
        });
    }

    /**
     * Whether a plan step's ready names a start other than the one its dispatch required (A2): a head off the chain. The
     * run worker never replaces a required start, so only a crafted or crossed result differs. A build with no required
     * start may have fallen back to the base, and is not compared.
     */
    boolean offTheChain(RunResult.RunWorkReady ready) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT required_start_head FROM work_run_effect WHERE run_id=?")) {
            ps.setString(1, ready.runId());
            try (ResultSet rs = ps.executeQuery()) {
                String required = rs.next() ? rs.getString(1) : null;
                if (required == null || required.equals(ready.startFromHead())) return false;
            }
            try (PreparedStatement note = c.prepareStatement("UPDATE work_run_effect SET reason='phase_evidence_mismatch' WHERE run_id=?")) {
                note.setString(1, ready.runId());
                note.executeUpdate();
            }
            return true;
        } catch (SQLException failed) {
            throw WorkSourceRegistry.database(failed);
        }
    }

    private static WorkStepLifecycle.RunFailure runFailure(Connection c, Failure failure) throws SQLException {
        boolean missing = RunFailureCause.of(failure.cause()) == RunFailureCause.CHECKPOINT_MISSING;
        WorkItemTransitions.PhaseResult usage = failure.usage();
        return new WorkStepLifecycle.RunFailure(usage.attemptId(), missing, missing ? requiredStart(c, failure.runId()) : null,
                new WorkDraftLifecycle.RunUsage(usage.wallSeconds(), usage.costMillicents(), usage.calls(), usage.usageKnown()));
    }

    /** The start the failed run was dispatched to read, which a missing checkpoint records as lost (decision 11). */
    private static String requiredStart(Connection c, String runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT required_start_run_id FROM work_run_effect WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean completed(Connection c, UUID attempt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT state FROM work_phase_attempt WHERE id=?")) {
            ps.setObject(1, attempt);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "completed".equals(rs.getString(1));
            }
        }
    }

    /** A verify switched off while the step ran has no gate to open; the ordinary completion decides the failure. */
    private static boolean verifyOff(WorkItemEvent item) {
        return item.policy().selected() == null || "off".equals(item.policy().effective().getOrDefault(WorkPolicy.Phase.VERIFY, "off"));
    }
}

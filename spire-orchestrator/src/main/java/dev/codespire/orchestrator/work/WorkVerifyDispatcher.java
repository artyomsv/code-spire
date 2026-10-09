package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.orchestrator.factory.BuildDefaults;
import dev.codespire.orchestrator.factory.RunLaunch;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Sends the verify for a started verify attempt (M4). The claim commits before the command goes on the bus,
 * and an uncertain send is never resent automatically: the worker's own claim makes a duplicate harmless,
 * but nothing here should rely on that to decide what happened.
 */
@ApplicationScoped
public class WorkVerifyDispatcher {
    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkVerifyTransport transport;

    @Scheduled(every = "${spire.work-run-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void drain() {
        List<UUID> pending = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT attempt_id FROM work_verify_effect WHERE state='pending' ORDER BY created_at LIMIT 20");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) pending.add(rs.getObject(1, UUID.class));
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        for (UUID attempt : pending) dispatch(attempt);
    }

    public void dispatch(UUID attempt) {
        RunCommand.VerifyWork command = QuarkusTransaction.requiringNew().call(() -> claim(attempt));
        if (command == null) return;
        RunLaunch.Outcome outcome;
        try { outcome = transport.dispatch(command); }
        catch (RuntimeException unknown) { outcome = new RunLaunch.Uncertain(new IllegalStateException("Verify dispatch outcome is unknown", unknown)); }
        String state = switch (outcome) {
            case RunLaunch.Dispatched ignored -> "sent";
            case RunLaunch.DefiniteMiss ignored -> "pending";
            case RunLaunch.Uncertain ignored -> "uncertain";
        };
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE work_verify_effect SET state=?,reason=? WHERE attempt_id=? AND state='uncertain'")) {
            ps.setString(1, state);
            ps.setString(2, "uncertain".equals(state) ? "dispatch_uncertain" : null);
            ps.setObject(3, attempt);
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    private RunCommand.VerifyWork claim(UUID attempt) {
        try (Connection c = dataSource.getConnection()) {
            String id;
            try (PreparedStatement ps = c.prepareStatement("SELECT work_item_id FROM work_verify_effect WHERE attempt_id=?")) {
                ps.setObject(1, attempt);
                try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) return null; id = rs.getString(1); }
            }
            WorkItemTransitions.lockItem(c, id);
            WorkItemEvent item = (WorkItemEvent) store.history(id).getLast().payload();
            String head;
            long generation;
            try (PreparedStatement ps = c.prepareStatement("SELECT state,generation,head FROM work_verify_effect WHERE attempt_id=? FOR UPDATE")) {
                ps.setObject(1, attempt);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !"pending".equals(rs.getString(1))) return null;
                    generation = rs.getLong(2);
                    head = rs.getString(3);
                }
            }
            if (!isStillVerifying(item, attempt, generation, head)) {
                refuse(c, attempt);
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE work_verify_effect SET state='uncertain',reason='dispatch_claimed' WHERE attempt_id=?")) {
                ps.setObject(1, attempt);
                ps.executeUpdate();
            }
            return commandFor(item, attempt);
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    /** The item is still in this verify attempt, of this generation, at this head, with a preparation to read. */
    private static boolean isStillVerifying(WorkItemEvent item, UUID attempt, long generation, String head) {
        WorkExecution execution = item.progress().execution();
        return generation == item.generation() && attempt.equals(item.progress().attemptId()) && "verify".equals(item.phase())
                && "active".equals(item.workflowStatus()) && execution != null && Objects.equals(head, execution.head())
                && item.preparation() != null;
    }

    private static RunCommand.VerifyWork commandFor(WorkItemEvent item, UUID attempt) {
        WorkExecution execution = item.progress().execution();
        long timeout = item.preparation().verifyTimeoutSeconds();
        // A version 4 preparation carries no checks and no limit: it still verifies, as no_checks_declared.
        return new RunCommand.VerifyWork(execution.runId(), execution.build(), attempt, execution.head(),
                item.preparation().verifyCommands(), timeout > 0 ? timeout : BuildDefaults.DEFAULT_VERIFY_SECONDS);
    }

    private static void refuse(Connection c, UUID attempt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE work_verify_effect SET state='refused',reason='work_item_changed' WHERE attempt_id=? AND state='pending'")) {
            ps.setObject(1, attempt);
            ps.executeUpdate();
        }
    }
}

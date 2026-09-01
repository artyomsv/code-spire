package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.event.RunResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * The {@code factory_run} read model.
 *
 * <p>Two rules that are not obvious. A gate refusal is {@code push_gate_refused}, never
 * {@code failed}: the run did correct work that was deliberately not delivered, and a status saying
 * otherwise sends an operator hunting for a bug that does not exist. And every write is
 * idempotent on redelivery — a result reaches this projection through a channel that acks on
 * receipt, so the same {@code RunFinished} can arrive twice, and the second must change nothing.
 */
@ApplicationScoped
public class FactoryRunProjection {

    private static final Logger LOG = Logger.getLogger(FactoryRunProjection.class);

    static final String QUEUED = "queued";

    static final String RUNNING = "running";

    static final String SUCCEEDED = "succeeded";

    static final String FAILED = "failed";

    static final String PUSH_GATE_REFUSED = "push_gate_refused";

    /** The one failure cause a retried {@link #queued} re-arms — see {@link #dispatchFailed}. */
    static final String DISPATCH_FAILED = "DISPATCH_FAILED";

    private static final String FAIL_SQL = """
            UPDATE factory_run SET status = ?, failure_cause = ?, failure_detail = ?, ended_at = now()
             WHERE run_id = ? AND status IN (?, ?)
            """;

    /** What the read model knows about one run. */
    public record RunView(String runId, String status, String pushedRef, List<String> blockedPaths,
                          String failureCause, String failureDetail) {
    }

    @Inject
    DataSource dataSource;

    /**
     * A queued row, written BEFORE the command is dispatched so a run can never exist unrecorded.
     *
     * <p>Idempotent on the run id, with one deliberate exception: a row whose dispatch the broker
     * never acknowledged ({@link #DISPATCH_FAILED}) is re-armed to queued. Any other existing row
     * is left alone — a succeeded or refused run must not be reopened by a repeated request.
     */
    public void queued(String runId, String harness, String model, String baseBranch, String baseCommit,
                       String branch, String pushedAs) {
        RunIds.Parsed parsed = RunIds.parse(runId);
        String sql = """
                INSERT INTO factory_run (run_id, provider_type, workspace, slug, subject, attempt, status,
                                         harness, model, base_branch, base_commit, branch, pushed_as)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id) DO UPDATE
                   SET status = EXCLUDED.status, failure_cause = NULL, failure_detail = NULL, ended_at = NULL
                 WHERE factory_run.status = ? AND factory_run.failure_cause = ?
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setString(2, parsed.scmType().providerType());
            ps.setString(3, parsed.workspace());
            ps.setString(4, parsed.slug());
            ps.setString(5, parsed.subject());
            ps.setInt(6, parsed.attempt());
            ps.setString(7, QUEUED);
            ps.setString(8, harness);
            ps.setString(9, model);
            ps.setString(10, baseBranch);
            ps.setString(11, baseCommit);
            ps.setString(12, branch);
            ps.setString(13, pushedAs);
            ps.setString(14, FAILED);
            ps.setString(15, DISPATCH_FAILED);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record run " + runId, e);
        }
    }

    public void apply(RunResult result) {
        switch (result) {
            case RunResult.RunStarted started -> started(started.runId());
            case RunResult.RunFinished finished -> finished(finished);
            case RunResult.RunFailed failed -> failed(failed);
        }
    }

    private void started(String runId) {
        // Only a queued run starts. A redelivered RunStarted after RunFinished must not drag a
        // terminal row back to running — and a terminal row has an ended_at the CHECK would then
        // refuse anyway.
        update("UPDATE factory_run SET status = ? WHERE run_id = ? AND status = ?",
                runId, RUNNING, runId, QUEUED);
    }

    private void finished(RunResult.RunFinished finished) {
        if (finished.refused()) {
            update("""
                    UPDATE factory_run SET status = ?, blocked_paths = ?, ended_at = now()
                     WHERE run_id = ? AND status IN (?, ?)
                    """, finished.runId(), PUSH_GATE_REFUSED, String.join("\n", finished.blockedPaths()),
                    finished.runId(), QUEUED, RUNNING);
            return;
        }
        update("""
                UPDATE factory_run SET status = ?, pushed_ref = ?, ended_at = now()
                 WHERE run_id = ? AND status IN (?, ?)
                """, finished.runId(), SUCCEEDED, finished.pushedRef(), finished.runId(), QUEUED, RUNNING);
    }

    private void failed(RunResult.RunFailed failed) {
        update(FAIL_SQL, failed.runId(), FAILED, failed.cause(), failed.detail(), failed.runId(), QUEUED, RUNNING);
    }

    /**
     * The row was written but the broker never acknowledged the command, so whether the run exists
     * on the bus is UNKNOWN — an ack timeout does not mean the record was lost. Marked rather than
     * deleted, because a deleted row is exactly the unrecorded run the write order in
     * {@link #queued} exists to prevent. That method re-arms precisely this row, so the operator's
     * retry of the same request either starts the run or, if the first dispatch did land after all,
     * lets the worker's claim drop the duplicate while its results project onto the re-armed row.
     */
    public void dispatchFailed(String runId, String detail) {
        update(FAIL_SQL, runId, FAILED, DISPATCH_FAILED, detail, runId, QUEUED, RUNNING);
    }

    /**
     * A terminal-state write that touches no row is a REDELIVERY, not an error — the first delivery
     * already moved the row. Logged at debug so a genuine ordering fault is still traceable.
     */
    private void update(String sql, String runId, Object... params) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            if (ps.executeUpdate() == 0) {
                LOG.debugf("run %s: result touched no row (redelivery, or already terminal)", runId);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to project a result for run " + runId, e);
        }
    }

    public Optional<RunView> find(String runId) {
        String sql = """
                SELECT status, pushed_ref, blocked_paths, failure_cause, failure_detail
                  FROM factory_run WHERE run_id = ?
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String blocked = rs.getString("blocked_paths");
                return Optional.of(new RunView(runId, rs.getString("status"), rs.getString("pushed_ref"),
                        blocked == null ? List.of() : List.of(blocked.split("\n")),
                        rs.getString("failure_cause"), rs.getString("failure_detail")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read run " + runId, e);
        }
    }
}

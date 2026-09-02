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
     * never acknowledged ({@link #DISPATCH_FAILED}) is re-armed to queued — and ONLY by the identical
     * request. "Never acknowledged" means the first command may well have landed and be the one
     * that runs; the worker's claim then drops the retry. A re-arm that took the retry's parameters
     * would leave that first run's result on a row describing a different base commit, model or
     * account. A differing retry therefore matches no row here and is refused by the caller. Any
     * other existing row is left alone — a succeeded or refused run must not be reopened.
     */
    /**
     * @return whether a row was written or re-armed. {@code false} means the run already exists in
     *     a state this must not overwrite (queued, running, or finished either way) — the caller
     *     must refuse rather than dispatch, because the claim store would then drop the command as
     *     a redelivery and the operator would hold a 201 for a run that never runs.
     */
    /** What a queued row is made of — the dispatch's own inputs, before any result. */
    public record QueuedRun(String runId, String harness, String model, String baseBranch,
                            String baseCommit, String branch, String pushedAs) {
    }

    /**
     * A row is still "live" — a result may project onto it — while queued or running, and also
     * while it reads {@code failed / DISPATCH_FAILED}: that cause means the broker's ack timed out,
     * not that the record was lost, and the worker consumes one command at a time, so the real
     * {@code RunStarted} routinely arrives AFTER the row was marked. A result correcting such a row
     * clears the failure it superseded.
     */
    private static final String LIVE = "(status IN ('" + QUEUED + "', '" + RUNNING + "') "
            + "OR (status = '" + FAILED + "' AND failure_cause = '" + DISPATCH_FAILED + "'))";

    public boolean queued(QueuedRun row) {
        String runId = row.runId();
        String harness = row.harness();
        String model = row.model();
        String baseBranch = row.baseBranch();
        String baseCommit = row.baseCommit();
        String branch = row.branch();
        String pushedAs = row.pushedAs();
        RunIds.Parsed parsed = RunIds.parse(runId);
        String sql = """
                INSERT INTO factory_run (run_id, provider_type, workspace, slug, subject, attempt, status,
                                         harness, model, base_branch, base_commit, branch, pushed_as)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id) DO UPDATE
                   SET status = EXCLUDED.status, failure_cause = NULL, failure_detail = NULL, ended_at = NULL
                 WHERE factory_run.status = ? AND factory_run.failure_cause = ?
                   AND factory_run.harness = EXCLUDED.harness AND factory_run.model = EXCLUDED.model
                   AND factory_run.base_branch = EXCLUDED.base_branch AND factory_run.base_commit = EXCLUDED.base_commit
                   AND factory_run.branch = EXCLUDED.branch
                   AND factory_run.pushed_as IS NOT DISTINCT FROM EXCLUDED.pushed_as
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
            // 1 on insert and on a re-arm; 0 when ON CONFLICT matched a row the WHERE declined to
            // touch. That 0 used to be discarded, and the dispatch went ahead anyway.
            return ps.executeUpdate() == 1;
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
        // A queued run starts — and so does one whose dispatch the broker never acknowledged: the
        // RunStarted IS the acknowledgement. A redelivered RunStarted after RunFinished must not
        // drag a terminal row back to running, and a terminal row has an ended_at the CHECK would
        // refuse anyway; only the DISPATCH_FAILED shape of "terminal" is reopened.
        update("UPDATE factory_run SET status = ?, failure_cause = NULL, failure_detail = NULL, ended_at = NULL"
                        + " WHERE run_id = ? AND (status = ? OR (status = ? AND failure_cause = ?))",
                runId, RUNNING, runId, QUEUED, FAILED, DISPATCH_FAILED);
    }

    private void finished(RunResult.RunFinished finished) {
        if (finished.refused()) {
            update("UPDATE factory_run SET status = ?, blocked_paths = ?, failure_cause = NULL, failure_detail = NULL,"
                            + " ended_at = now() WHERE run_id = ? AND " + LIVE,
                    finished.runId(), PUSH_GATE_REFUSED, String.join("\n", finished.blockedPaths()), finished.runId());
            return;
        }
        update("UPDATE factory_run SET status = ?, pushed_ref = ?, failure_cause = NULL, failure_detail = NULL,"
                        + " ended_at = now() WHERE run_id = ? AND " + LIVE,
                finished.runId(), SUCCEEDED, finished.pushedRef(), finished.runId());
    }

    private void failed(RunResult.RunFailed failed) {
        update("UPDATE factory_run SET status = ?, failure_cause = ?, failure_detail = ?, ended_at = now()"
                        + " WHERE run_id = ? AND " + LIVE,
                failed.runId(), FAILED, failed.cause(), failed.detail(), failed.runId());
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
        // Guarded on QUEUED alone, deliberately not FAIL_SQL's (queued, running) pair. The emitter's
        // ack wait is 10s and the producer's own delivery timeout is 120s, so a slow broker can time
        // the ack out AFTER the record landed and a worker already started the run: its RunStarted
        // has moved the row to running. Marking that row failed would have its real result — which
        // only projects onto a queued or running row — silently dropped, and the operator's retry
        // would re-arm the row under a run that is still executing.
        update("""
                UPDATE factory_run SET status = ?, failure_cause = ?, failure_detail = ?, ended_at = now()
                 WHERE run_id = ? AND status = ?
                """, runId, FAILED, DISPATCH_FAILED, detail, runId, QUEUED);
    }

    /**
     * Clears the run's attention row. Only {@code attention_ack_at} moves — the row's own timestamps
     * stay, so the predicate {@code ended_at > attention_ack_at} keeps its meaning.
     *
     * @return false when no such run exists
     */
    public boolean acknowledgeAttention(String runId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE factory_run SET attention_ack_at = now() WHERE run_id = ?")) {
            ps.setString(1, runId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to acknowledge attention for run " + runId, e);
        }
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

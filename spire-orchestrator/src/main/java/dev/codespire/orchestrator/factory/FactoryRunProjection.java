package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunFailureCause;
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

    /**
     * The command was published and the broker never acknowledged it, so nobody knows if it landed.
     *
     * <p>Not {@link #FAILED}. A failed dispatch is re-armable by {@link #queued}, which is right when
     * the record never left and expensive when it did — the operator's identical retry publishes a
     * second command, and if the first one landed after all, two agents work the same branch and the
     * model is paid twice. An elapsed acknowledgement says nothing about the record, so recording it
     * as a definite miss was a guess made in the direction that costs money.
     *
     * <p><b>Live, not terminal.</b> If the record did land, that run's own {@code RunStarted} is on
     * its way, and a result only applies to a live row — so making this terminal would drop the
     * result of a run that really is executing, which is the exact thing being uncertain about.
     * Reality resolves most of these with nobody being asked; the operator's resolution is for the
     * ones where nothing ever arrives.
     */
    static final String DISPATCH_UNCERTAIN = "dispatch_uncertain";

    /**
     * An operator stopped this run, so it is not a failure.
     *
     * <p>Reserved in the V43 status set at M0 and written by nothing until now: every cancelled run
     * was projected {@link #FAILED}, which tells the operator who pressed the button that the thing
     * they stopped broke. The worker already goes to the trouble of recording the cancellation
     * separately from acting on it, precisely so the killed agent's non-zero exit is not read as a
     * fault — and then the projection discarded that distinction one layer up.
     *
     * <p>The same argument as {@link #PUSH_GATE_REFUSED}, only stronger: there the run did correct
     * work that was deliberately not delivered, here the operator IS the cause. The cause is still
     * stored beside it, so the row says both what happened and who ended it.
     */
    static final String CANCELLED = "cancelled";

    /**
     * Finished, and there was nothing to deliver: the agent exited cleanly and committed nothing.
     *
     * <p>A status rather than a failure, for the reason {@link #PUSH_GATE_REFUSED} is one. The run
     * did what it was asked; calling it failed sends an operator hunting for a bug that does not
     * exist. It used to be written as {@link #SUCCEEDED}, indistinguishable in the list from a run
     * whose branch is on the remote.
     */
    static final String DELIVERED_NOTHING = "delivered_nothing";

    /**
     * The work is on the branch, and the agent's own outcome was never observed.
     *
     * <p>It overran its wall clock, or the runtime could not read its exit — and the publisher had
     * already pushed. Neither neighbouring status tells the truth: {@link #FAILED} hides a branch
     * that really is on the remote, and {@link #SUCCEEDED} asserts a clean delivery for a run whose
     * agent was killed mid-thought, so a half-written change reviews like a finished one.
     *
     * <p>Terminal, and not retryable — retrying puts a second agent on the branch the first may
     * still be holding.
     */
    static final String DELIVERED_UNFINISHED = "delivered_unfinished";

    /** The one failure cause a retried {@link #queued} re-arms — see {@link #dispatchFailed}. */
    static final String DISPATCH_FAILED = "DISPATCH_FAILED";

    /**
     * The model this run was dispatched with, or empty when the row cannot be read.
     *
     * <p>Read from the run's own row rather than carried on the result, because the result is
     * emitted by the worker and the model is the ORCHESTRATOR's fact: it chose it at dispatch,
     * and a worker echoing it back would let the two disagree with nothing to say which is right.
     */
    public Optional<String> modelOf(String runId) {
        String sql = "SELECT model FROM factory_run WHERE run_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString("model")) : Optional.empty();
            }
        } catch (SQLException e) {
            // Empty, not a throw: the caller is recording a charge for money already spent, and
            // a throw here would dead-letter the result and lose the run's outcome as well.
            LOG.errorf(e, "run %s: its model could not be read for the charge ledger", runId);
            return Optional.empty();
        }
    }

    /**
     * What the read model knows about one run.
     *
     * @param unitId the sandbox's own id, null until the worker creates one. The route to a unit
     *               kept for inspection — the container label was the documented workaround
     *               precisely because this used to be discarded.
     */
    public record RunView(String runId, String status, String pushedRef, List<String> blockedPaths,
                          String failureCause, String failureDetail, String unitId) {
    }

    @Inject
    DataSource dataSource;

    /** What a queued row is made of — the dispatch's own inputs, before any result. */
    public record QueuedRun(String runId, String harness, String model, String baseBranch,
                            String baseCommit, String branch, String pushedAs) {
    }

    /**
     * A row is still "live" — a result may project onto it — while queued, running, or awaiting a
     * dispatch resolution, and also while it carries EITHER dispatch failure cause.
     *
     * <p>All four cases share one fact: the real {@code RunStarted} may still be on its way. A
     * dispatch failure means the broker did not acknowledge, not that the record was lost, and the
     * worker consumes one command at a time, so a result routinely arrives after the row was marked.
     * A result correcting such a row clears the failure it superseded.
     *
     * <p><b>{@code DISPATCH_UNCERTAIN} belongs here, and leaving it out was a defect.</b> An operator
     * resolving "the run did start" wrote that cause — and the row then stopped accepting the very
     * result they had just asserted was coming, so the branch reached the remote with no row pointing
     * at it. The asymmetry gave it away: resolving "it never ran" left the row live, so the answer
     * that ASSERTS a run is executing was the one that discarded its outcome.
     *
     * <p>Being live is not being re-armable. {@link #queued} re-arms on its own guard, which names
     * {@code DISPATCH_FAILED} alone, so admitting the uncertain cause here publishes nothing.
     */
    private static final String LIVE = "(status IN ('" + QUEUED + "', '" + RUNNING + "', '"
            + DISPATCH_UNCERTAIN + "') "
            + "OR (status = '" + FAILED + "' AND failure_cause IN ('" + DISPATCH_FAILED + "', '"
            + RunFailureCause.DISPATCH_UNCERTAIN.name() + "')))";

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
     *
     * <p>An uncertain dispatch is NOT re-armed, and that is the fail-closed half: the guard below
     * names {@link #DISPATCH_FAILED} alone, so a row whose command may already be on the topic is
     * refused rather than published a second time.
     *
     * @return whether a row was written or re-armed. {@code false} means the run already exists in
     *     a state this must not overwrite (queued, running, awaiting a dispatch resolution, or
     *     finished either way) — the caller must refuse rather than dispatch, because the claim
     *     store would then drop the command as a redelivery and the operator would hold a 201 for a
     *     run that never runs.
     */
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
            case RunResult.RunStarted started -> started(started.runId(), started.providerRunId());
            case RunResult.RunFinished finished -> finished(finished);
            case RunResult.RunFailed failed -> failed(failed);
        }
    }

    private void started(String runId, String unitId) {
        // Recorded unconditionally, and BEFORE the status transition below, because the two
        // answer different questions: the unit id says where to look, and it is worth having
        // even for a row the status guard declines to reopen. A redelivery writes the same value.
        update("UPDATE factory_run SET unit_id = ? WHERE run_id = ?", runId, unitId, runId);
        // A queued run starts — and so does one whose dispatch the broker never acknowledged: the
        // RunStarted IS the acknowledgement. A redelivered RunStarted after RunFinished must not
        // drag a terminal row back to running, and a terminal row has an ended_at the CHECK would
        // refuse anyway; only the DISPATCH_FAILED shape of "terminal" is reopened.
        // Both dispatch shapes are reopened here, and this is what usually resolves them: the
        // RunStarted IS the acknowledgement the broker never gave us, so the ambiguity ends as a
        // fact rather than as somebody's decision. The failed/DISPATCH_UNCERTAIN shape is included
        // for the same reason it is in LIVE -- an operator who answered "it did start" must not
        // thereby stop the row hearing the start.
        update("UPDATE factory_run SET status = ?, failure_cause = NULL, failure_detail = NULL, ended_at = NULL"
                        + " WHERE run_id = ? AND (status IN (?, ?) OR (status = ? AND failure_cause IN (?, ?)))",
                runId, RUNNING, runId, QUEUED, DISPATCH_UNCERTAIN, FAILED, DISPATCH_FAILED,
                RunFailureCause.DISPATCH_UNCERTAIN.name());
    }

    private void finished(RunResult.RunFinished finished) {
        if (finished.refused()) {
            update("UPDATE factory_run SET status = ?, blocked_paths = ?, failure_cause = NULL, failure_detail = NULL,"
                            + " ended_at = now() WHERE run_id = ? AND " + LIVE,
                    finished.runId(), PUSH_GATE_REFUSED, String.join("\n", finished.blockedPaths()), finished.runId());
            return;
        }
        String status;
        if (finished.pushedRef() == null) {
            status = DELIVERED_NOTHING;
        } else if (finished.agentUnobserved()) {
            // Both facts, because either alone misleads. Ranked ahead of SUCCEEDED rather than
            // decorating it: an operator reads the status, and a decoration on a green row is not
            // read at all.
            status = DELIVERED_UNFINISHED;
        } else {
            status = SUCCEEDED;
        }
        update("UPDATE factory_run SET status = ?, pushed_ref = ?, failure_cause = NULL, failure_detail = NULL,"
                        + " ended_at = now() WHERE run_id = ? AND " + LIVE,
                finished.runId(), status, finished.pushedRef(), finished.runId());
    }

    /**
     * Normalized through {@link RunFailureCause} before it is stored, never written raw.
     *
     * <p>The cause arrives as a string from three producers with three vocabularies, and V46
     * constrains the column to the closed set. Writing {@code failed.cause()} straight through would
     * make a producer's unrecognised spelling a constraint violation — thrown inside a result
     * handler, after the run has already been paid for, which is how this project once
     * dead-lettered a charged review. Mapping to {@code UNCLASSIFIED} records the failure honestly
     * and keeps the producer's own word in the detail beside it.
     */
    private void failed(RunResult.RunFailed failed) {
        RunFailureCause cause = RunFailureCause.of(failed.cause());
        String detail = cause == RunFailureCause.UNCLASSIFIED
                ? unrecognised(failed.cause(), failed.detail())
                : failed.detail();
        // A cancellation is not a failure, and the status is how an operator reads the row at a
        // glance. The worker relabels the killed agent's exit to CANCELLED for exactly this reason;
        // writing FAILED here threw that away and told whoever stopped the run that it broke.
        String status = cause == RunFailureCause.CANCELLED ? CANCELLED : FAILED;
        update("UPDATE factory_run SET status = ?, failure_cause = ?, failure_detail = ?, ended_at = now()"
                        + " WHERE run_id = ? AND " + LIVE,
                failed.runId(), status, cause.name(), detail, failed.runId());
    }

    /** Keeps the producer's own spelling readable when this version does not know it. */
    private static String unrecognised(String rawCause, String detail) {
        String prefix = "unrecognised cause '" + rawCause + "'";
        return detail == null || detail.isBlank() ? prefix : prefix + ": " + detail;
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
        // This statement's WHERE is a state guard, so a zero row count is a refusal rather than
        // the redelivery `update` reports for the event-driven writes. Ignored here on purpose:
        // the row having moved on means a result already told us more than we were about to.
        // Guarded on QUEUED alone, deliberately not FAIL_SQL's (queued, running) pair. The emitter's
        // ack wait is 10s and the producer's own delivery timeout is 120s, so a slow broker can time
        // the ack out AFTER the record landed and a worker already started the run: its RunStarted
        // has moved the row to running. Marking that row failed would have its real result — which
        // only projects onto a queued or running row — silently dropped, and the operator's retry
        // would re-arm the row under a run that is still executing.
        update("""
                UPDATE factory_run SET status = ?, failure_cause = ?, failure_detail = ?, ended_at = now()
                 WHERE run_id = ? AND status = ?
                """, runId, FAILED, RunFailureCause.DISPATCH_FAILED.name(), detail, runId, QUEUED);
    }

    /**
     * The broker never answered, so whether the run exists is UNKNOWN (FR-F10).
     *
     * <p>Guarded on {@link #QUEUED} for the reason {@link #dispatchFailed} is: an acknowledgement can
     * elapse after the record landed and a worker already started the run, whose {@code RunStarted}
     * has moved this row to running. Marking that row would put a live run into a state an operator
     * is asked to resolve, about a question reality has already answered.
     *
     * <p>No {@code ended_at}: the run has not ended, it is unresolved. The V51 CHECK enforces the
     * pairing, so writing one here fails loudly rather than producing a row that claims a finish time
     * while still waiting for its own result.
     */
    public void dispatchUncertain(String runId, String detail) {
        // A state guard, like dispatchFailed above: zero rows means the row moved on, which is
        // reality answering the question this write was about to ask an operator.
        update("""
                UPDATE factory_run SET status = ?, failure_cause = ?, failure_detail = ?
                 WHERE run_id = ? AND status = ?
                """, runId, DISPATCH_UNCERTAIN, RunFailureCause.DISPATCH_UNCERTAIN.name(), detail,
                runId, QUEUED);
    }

    /**
     * The operator found that no run was started, so the row becomes retryable.
     *
     * <p>Writes the re-armable {@link #DISPATCH_FAILED} shape, which means the operator's ordinary
     * retry starts the run through the path that already exists rather than through a second
     * mechanism invented for this.
     *
     * @return false when the row is gone or no longer awaiting a resolution
     */
    public boolean resolveAsNeverRan(String runId) {
        return resolve(runId, RunFailureCause.DISPATCH_FAILED.name(),
                "an operator confirmed no run was started; retry the original request to start one");
    }

    /**
     * The operator found that a run WAS started, so the row is closed and never published again.
     *
     * <p>Nothing re-arms this shape, because publishing again for a run that really happened is the
     * duplicate the whole uncertain state exists to prevent. The row stays inside {@link #LIVE}
     * though: the result the operator has just asserted is coming must still be able to land.
     */
    public boolean resolveAsStarted(String runId) {
        return resolve(runId, RunFailureCause.DISPATCH_UNCERTAIN.name(),
                "an operator confirmed the run did start, so it is not published again");
    }

    /**
     * The one write, and the one guard.
     *
     * <p>Two named methods over this rather than a boolean parameter: the answer was branched on
     * three separate times in a single request — here for the cause, in the resource for the stored
     * detail, and again for the log line — so one decision lived in three places free to disagree.
     * And {@code resolveDispatch(id, false, …)} at a call site says nothing about the fact that
     * {@code false} is the answer permanently forbidding the retry.
     *
     * <p>Guarded on {@link #DISPATCH_UNCERTAIN}, so a run that resolved itself — the common case,
     * where the record did land and its {@code RunStarted} arrived — cannot be overwritten by a
     * decision made before the operator refreshed the page. Zero rows here is that refusal, not a
     * redelivery.
     */
    private boolean resolve(String runId, String cause, String detail) {
        return update("""
                UPDATE factory_run SET status = ?, failure_cause = ?, failure_detail = ?, ended_at = now()
                 WHERE run_id = ? AND status = ?
                """, runId, FAILED, cause, detail, runId, DISPATCH_UNCERTAIN) == 1;
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
     * Apply one statement, and report how many rows it moved.
     *
     * <p>A terminal-state write that touches no row is a REDELIVERY, not an error — the first
     * delivery already moved the row. Logged at debug so a genuine ordering fault is still
     * traceable.
     *
     * <p>What zero MEANS depends on the statement: for the event-driven writes it is that
     * redelivery, and for a statement whose WHERE carries a state guard it is a refusal the caller
     * acts on. Each guarded call site says which it is, because the count alone cannot.
     *
     * @return rows touched
     */
    private int update(String sql, String runId, Object... params) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            int touched = ps.executeUpdate();
            if (touched == 0) {
                LOG.debugf("run %s: result touched no row (redelivery, or already terminal)", runId);
            }
            return touched;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to project a result for run " + runId, e);
        }
    }

    public Optional<RunView> find(String runId) {
        String sql = """
                SELECT status, pushed_ref, blocked_paths, failure_cause, failure_detail, unit_id
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
                        rs.getString("failure_cause"), rs.getString("failure_detail"),
                        rs.getString("unit_id")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read run " + runId, e);
        }
    }
}

package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * The factory's attention rows: a run the push gate refused, naming the paths it blocked
 * (ROADMAP M0 exit criterion 2).
 *
 * <p>One row per run, like the per-review rows and for the same reason — a refusal is a discrete
 * event about a specific record, and "3 run(s) refused" throws away the paths, which are the only
 * thing that makes the row actionable. Past {@link #MAX_ROWS} the remainder is one summary row, so
 * a systemic refusal (a protected-paths rule that is too wide) cannot bury the blockers above it.
 *
 * <p>Nothing un-refuses a run, so the row clears on an operator's acknowledgement
 * ({@code factory_run.attention_ack_at}) exactly as a failed review's does — otherwise it would be
 * this panel's first permanently-lit row, against the contract that fixing the cause removes it.
 */
@ApplicationScoped
public class RunAttentionRows {

    static final String CODE = "RUN_PUSH_GATE_REFUSED";

    private static final int MAX_ROWS = 5;

    /** One past the cap is fetched, so the summary row knows there is more without counting it all. */
    private static final String REFUSED_SQL = """
            SELECT run_id, blocked_paths FROM factory_run
             WHERE status = 'push_gate_refused'
               AND (attention_ack_at IS NULL OR ended_at > attention_ack_at)
             ORDER BY ended_at DESC
            """ + " LIMIT " + (MAX_ROWS + 1);

    static final String UNCERTAIN_CODE = "RUN_DISPATCH_UNCERTAIN";

    /**
     * Runs whose dispatch was published and never acknowledged (FR-F10).
     *
     * <p>No acknowledgement watermark, unlike the two ledger rows, and that is the point rather than
     * an omission: this describes CURRENT state, and resolving the dispatch — or the run's own result
     * arriving — takes the row away. A row an operator could silence without deciding anything would
     * leave a run that may be executing with nothing tracking it.
     */
    private static final String UNCERTAIN_SQL = """
            SELECT run_id FROM factory_run
             WHERE status = 'dispatch_uncertain'
             ORDER BY started_at DESC
            """ + " LIMIT " + (MAX_ROWS + 1);

    void collect(Connection c, List<AttentionView> rows) throws SQLException {
        collectUncertainDispatches(c, rows);
        int total = 0;
        int listed = 0;
        try (PreparedStatement ps = c.prepareStatement(REFUSED_SQL); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                total++;
                if (listed >= MAX_ROWS) {
                    continue;
                }
                listed++;
                String runId = rs.getString("run_id");
                String paths = rs.getString("blocked_paths").replace("\n", ", ");
                // The action is a UI route by the panel's contract; there is no run page yet, so it
                // is the dashboard root — the message carries the run id and the paths.
                // The gate judges the branch's cumulative tree, so a refusal can follow checkpoints
                // that were already pushed: the refused change was not delivered, but "nothing
                // reached the remote" would be false for every run that pushed before it tripped.
                rows.add(new AttentionView(CODE, Severity.WARNING, runId,
                        "The push gate refused run " + runId + ": it changed " + paths
                                + ". That change was not pushed; checkpoints this run pushed earlier may "
                                + "already be on its branch. Read the paths, then acknowledge this.",
                        "/",
                        "/api/runs/" + runId + "/attention-ack"));
            }
        }
        if (total > listed) {
            rows.add(new AttentionView(CODE, Severity.WARNING, null,
                    "Further run(s) refused at the push gate, not listed individually.", "/"));
        }
    }

    /**
     * A dispatch nobody can confirm, which only an operator or the run itself can settle.
     *
     * <p>Named individually because the resolution is per run and needs a person to look: at the
     * topic, at the worker's log, or at the forge for a branch. A count would tell an operator that
     * something is unresolved without telling them what to resolve.
     */
    private void collectUncertainDispatches(Connection c, List<AttentionView> rows) throws SQLException {
        int total = 0;
        int listed = 0;
        try (PreparedStatement ps = c.prepareStatement(UNCERTAIN_SQL); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                total++;
                if (listed >= MAX_ROWS) {
                    continue;
                }
                listed++;
                String runId = rs.getString("run_id");
                // NOT dismissable, unlike the gate-refusal row above. That one describes something
                // already over, which an operator reads and acknowledges; this one describes a run
                // that may be executing right now, and silencing it would leave that untracked.
                // Resolving the dispatch is what removes it, which is the panel's own contract.
                rows.add(new AttentionView(UNCERTAIN_CODE, Severity.WARNING, runId,
                        "Run " + runId + " was published but the broker never acknowledged it, so whether"
                                + " it is running is unknown. It will NOT be retried on its own — retrying a"
                                + " run that did start puts a second agent on the same branch and pays for"
                                + " the model twice. If it started, its own result clears this. Otherwise"
                                + " POST {\"neverRan\": true} to /api/runs/" + runId + "/dispatch-resolution"
                                + " and then retry the original request.",
                        "/"));
            }
        }
        if (total > listed) {
            rows.add(new AttentionView(UNCERTAIN_CODE, Severity.WARNING, null,
                    "Further run(s) with an unacknowledged dispatch, not listed individually.", "/"));
        }
    }
}

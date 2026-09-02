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

    void collect(Connection c, List<AttentionView> rows) throws SQLException {
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
                rows.add(new AttentionView(CODE, Severity.WARNING, runId,
                        "The push gate refused run " + runId + ": it changed " + paths
                                + ". Nothing reached the remote. Read the paths, then acknowledge this.",
                        "/",
                        "/api/runs/" + runId + "/attention-ack"));
            }
        }
        if (total > listed) {
            rows.add(new AttentionView(CODE, Severity.WARNING, null,
                    "Further run(s) refused at the push gate, not listed individually.", "/"));
        }
    }
}

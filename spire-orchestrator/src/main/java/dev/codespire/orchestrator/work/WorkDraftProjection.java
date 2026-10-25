package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkItemEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * The SQL side of a generated item's draft (M4 slice 3, A4), projected in the transaction that appends each event:
 * the item's preparation mode, which the copy and draft sweeps select on (decision 2), and one
 * {@code work_prepare_effect} row for each draft attempt started.
 */
@ApplicationScoped
public class WorkDraftProjection {
    private static final Set<String> DRAFT_PHASES = Set.of("spec", "plan");

    void project(Connection c, WorkItemEvent item) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE work_item SET preparation_mode=? WHERE id=?")) {
            ps.setString(1, item.draft() == null ? "composed" : "generated");
            ps.setString(2, item.workItemId());
            ps.executeUpdate();
        }
        if (item.draft() == null || !DRAFT_PHASES.contains(item.phase()) || !"PHASE_STARTED".equals(item.milestone())) return;
        // A replayed projection of the same start inserts nothing: the attempt is the row's identity.
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO work_prepare_effect(attempt_id,work_item_id,generation,kind,state) VALUES (?,?,?,?,'pending')
                ON CONFLICT DO NOTHING
                """)) {
            ps.setObject(1, item.progress().attemptId());
            ps.setString(2, item.workItemId());
            ps.setLong(3, item.generation());
            ps.setString(4, WorkDraftBinding.Kind.valueOf(item.phase().toUpperCase(Locale.ROOT)).name());
            ps.executeUpdate();
        }
    }
}

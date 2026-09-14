package dev.codespire.orchestrator.work;

import dev.codespire.contract.attention.AttentionView;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Current workflow conditions; a superseded policy observation cannot keep an attention row alive. */
public final class WorkAttentionRows {
    private WorkAttentionRows() {}

    public static void collect(Connection c, List<AttentionView> rows) throws SQLException {
        try (var ps = c.prepareStatement("""
                SELECT w.id,w.issue_key FROM work_item w
                JOIN work_repository_policy p ON p.repository_id=w.repository_id AND p.revision=w.policy_revision
                JOIN work_source s ON s.id=w.source_id
                JOIN scm_provider a ON a.id=s.account_id
                JOIN repository r ON r.id=w.repository_id
                WHERE w.policy_clamped AND s.enabled AND a.enabled AND r.enabled
                ORDER BY w.id
                """); var rs = ps.executeQuery()) {
            while (rs.next()) rows.add(new AttentionView("WORK_POLICY_CLAMPED", AttentionView.Severity.WARNING,
                    rs.getString(2), "The requested profile is restricted by the current ceiling, applied labels or admission policy.",
                    "/work-items/" + rs.getString(1)));
        }
        try(var ps=c.prepareStatement("""
                SELECT w.id,w.issue_key,g.state FROM work_item_gate g JOIN work_item w ON w.id=g.work_item_id
                AND w.generation=g.generation AND w.phase=g.phase
                WHERE g.state='OPEN' OR (g.state='EXPIRED' AND w.reason='gate_expired') ORDER BY g.expires_at,g.id
                """);var rs=ps.executeQuery()) {
            while(rs.next()) {
                boolean open="OPEN".equals(rs.getString(3));
                rows.add(new AttentionView(open?"WORK_APPROVAL_OPEN":"WORK_APPROVAL_EXPIRED",AttentionView.Severity.WARNING,
                        rs.getString(2),open?"A workflow phase is waiting for an operator decision.":"An approval expired; explicit re-admission is required.",
                        open?"/approvals":"/work-items/"+rs.getString(1)));
            }
        }
        try(var ps=c.prepareStatement("""
                SELECT s.name FROM work_source s JOIN scm_provider a ON a.id=s.account_id JOIN repository r ON r.id=s.repository_id
                WHERE s.enabled AND (NOT a.enabled OR NOT r.enabled OR s.health NOT IN ('healthy','not_checked')) ORDER BY s.id
                """);var rs=ps.executeQuery()) {
            while(rs.next())rows.add(new AttentionView("WORK_SOURCE_UNAVAILABLE",AttentionView.Severity.WARNING,rs.getString(1),
                    "The tracker or its serving account needs attention before work can continue.","/settings/work-sources"));
        }
    }
}

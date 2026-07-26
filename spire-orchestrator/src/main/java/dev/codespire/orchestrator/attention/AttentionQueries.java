package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import dev.codespire.orchestrator.dlq.DlqRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every orchestrator-side condition, evaluated fresh on each call. Nothing is stored and
 * nothing is cached: the panel's whole correctness argument is that a row cannot outlive
 * the state that produced it.
 *
 * <p>Aggregate conditions (stuck reviews, failed reviews, dead-letter entries) emit ONE row
 * carrying a count rather than one row per record. A stalled broker produces dozens of stuck
 * reviews at once, and thirty rows saying the same thing is the failure this panel exists to
 * avoid.
 */
@ApplicationScoped
public class AttentionQueries {

    @Inject
    DataSource dataSource;

    @Inject
    DlqRepository dlq;

    @ConfigProperty(name = "spire.attention.stuck-minutes")
    int stuckMinutes;

    @ConfigProperty(name = "spire.attention.failed-window-hours")
    int failedWindowHours;

    /** Blockers first, then warnings; stable by code within a severity. */
    public List<AttentionView> collect() {
        List<AttentionView> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            llmProviderRows(c, rows);
            scmProviderRows(c, rows);
            reviewRows(c, rows);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to evaluate attention conditions", e);
        }
        deadLetterRows(rows);
        rows.sort(Comparator.comparing((AttentionView v) -> v.severity().ordinal())
                .thenComparing(AttentionView::code)
                .thenComparing(v -> v.subject() == null ? "" : v.subject()));
        return rows;
    }

    private void llmProviderRows(Connection c, List<AttentionView> rows) throws SQLException {
        if (count(c, "SELECT COUNT(*) FROM llm_provider WHERE enabled = TRUE") == 0) {
            rows.add(new AttentionView("LLM_PROVIDER_MISSING", Severity.BLOCKING, null,
                    "No enabled LLM provider is configured, so no review can run.", "/settings/llm"));
            return; // "no default" is not the actionable problem when there is nothing to default to
        }
        // Mirrors the real gate in LlmProviderRegistry.resolveDefault(): a default that has been
        // disabled resolves to nothing, so it is exactly as blocking as never setting one.
        if (count(c, "SELECT COUNT(*) FROM llm_provider WHERE enabled = TRUE AND is_default = TRUE") == 0) {
            rows.add(new AttentionView("LLM_DEFAULT_MISSING", Severity.BLOCKING, null,
                    "No enabled LLM provider is marked as the default, so no review can run.",
                    "/settings/llm"));
        }
    }

    private void scmProviderRows(Connection c, List<AttentionView> rows) throws SQLException {
        if (count(c, "SELECT COUNT(*) FROM scm_provider WHERE enabled = TRUE") == 0) {
            rows.add(new AttentionView("SCM_PROVIDER_MISSING", Severity.BLOCKING, null,
                    "No enabled source-control provider is configured, so no pull request can be reviewed.",
                    "/settings/providers"));
        }
        // bot_account_id is NOT NULL DEFAULT '' while bot_username is a nullable TEXT, so both
        // blank forms have to be tested. Either field alone is enough to identify the bot.
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT name FROM scm_provider
                 WHERE enabled = TRUE
                   AND (bot_account_id IS NULL OR bot_account_id = '')
                   AND (bot_username   IS NULL OR bot_username   = '')
                 ORDER BY name
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new AttentionView("BOT_IDENTITY_UNRESOLVED", Severity.WARNING, rs.getString("name"),
                        "The bot's own identity could not be resolved, so it cannot recognise its own "
                                + "comments and will not hold a conversation.", "/settings/providers"));
            }
        }
    }

    /**
     * Non-terminal reviews that have stopped moving, and recent terminal failures.
     *
     * <p>Restricted to {@code pr_state = 'OPEN'}: cancel-on-close should already have ended a
     * review whose PR was merged or closed, and a row about yesterday's merged PR is not
     * actionable. Both rows are aggregates carrying a count — one stalled broker should not
     * produce thirty identical rows.
     */
    private void reviewRows(Connection c, List<AttentionView> rows) throws SQLException {
        int stuck = countWithInt(c, """
                SELECT COUNT(*) FROM review_status
                 WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                   AND pr_state = 'OPEN'
                   AND updated_at < now() - make_interval(mins => ?)
                """, stuckMinutes);
        if (stuck > 0) {
            rows.add(new AttentionView("REVIEW_STUCK", Severity.WARNING, null,
                    stuck + " review(s) have not progressed for over " + stuckMinutes
                            + " minutes — a webhook delivery path or a worker may be down.", "/"));
        }
        int failed = countWithInt(c, """
                SELECT COUNT(*) FROM review_status
                 WHERE status = 'FAILED'
                   AND updated_at > now() - make_interval(hours => ?)
                """, failedWindowHours);
        if (failed > 0) {
            rows.add(new AttentionView("REVIEW_FAILED", Severity.WARNING, null,
                    failed + " review(s) failed in the last " + failedWindowHours + " hours.", "/"));
        }
    }

    private static int countWithInt(Connection c, String sql, int arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void deadLetterRows(List<AttentionView> rows) {
        int pending = dlq.countPending();
        if (pending > 0) {
            rows.add(new AttentionView("DLQ_PENDING", Severity.WARNING, null,
                    pending + " message(s) failed processing and are waiting in the dead-letter queue.",
                    "/settings/dlq"));
        }
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}

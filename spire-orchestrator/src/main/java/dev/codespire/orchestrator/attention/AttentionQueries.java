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
import java.util.UUID;

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

    /**
     * Past this many affected reviews the remainder collapses into one summary row. Five is enough
     * to act on individually while keeping a systemic outage from burying the BLOCKING rows that
     * mean nothing works at all.
     */
    private static final int MAX_REVIEW_ROWS = 5;

    @Inject
    DataSource dataSource;

    @Inject
    DlqRepository dlq;

    @ConfigProperty(name = "spire.attention.stuck-minutes")
    int stuckMinutes;

    /** Blockers first, then warnings; stable by code within a severity. */
    public List<AttentionView> collect() {
        List<AttentionView> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            llmProviderRows(c, rows);
            scmProviderRows(c, rows);
            reviewRows(c, rows);
            credentialRows(c, rows);
            costRows(c, rows);
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
                SELECT id, name FROM scm_provider
                 WHERE enabled = TRUE
                   AND (bot_account_id IS NULL OR bot_account_id = '')
                   AND (bot_username   IS NULL OR bot_username   = '')
                 ORDER BY name
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new AttentionView("BOT_IDENTITY_UNRESOLVED", Severity.WARNING, rs.getString("name"),
                        "The bot's own identity could not be resolved, so it cannot recognise its own "
                                + "comments and will not hold a conversation.",
                        editLink("/settings/providers", rs.getObject("id", UUID.class))));
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
     *
     * <p>{@code review_status.status} is the READ MODEL's own lowercase vocabulary — written by
     * {@link dev.codespire.orchestrator.readmodel.ReviewProjection} as {@code reviewing},
     * {@code completed}, {@code failed}, {@code cancelled}, {@code superseded} — and is NOT
     * {@code ReviewState.Status}'s uppercase enum names. Comparing against the enum spelling made
     * every completed review on an open PR read as stuck and made a genuinely failed review
     * invisible, so both predicates fold case and the terminal set names all four terminal values.
     * {@code superseded} is terminal: a run replaced by a newer commit is finished, not stalled.
     */
    private void reviewRows(Connection c, List<AttentionView> rows) throws SQLException {
        rows.addAll(perReviewRows(c, """
                SELECT workspace, slug, pr_id FROM review_status
                 WHERE lower(status) NOT IN ('completed', 'failed', 'cancelled', 'superseded')
                   AND pr_state = 'OPEN'
                   AND updated_at < now() - make_interval(mins => ?)
                   AND (attention_ack_at IS NULL OR updated_at > attention_ack_at)
                 ORDER BY updated_at
                """, stuckMinutes, "REVIEW_STUCK",
                "This review has not progressed for over " + stuckMinutes
                        + " minutes — a webhook delivery path or a worker may be down.",
                "review(s) stalled"));

        rows.addAll(perReviewRows(c, """
                SELECT workspace, slug, pr_id FROM review_status
                 WHERE lower(status) = 'failed'
                   AND (attention_ack_at IS NULL OR updated_at > attention_ack_at)
                 ORDER BY updated_at DESC
                """, null, "REVIEW_FAILED",
                "This review failed. Open it for the reason, then re-run it or dismiss this.",
                "review(s) failed"));
    }

    /**
     * One row per affected review, named and linked to its own detail page.
     *
     * <p>Deliberately not one aggregate row carrying a count. A failed review is a discrete event
     * about a specific record with a page to open, so collapsing several into "3 review(s) failed"
     * throws away the only thing that makes the row actionable. The flooding this originally guarded
     * against turned out to be a symptom of a defect rather than of real failures — a status
     * comparison that reported every completed review as stalled — so the guard is now a cap rather
     * than an unconditional collapse: past {@link #MAX_REVIEW_ROWS} the remainder becomes a single
     * summary row, which keeps a systemic outage from burying the configuration blockers above it.
     *
     * @param minutes bound for the stuck query's interval, or null when the SQL takes no parameter
     */
    private List<AttentionView> perReviewRows(Connection c, String sql, Integer minutes,
                                              String code, String message, String moreNoun)
            throws SQLException {
        List<AttentionView> found = new ArrayList<>();
        int total = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (minutes != null) {
                ps.setInt(1, minutes);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    total++;
                    if (found.size() >= MAX_REVIEW_ROWS) {
                        continue;
                    }
                    String workspace = rs.getString("workspace");
                    String slug = rs.getString("slug");
                    long pr = rs.getLong("pr_id");
                    String repo = workspace + "/" + slug;
                    found.add(new AttentionView(code, Severity.WARNING, repo + "#" + pr, message,
                            "/r/" + workspace + "/" + slug + "/" + pr,
                            "/api/reviews/" + workspace + "/" + slug + "/" + pr + "/attention-ack"));
                }
            }
        }
        int hidden = total - found.size();
        if (hidden > 0) {
            // Never silently truncate: a capped list that does not say so reads as "that is all of
            // them", which is exactly the wrong impression during a systemic outage.
            found.add(new AttentionView(code, Severity.WARNING, null,
                    hidden + " further " + moreNoun + ", not listed individually.", "/"));
        }
        return found;
    }

    /**
     * A credential the provider refused, across all three registries. Only an explicit FALSE
     * qualifies: NULL means never checked, which is not a problem and would otherwise nag for
     * every provider whose Check button was never pressed. Disabled providers are excluded —
     * they cannot break a review.
     */
    private void credentialRows(Connection c, List<AttentionView> rows) throws SQLException {
        for (Registry registry : Registry.values()) {
            credentialRows(c, rows, registry);
        }
    }

    private void credentialRows(Connection c, List<AttentionView> rows, Registry registry)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(registry.rejectedCredentials);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String detail = rs.getString("last_check_error");
                rows.add(new AttentionView("CREDENTIAL_REJECTED", Severity.WARNING, rs.getString("name"),
                        "The " + registry.kind + "'s credential was rejected"
                                + (detail == null || detail.isBlank() ? "." : ": " + detail),
                        editLink(registry.settingsPath, rs.getObject("id", UUID.class))));
            }
        }
    }

    /**
     * The three registries that hold a credential, each carrying the whole query that reads it.
     *
     * <p>One query per registry rather than one query with the table name concatenated in. A table
     * name cannot be a bind parameter — {@link PreparedStatement} binds values, not identifiers — so
     * the previous form had no way to state its safety except a comment asserting the argument was
     * always a constant. That is true and unverifiable, and it is the shape a scanner cannot tell
     * apart from the injectable version. Written out, there is no runtime string building left to
     * reason about, and the cost is three lines of near-identical SQL.
     */
    private enum Registry {

        SCM("""
                SELECT id, name, last_check_error FROM scm_provider
                 WHERE enabled = TRUE AND last_check_ok = FALSE
                 ORDER BY name""",
                "/settings/providers", "source-control provider"),

        LLM("""
                SELECT id, name, last_check_error FROM llm_provider
                 WHERE enabled = TRUE AND last_check_ok = FALSE
                 ORDER BY name""",
                "/settings/llm", "LLM provider"),

        CONTEXT("""
                SELECT id, name, last_check_error FROM context_provider
                 WHERE enabled = TRUE AND last_check_ok = FALSE
                 ORDER BY name""",
                "/settings/context", "context provider");

        private final String rejectedCredentials;
        private final String settingsPath;
        private final String kind;

        Registry(String rejectedCredentials, String settingsPath, String kind) {
            this.rejectedCredentials = rejectedCredentials;
            this.settingsPath = settingsPath;
            this.kind = kind;
        }
    }

    /**
     * A call the ledger could not price (rate missing at charge time) and a call whose reported
     * token breakdown did not sum to its own total (recorded as {@code TOTAL} instead of a split)
     * are both silent by construction: neither contributes to a money total, so a genuine $0.00
     * and "we could not account for N calls" would otherwise look identical on screen.
     *
     * <p>The two rows are made mutually exclusive by construction, each naming its own cause rather
     * than a shared symptom. {@code LlmModelPricer.priceCall} returns EITHER per-type lines OR one
     * {@code TOTAL} line, never both, so a {@code TOTAL} row is always the unreconciled case — even
     * when it is also priced {@code UNKNOWN} (a metered model has no split to apply a rate to). The
     * unpriced query excludes {@code TOTAL} so that case is reported as a mapping defect, not sent to
     * the LLM settings page where entering a rate would do nothing: the call is unpriced BECAUSE it
     * did not reconcile, not because a rate is missing. Do not "simplify" this exclusion away.
     */
    private void costRows(Connection c, List<AttentionView> rows) throws SQLException {
        int unpriced = count(c,
                "SELECT count(DISTINCT call_ref) FROM llm_charge "
                        + "WHERE pricing_mode = 'UNKNOWN' AND token_type <> 'TOTAL'");
        if (unpriced > 0) {
            rows.add(new AttentionView("LLM_COST_UNPRICED", Severity.WARNING, null,
                    unpriced + " LLM call(s) could not be priced, so the reported cost is lower than"
                            + " the real spend.", "/settings/llm"));
        }
        int unreconciled = count(c,
                "SELECT count(DISTINCT call_ref) FROM llm_charge WHERE token_type = 'TOTAL'");
        if (unreconciled > 0) {
            // Not a setting to fix: a reconciliation failure is a TokenUsageMapper defect, so there
            // is no operator page that helps and the action is deliberately null.
            rows.add(new AttentionView("LLM_USAGE_UNRECONCILED", Severity.WARNING, null,
                    unreconciled + " LLM call(s) reported a token breakdown that did not match their"
                            + " own total, so only the total was recorded and those calls are not"
                            + " priced. This is a mapping defect rather than a setting, so the"
                            + " figures will not change until it is fixed.", null));
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

    /**
     * Deep-links to the one provider that needs changing, rather than to a page the operator then
     * has to search. A row that names a specific record should land the reader on that record: with
     * several providers registered, "go to Settings" leaves them working out which row was meant.
     * Aggregate rows (stuck reviews, dead-letter entries) keep a plain page link — they name no
     * single record to open.
     */
    private static String editLink(String page, UUID id) {
        return page + "?edit=" + id;
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}

package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import dev.codespire.orchestrator.caps.CapPolicy;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.caps.SpendWindow;
import dev.codespire.orchestrator.dlq.DlqRepository;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    @Inject
    LlmModelPricer pricer;

    @Inject
    CostAttentionAcks acks;

    @Inject
    SpendGate spendGate;

    @Inject
    CapPolicy capPolicy;

    @Inject
    SpendWindow spendWindow;

    @Inject
    RunAttentionRows runRows;

    @ConfigProperty(name = "spire.attention.stuck-minutes")
    int stuckMinutes;

    /** Blockers first, then warnings; stable by code within a severity. */
    public List<AttentionView> collect() {
        List<AttentionView> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            llmProviderRows(c, rows);
            scmProviderRows(c, rows);
            reviewRows(c, rows);
            degradedReviewRows(c, rows);
            runRows.collect(c, rows);
            credentialRows(c, rows);
            costRows(c, rows);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to evaluate attention conditions", e);
        }
        deadLetterRows(rows);
        capRows(rows);
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
            return; // no default resolves to no model, so there is nothing to price-check
        }
        modelPricingRow(defaultProviderModel(c), rows);
    }

    /**
     * The default provider names a model the ledger cannot price, so no review can start at all.
     *
     * <p>Existence of a provider and of a default is not enough: {@code ResultSaga}'s pre-spend guard
     * refuses to emit {@code GenerateReview} for an unpriceable model, and a refused review sits in
     * {@code REVIEWING} until {@code REVIEW_STUCK} eventually fires — a symptom one level away from
     * the cause, minutes later, on a panel that exists so blocking configuration announces itself
     * BEFORE work fails. The V30 upgrade makes this the guaranteed state of any model that carried a
     * legacy zero price, so it is the first thing an upgraded deployment hits.
     *
     * <p>Priceability comes from {@link LlmModelPricer#isPriceable} rather than a query written here:
     * three guards already share that one rule and a fourth definition of it could disagree with the
     * gate that actually blocks the review. It opens its own connection, which is why the model name is
     * all that is read from {@code c} — do not "optimise" that by threading this connection into the
     * pricer, whose callers are otherwise connectionless.
     */
    private void modelPricingRow(String model, List<AttentionView> rows) {
        if (model == null || pricer.isPriceable(model)) {
            return;
        }
        rows.add(new AttentionView("MODEL_NOT_PRICEABLE", Severity.BLOCKING,
                // A provider saved with no model at all is equally blocking, but naming "" as the
                // subject would render an empty label; the message alone carries it.
                model.isBlank() ? null : model,
                "The default LLM provider's model has no usable pricing, so every review stops before"
                        + " it calls the model. Set input and output rates in Settings → LLM → Models,"
                        + " or mark the model unmetered if it is self-hosted.", "/settings/llm"));
    }

    /** The model the default provider would use, or null when no enabled default is set. */
    private static String defaultProviderModel(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT model FROM llm_provider WHERE enabled = TRUE AND is_default = TRUE");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("model") : null;
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
     * {@code superseded} is terminal: a run replaced by a newer commit is finished, not stalled, and
     * so is {@code refused} — a review a spend cap declined to run has reached its end state, and
     * reporting it here would blame a delivery path for a deliberate policy decision.
     *
     * <p>EVERY query here excludes archived reviews, not just the stuck one. Nothing an operator does makes
     * a failed review un-fail, so archiving it IS the resolution — and a {@code REVIEW_FAILED} row
     * that survived it would be this panel's first permanently-lit row, against the contract that
     * fixing the cause removes the row.
     */
    private void reviewRows(Connection c, List<AttentionView> rows) throws SQLException {
        rows.addAll(perReviewRows(c, """
                SELECT workspace, slug, pr_id FROM review_status
                 WHERE lower(status) NOT IN ('completed', 'failed', 'cancelled', 'superseded', 'refused')
                   AND pr_state = 'OPEN'
                   AND archived_at IS NULL
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
                   AND archived_at IS NULL
                   AND (attention_ack_at IS NULL OR updated_at > attention_ack_at)
                 ORDER BY updated_at DESC
                """, null, "REVIEW_FAILED",
                "This review failed. Open it for the reason, then re-run it or dismiss this.",
                "review(s) failed"));

    }

    /**
     * A run that reached the end of the pipeline having reviewed nothing, or having been cut off
     * part-way through its answer.
     *
     * <p>Not derivable from {@code findings_count = 0}, which a genuinely clean review writes too —
     * hence the dedicated column. Neither query in {@link #reviewRows} sees it: the run completed,
     * so it is neither stalled nor failed.
     *
     * <p>The {@code status} predicate is load-bearing, not decoration. {@code degraded} is written
     * by the outcome projection alone, so it outlives the run that set it: round 2 in flight would
     * otherwise raise this row about a review happening right now, a round that later failed would
     * raise it alongside {@code REVIEW_FAILED} with stale advice, and a {@code refused} review —
     * which was never charged this round — would be told it had been.
     */
    private void degradedReviewRows(Connection c, List<AttentionView> rows) throws SQLException {
        rows.addAll(perReviewRows(c, """
                SELECT workspace, slug, pr_id FROM review_status
                 WHERE degraded
                   AND lower(status) = 'completed'
                   AND archived_at IS NULL
                   AND (attention_ack_at IS NULL OR updated_at > attention_ack_at)
                 ORDER BY updated_at DESC
                """, null, "REVIEW_DEGRADED",
                "This review was charged but did not produce a usable result: the model's response "
                        + "was unparseable or cut off at its output limit. Re-run it, and if it "
                        + "repeats raise the model's output cap.",
                "review(s) produced no usable output"));
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
     * <p>Both are counted only from each condition's acknowledgement watermark, and both are therefore
     * dismissable — the only rows here that are. {@link CostAttentionRow} carries the reasoning for
     * why, along with each row's own query and wording.
     */
    private void costRows(Connection c, List<AttentionView> rows) throws SQLException {
        for (CostAttentionRow row : CostAttentionRow.values()) {
            int calls = countSince(c, row.countQuery(), acks.since(row));
            if (calls > 0) {
                rows.add(row.view(calls));
            }
        }
    }

    /** Acknowledge a ledger-wide cost condition: calls already priced stop being counted. */
    void acknowledge(CostAttentionRow row) {
        acks.acknowledge(row);
    }

    /**
     * Whether deployment-wide usage over the configured window exceeds either cap right now, asking
     * the exact question {@link SpendGate#decide()} asks before every paid call. Sharing the decision,
     * rather than re-comparing usage against the caps here, is what keeps this row and the two
     * enforcement sites (the pre-spend gate, the conversation gate) from drifting on what "over the
     * cap" means — a drift in a money gate is invisible until it fails to fire.
     *
     * <p>Carries no acknowledgement, unlike {@link CostAttentionRow}: those describe calls already made
     * that no fix can un-make, while this describes usage right now, so it clears on its own once the
     * charges that tripped it age out of the window or the operator raises the limit — never stored,
     * never dismissed, exactly this panel's contract.
     */
    // Package-private so the degraded-ledger row can be driven without a DataSource: collect() opens a
    // connection before it ever reaches here, which would make the test assert on the wrong failure.
    void capRows(List<AttentionView> rows) {
        SpendGate.Decision decision = spendGate.decide();
        if (decision.ledgerUnreadable()) {
            rows.add(new AttentionView("CAP_UNENFORCEABLE", Severity.WARNING, null,
                    "A spend cap is configured but its usage ledger could not be read, so no paid call "
                            + "is being refused right now. See the orchestrator log for the database "
                            + "error.", "/settings/general"));
            return;
        }
        if (decision.allowed()) {
            return;
        }
        rows.add(new AttentionView("CAP_REACHED", Severity.BLOCKING, null,
                capitalize(decision.refusal().detail()) + "." + recoveryClause(), "/settings/general"));
    }

    /**
     * The earliest instant any capacity can return — the oldest in-window charge ageing out — stated as
     * the lower bound it is.
     *
     * <p>NOT the instant the cap clears, which is what an earlier wording ("Capacity returns at …")
     * claimed. Ageing one charge out restores capacity only when usage exceeds the cap by no more than
     * that charge's own contribution: with a call cap of 100 and 300 calls in the window, the named
     * instant takes usage to 299 and changes nothing. Computing the true instant means walking charges
     * oldest-first until the remainder falls under the cap; the honest lower bound costs nothing and this
     * project's own ADR argues that a number documented as exact and then observed to be wrong destroys
     * trust in it.
     *
     * <p>Empty when the ledger read failed, which leaves the row's own detail as the only wording rather
     * than a fabricated instant.
     */
    private String recoveryClause() {
        return spendWindow.oldestChargeAt(Instant.now().minus(capPolicy.window()))
                .map(oldest -> " No capacity returns before "
                        + oldest.plus(capPolicy.window()).truncatedTo(ChronoUnit.SECONDS) + ".")
                .orElse("");
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
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

    /** As {@link #count}, for a query bounded by one acknowledgement watermark. */
    private static int countSince(Connection c, String sql, Instant since) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}

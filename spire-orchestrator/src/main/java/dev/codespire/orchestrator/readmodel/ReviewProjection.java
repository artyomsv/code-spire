package dev.codespire.orchestrator.readmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.encryption.EncryptionService;
import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The reviews read model (DATA-MODEL §5) — the single writer to
 * {@code review_status} + {@code review_event}, and the source for GET
 * /api/reviews, GET /api/reviews/{workspace}/{slug}/{pr}, and the /ws/reviews
 * live feed. Projected from the sagas; rebuildable from the event streams.
 */
@ApplicationScoped
public class ReviewProjection {

    private static final Logger LOG = Logger.getLogger(ReviewProjection.class);
    private static final List<String> STEPS = List.of("Received", "Diff", "Context", "Review", "Comments", "Done");
    private static final String UNIQUE_VIOLATION = "23505";
    // Generous: each lost race costs one DB round trip, and N concurrent writers
    // can make one writer lose up to N-1 rounds in a row.
    private static final int SEQ_RETRY_LIMIT = 50;

    // Active-step index for the pipeline stepper (6 = every step done).
    public static final int STAGE_RECEIVED = 0;
    public static final int STAGE_DIFF = 1;
    public static final int STAGE_CONTEXT = 2;
    public static final int STAGE_REVIEW = 3;
    public static final int STAGE_COMMENTS = 4;
    public static final int STAGE_POSTING = 5;
    public static final int STAGE_DONE = 6;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    @Inject
    OpenConnections connections;

    @Inject
    EncryptionService encryption;

    // ---- writes (called by the sagas) --------------------------------------

    /** Upsert the header of a review and reset its status/stage for a (re)run. */
    public void registerHeader(String reviewId, RepoRef repo, long prId, String title, String author,
                               String authorId, String sourceBranch, String destBranch, String sha,
                               String htmlUrl, String providerType, String status, int stage) {
        String sql = """
                INSERT INTO review_status (review_id, workspace, slug, pr_id, title, author, author_id,
                        source_branch, dest_branch, commit_sha, html_url, provider_type, status, stage,
                        attempt, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, now())
                ON CONFLICT (review_id) DO UPDATE SET
                        title = EXCLUDED.title, author = EXCLUDED.author, author_id = EXCLUDED.author_id,
                        source_branch = EXCLUDED.source_branch, dest_branch = EXCLUDED.dest_branch,
                        commit_sha = EXCLUDED.commit_sha, html_url = EXCLUDED.html_url,
                        provider_type = EXCLUDED.provider_type,
                        status = EXCLUDED.status, stage = EXCLUDED.stage, attempt = 1, updated_at = now()
                """;
        update(sql, ps -> {
            ps.setString(1, reviewId);
            ps.setString(2, repo.workspace());
            ps.setString(3, repo.slug());
            ps.setLong(4, prId);
            ps.setString(5, title);
            ps.setString(6, author);
            ps.setString(7, authorId);
            ps.setString(8, sourceBranch);
            ps.setString(9, destBranch);
            ps.setString(10, sha);
            ps.setString(11, htmlUrl);
            ps.setString(12, providerType == null ? "" : providerType);
            ps.setString(13, status);
            ps.setInt(14, stage);
        });
        broadcast(reviewId);
    }

    /** Set status only, keeping the current stage (terminal failures/cancels). */
    public void updateStatus(String reviewId, String status) {
        update("UPDATE review_status SET status = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, status);
            ps.setString(2, reviewId);
        });
        broadcast(reviewId);
    }

    public void updateStatus(String reviewId, String status, int stage) {
        update("UPDATE review_status SET status = ?, stage = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, status);
            ps.setInt(2, stage);
            ps.setString(3, reviewId);
        });
        broadcast(reviewId);
    }

    public void updateStage(String reviewId, int stage) {
        update("UPDATE review_status SET stage = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setInt(1, stage);
            ps.setString(2, reviewId);
        });
        broadcast(reviewId);
    }

    public void setNote(String reviewId, String note) {
        update("UPDATE review_status SET note = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, note);
            ps.setString(2, reviewId);
        });
    }

    /** The current attempt (pipeline run) count for a review; 1 when unknown (C8 retry budget). */
    public int currentAttempt(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT attempt FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("attempt") : 1;
            }
        } catch (SQLException e) {
            LOG.warnf(e, "currentAttempt read failed for %s", reviewId);
            return 1;
        }
    }

    /** Bump the attempt counter and put the review back into REVIEWING at the diff step for a retry. */
    public void retryPipeline(String reviewId, int attempt, String note) {
        update("""
                UPDATE review_status SET attempt = ?, status = 'reviewing', stage = ?, note = ?, updated_at = now()
                WHERE review_id = ?
                """, ps -> {
            ps.setInt(1, attempt);
            ps.setInt(2, STAGE_DIFF);
            ps.setString(3, note);
            ps.setString(4, reviewId);
        });
        broadcast(reviewId);
    }

    /** Record the generated review's findings + usage against the row. */
    public void recordOutcome(String reviewId, ReviewResult result, int stage) {
        // Findings quote the source under review — encrypt at rest (AAD = reviewId).
        String findingsJson = encryption.encryptString(toFindingsJson(result.findings()), reviewId);
        var usage = result.usage();
        update("""
                UPDATE review_status SET findings_count = ?, findings_json = ?, model = ?, tokens_in = ?,
                        tokens_out = ?, cost_millicents = ?, stage = ?, updated_at = now()
                WHERE review_id = ?
                """, ps -> {
            ps.setInt(1, result.findings().size());
            ps.setString(2, findingsJson);
            if (usage == null) {
                ps.setNull(3, java.sql.Types.VARCHAR);
                ps.setNull(4, java.sql.Types.INTEGER);
                ps.setNull(5, java.sql.Types.INTEGER);
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setString(3, usage.model());
                ps.setInt(4, usage.tokensIn());
                ps.setInt(5, usage.tokensOut());
                ps.setLong(6, usage.costMillicents());
            }
            ps.setInt(7, stage);
            ps.setString(8, reviewId);
        });
        broadcast(reviewId);
    }

    /**
     * Append one line to the review's scoped event stream. seq is allocated
     * atomically (MAX+1 inside the INSERT) and guarded by UNIQUE(review_id, seq)
     * (V6): three independently-threaded consumers append to the same review, so
     * a writer that loses the allocation race gets 23505 and simply retries with
     * a fresh MAX — timeline order stays deterministic.
     */
    public void appendEvent(String reviewId, String lane, String type, String detail) {
        String sql = """
                INSERT INTO review_event (review_id, seq, lane, type, detail)
                SELECT ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ? FROM review_event WHERE review_id = ?
                """;
        for (int attempt = 1; attempt <= SEQ_RETRY_LIMIT; attempt++) {
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, reviewId);
                ps.setString(2, lane);
                ps.setString(3, type);
                ps.setString(4, detail == null ? "" : detail);
                ps.setString(5, reviewId);
                ps.executeUpdate();
                return;
            } catch (SQLException e) {
                if (!UNIQUE_VIOLATION.equals(e.getSQLState()) || attempt == SEQ_RETRY_LIMIT) {
                    throw new IllegalStateException("review_event write failed for " + reviewId, e);
                }
                // lost the seq race to a concurrent consumer — recompute MAX and retry
            }
        }
    }

    // ---- reads (REST + WS) -------------------------------------------------

    public List<ReviewSummary> listSummaries() {
        String sql = "SELECT * FROM review_status ORDER BY updated_at DESC";
        List<ReviewSummary> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(toSummary(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list reviews", e);
        }
        return out;
    }

    public Optional<ReviewDetail> loadDetail(String workspace, String slug, long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        try (Connection c = dataSource.getConnection()) {
            ReviewRow row = loadRow(c, reviewId);
            if (row == null) {
                return Optional.empty();
            }
            return Optional.of(toDetail(row, loadEvents(c, reviewId, row.createdAt)));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load review " + reviewId, e);
        }
    }

    // ---- broadcast ---------------------------------------------------------

    private void broadcast(String reviewId) {
        ReviewSummary summary;
        try (Connection c = dataSource.getConnection()) {
            ReviewRow row = loadRow(c, reviewId);
            if (row == null) {
                return; // header not written yet (events can race ahead) — nothing to push
            }
            summary = row.toSummary();
        } catch (SQLException e) {
            LOG.debugf("broadcast load failed for %s: %s", reviewId, e.getMessage());
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize review summary", e);
            return;
        }
        connections.stream()
                .filter(conn -> conn.handshakeRequest().path().endsWith("/ws/reviews"))
                .forEach(conn -> conn.sendText(json).subscribe().with(v -> {
                }, t -> LOG.debugf("WS push failed: %s", t.getMessage())));
    }

    // ---- row mapping -------------------------------------------------------

    private record ReviewRow(String id, String workspace, String slug, long pr, String title, String author,
                             String authorId, String branch, String base, String sha, String htmlUrl,
                             String providerType, String status, int stage, int findings, String findingsJson,
                             String model, Integer tokensIn, Integer tokensOut, Long costMillicents, String note,
                             int attempt, Instant createdAt, Instant updatedAt) {
        ReviewSummary toSummary() {
            return new ReviewSummary(id, workspace, slug, slug, pr, title, author, authorId, branch, base, sha,
                    htmlUrl, providerType, status, stage, findings,
                    costMillicents == null ? 0L : costMillicents, updatedAt);
        }
    }

    private ReviewRow loadRow(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    private ReviewSummary toSummary(ResultSet rs) throws SQLException {
        return readRow(rs).toSummary();
    }

    private ReviewRow readRow(ResultSet rs) throws SQLException {
        return new ReviewRow(
                rs.getString("review_id"), rs.getString("workspace"), rs.getString("slug"), rs.getLong("pr_id"),
                rs.getString("title"), rs.getString("author"), rs.getString("author_id"),
                rs.getString("source_branch"), rs.getString("dest_branch"), rs.getString("commit_sha"),
                rs.getString("html_url"), rs.getString("provider_type"),
                rs.getString("status"), rs.getInt("stage"), rs.getInt("findings_count"),
                rs.getString("findings_json"), rs.getString("model"),
                (Integer) rs.getObject("tokens_in"), (Integer) rs.getObject("tokens_out"),
                (Long) rs.getObject("cost_millicents"), rs.getString("note"), rs.getInt("attempt"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private ReviewDetail toDetail(ReviewRow r, List<ReviewDetail.EventView> events) {
        return new ReviewDetail(r.id, r.workspace, r.slug, r.slug, r.pr, r.title, r.author, r.authorId,
                r.branch, r.base, r.sha, r.htmlUrl, r.providerType, r.status, r.stage, r.findings, r.updatedAt,
                r.attempt, computeStages(r.status, r.stage), List.of("", "", "", "", "", ""),
                parseFindings(r.findingsJson, r.id), usageView(r), r.note, events);
    }

    private List<ReviewDetail.EventView> loadEvents(Connection c, String reviewId, Instant t0) throws SQLException {
        List<ReviewDetail.EventView> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT lane, type, detail, at FROM review_event WHERE review_id = ? ORDER BY seq")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Instant at = rs.getTimestamp("at").toInstant();
                    out.add(new ReviewDetail.EventView(relative(t0, at), rs.getString("lane"),
                            rs.getString("type"), rs.getString("detail")));
                }
            }
        }
        return out;
    }

    // ---- derivations -------------------------------------------------------

    static List<String> computeStages(String status, int stage) {
        String[] s = new String[STEPS.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = "pending";
        }
        switch (status) {
            case "completed" -> java.util.Arrays.fill(s, "done");
            case "observed" -> s[0] = "done";
            case "failed" -> {
                int f = Math.min(Math.max(stage, 0), s.length - 1);
                for (int i = 0; i < f; i++) {
                    s[i] = "done";
                }
                s[f] = "failed";
            }
            case "cancelled", "superseded" -> {
                int done = Math.min(Math.max(stage, 0), s.length);
                for (int i = 0; i < done; i++) {
                    s[i] = "done";
                }
            }
            case "reviewing" -> {
                int active = Math.min(Math.max(stage, 0), s.length);
                for (int i = 0; i < active; i++) {
                    s[i] = "done";
                }
                if (active < s.length) {
                    s[active] = "active";
                }
            }
            default -> { /* all pending */ }
        }
        return List.of(s);
    }

    private static String relative(Instant t0, Instant at) {
        double secs = (at.toEpochMilli() - t0.toEpochMilli()) / 1000.0;
        if (secs < 0) {
            secs = 0;
        }
        return secs < 10 ? String.format(java.util.Locale.ROOT, "+%.1fs", secs) : "+" + Math.round(secs) + "s";
    }

    private ReviewDetail.UsageView usageView(ReviewRow r) {
        if (r.model == null) {
            return null;
        }
        String cost = r.costMillicents == null ? "—"
                : String.format(java.util.Locale.ROOT, "$%.3f", r.costMillicents / 100_000.0);
        return new ReviewDetail.UsageView(r.model,
                r.tokensIn == null ? "—" : String.format(java.util.Locale.ROOT, "%,d", r.tokensIn),
                r.tokensOut == null ? "—" : String.format(java.util.Locale.ROOT, "%,d", r.tokensOut),
                cost, "—");
    }

    private String toFindingsJson(List<Finding> findings) {
        List<ReviewDetail.FindingView> views = findings.stream().map(this::toView).toList();
        try {
            return mapper.writeValueAsString(views);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize findings", e);
            return "[]";
        }
    }

    private List<ReviewDetail.FindingView> parseFindings(String stored, String reviewId) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        String json;
        try {
            json = encryption.decryptString(stored, reviewId);
        } catch (RuntimeException notEncrypted) {
            json = stored; // legacy plaintext row
        }
        try {
            return mapper.readerForListOf(ReviewDetail.FindingView.class).readValue(json);
        } catch (Exception e) {
            LOG.debugf("Failed to parse findings_json: %s", e.getMessage());
            return List.of();
        }
    }

    private ReviewDetail.FindingView toView(Finding f) {
        String loc = f.range() == null ? f.path() : f.path() + ":" + f.range().startLine();
        return new ReviewDetail.FindingView(severitySlug(f.severity()), loc, f.message());
    }

    private static String severitySlug(Severity severity) {
        return switch (severity) {
            case BLOCKER -> "critical";
            case MAJOR -> "warning";
            case MINOR -> "suggestion";
            case INFO, NIT -> "nit";
        };
    }

    // ---- jdbc helper -------------------------------------------------------

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void update(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("review_status write failed", e);
        }
    }
}

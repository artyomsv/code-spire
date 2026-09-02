package dev.codespire.orchestrator.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.encryption.EncryptionService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recovers what the read model still holds into the finding corpus, once (ADR-027, amended).
 *
 * <p>ADR-027 shipped with no backfill, on the stated grounds that a salvage would give "one
 * unrepresentative round per review <b>with no verdicts</b>". Measured against a real deployment
 * that premise is only half true: {@code review_status} keeps a round of findings, and
 * {@code reconciliation_json} keeps the ADR-019 verdicts for every review that ran a second round.
 * On the deployment this was written against that was 14 reviews of 33. Analytics read zero while
 * the reviews list showed 52 findings across 6 repositories, which reads as data loss rather than as
 * a record that has not started.
 *
 * <p><b>What is recovered:</b> the finding, its severity, its location, its repository (through the
 * review), whether it was posted, and its verdict where one was reconciled.
 *
 * <p><b>What is NOT, and is left null rather than guessed:</b>
 * <ul>
 *   <li><b>Category.</b> The field did not exist when these reviews ran, so nothing was collected.
 *       Null already means "the model was not asked", which is exactly true here.</li>
 *   <li><b>The round.</b> Each review overwrites its findings, so the round a finding first appeared
 *       in is genuinely gone. These rows take {@link #BACKFILL_ROUND}, and {@code verdict_round}
 *       stays null so they never enter "median rounds to fix" — filling both from one snapshot would
 *       make that tile compute 1.0 forever, confidently and wrongly, which is the specific harm
 *       ADR-027 was protecting against.</li>
 * </ul>
 *
 * <p>Round <b>0</b> is reserved for exactly this and cannot collide: {@code recordGenerated} refuses
 * any round below 1, so a real round never lands there, and a re-run of the backfill replaces its own
 * rows without touching a real one.
 */
@ApplicationScoped
public class FindingBackfill {

    private static final Logger LOG = Logger.getLogger(FindingBackfill.class);

    /**
     * The round these rows carry: "before this record began".
     *
     * <p>Not 1. Filing a snapshot as round 1 would claim every finding was raised in the first round
     * and, paired with a verdict, that every one was settled immediately.
     */
    static final int BACKFILL_ROUND = 0;

    /** Runs once ever, not once per boot. */
    static final String DONE_KEY = "finding_backfill_v1";

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    void onStartup(@Observes StartupEvent event) {
        try {
            run();
        } catch (RuntimeException e) {
            // A recovery of history must never stop the service from starting. The corpus simply
            // begins empty, which is where it was before this class existed.
            LOG.warn("Could not backfill the finding corpus from the read model", e);
        }
    }

    /**
     * @return the number of findings recovered, or -1 when the backfill had already run
     */
    public int run() {
        try (Connection c = dataSource.getConnection()) {
            if (alreadyDone(c)) {
                return -1;
            }
            int findings = 0;
            int reviews = 0;
            for (StoredReview review : storedReviews(c)) {
                int recovered = backfill(c, review);
                if (recovered > 0) {
                    findings += recovered;
                    reviews++;
                }
            }
            markDone(c);
            if (findings > 0) {
                LOG.infof("Recovered %d findings across %d reviews into the corpus, as round %d "
                        + "(no category, no round history — see FindingBackfill)",
                        findings, reviews, BACKFILL_ROUND);
            }
            return findings;
        } catch (SQLException e) {
            throw new IllegalStateException("finding backfill failed", e);
        }
    }

    private record StoredReview(String reviewId, String commit, String findingsJson, String reconciliationJson) {
    }

    private List<StoredReview> storedReviews(Connection c) throws SQLException {
        // The posted snapshot when there is one -- it is the carried-forward OPEN set and so spans
        // more than the last round -- falling back to the last generated round otherwise. The same
        // COALESCE the ADR-019 prior-run read uses.
        String sql = """
                SELECT review_id, commit_sha,
                       COALESCE(posted_findings_json, open_findings_json, findings_json) AS findings_json,
                       reconciliation_json
                  FROM review_status
                """;
        List<StoredReview> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new StoredReview(rs.getString("review_id"), rs.getString("commit_sha"),
                        rs.getString("findings_json"), rs.getString("reconciliation_json")));
            }
        }
        return out;
    }

    private int backfill(Connection c, StoredReview review) throws SQLException {
        List<ReviewDetail.FindingView> findings = new ArrayList<>(
                parse(review.findingsJson(), review.reviewId(), ReviewDetail.FindingView.class));
        Map<String, String> verdicts = verdictsByLocation(review);
        findings.addAll(closedFindings(review, findings));
        if (findings.isEmpty()) {
            return 0;
        }

        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM review_finding WHERE review_id = ? AND round = ?")) {
            delete.setString(1, review.reviewId());
            delete.setInt(2, BACKFILL_ROUND);
            delete.executeUpdate();
        }

        String sql = """
                INSERT INTO review_finding (review_id, round, commit_sha, path, start_line, end_line,
                                            severity, category, origin, message, thread_ref,
                                            verdict, verdict_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, NULL)
                """;
        int written = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (ReviewDetail.FindingView finding : findings) {
                Location location = Location.parse(finding.loc());
                if (location == null) {
                    continue;
                }
                ps.setString(1, review.reviewId());
                ps.setInt(2, BACKFILL_ROUND);
                ps.setString(3, review.commit() == null ? "" : review.commit());
                ps.setString(4, location.path());
                ps.setInt(5, location.startLine());
                ps.setInt(6, location.endLine());
                ps.setString(7, severityOf(finding.sev()));
                ps.setString(8, finding.origin() == null
                        ? FindingProjection.ORIGIN_REVIEW : finding.origin());
                FindingRows.setNullable(ps, 9,
                        FindingRows.encrypted(encryption, finding.msg(), review.reviewId()));
                FindingRows.setNullable(ps, 10, finding.threadRef());
                FindingRows.setNullable(ps, 11, verdicts.get(finding.loc()));
                ps.addBatch();
                written++;
            }
            ps.executeBatch();
        }
        return written;
    }

    /**
     * The findings that were settled and are therefore absent from the snapshot.
     *
     * <p>The column this reads is the carried-forward <b>open</b> set, so a finding the author fixed
     * has already left it. Recovering verdicts only by matching that set therefore recovers exactly
     * the ones still open — measured on a real deployment, all 15 matches came back
     * {@code UNCHANGED} and not one {@code RESOLVED}, which would leave the "Fixed" column reading
     * zero forever on a repository whose findings plainly do get fixed.
     *
     * <p>{@code reconciliation_json} still carries those settled findings in full — severity,
     * location, message, thread — so they are recovered from there instead. Nothing is invented: the
     * row is the reconciliation's own record of the finding it judged.
     */
    private List<ReviewDetail.FindingView> closedFindings(StoredReview review,
                                                          List<ReviewDetail.FindingView> open) {
        Set<String> alreadyRecovered = new HashSet<>();
        for (ReviewDetail.FindingView finding : open) {
            alreadyRecovered.add(finding.loc());
        }
        List<ReviewDetail.FindingView> closed = new ArrayList<>();
        for (ReviewDetail.ReconciliationView entry
                : parse(review.reconciliationJson(), review.reviewId(), ReviewDetail.ReconciliationView.class)) {
            if (entry.loc() != null && verdictOf(entry.status()) != null
                    && alreadyRecovered.add(entry.loc())) {
                closed.add(new ReviewDetail.FindingView(entry.sev(), entry.loc(), entry.msg(),
                        entry.threadRef(), null));
            }
        }
        return closed;
    }

    /**
     * The reconciled verdicts, keyed by the location string they share with the findings.
     *
     * <p>Matched on location because that is the only key both snapshots carry — a thread ref is
     * null for anything never posted. An entry whose status is not one this build recognises is
     * skipped rather than defaulted: a wrong verdict inflates the dismissal rate that drives
     * learned-memory proposals, so no verdict is the safer answer.
     */
    private Map<String, String> verdictsByLocation(StoredReview review) {
        Map<String, String> verdicts = new HashMap<>();
        for (ReviewDetail.ReconciliationView entry
                : parse(review.reconciliationJson(), review.reviewId(), ReviewDetail.ReconciliationView.class)) {
            String status = verdictOf(entry.status());
            if (status != null && entry.loc() != null) {
                verdicts.put(entry.loc(), status);
            }
        }
        return verdicts;
    }

    /** The UI renders a verdict lower-case with spaces; this is the reverse. */
    private static String verdictOf(String rendered) {
        if (rendered == null || rendered.isBlank()) {
            return null;
        }
        String candidate = rendered.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (FindingVerdict.Status status : FindingVerdict.Status.values()) {
            if (status.name().equals(candidate)) {
                return status.name();
            }
        }
        return null;
    }

    /**
     * The stored slug back to a severity name.
     *
     * <p>Lossy in one direction the corpus can live with: {@code NIT} and {@code INFO} both render as
     * "nit", so both come back as {@code NIT}. Recorded here rather than silently, because it means a
     * backfilled severity split under-counts INFO.
     */
    private static String severityOf(String slug) {
        return switch (slug == null ? "" : slug) {
            case "critical" -> "BLOCKER";
            case "warning" -> "MAJOR";
            case "info" -> "MINOR";
            case "nit" -> "NIT";
            default -> "MINOR";
        };
    }

    /** A {@code path:line} or {@code path:start-end} location, or null when it is neither. */
    private record Location(String path, int startLine, int endLine) {

        static Location parse(String loc) {
            if (loc == null || loc.isBlank()) {
                return null;
            }
            // rsplit: a Windows-style path can carry a colon of its own, and the line marker is last.
            int mark = loc.lastIndexOf(':');
            if (mark <= 0 || mark == loc.length() - 1) {
                return null;
            }
            String path = loc.substring(0, mark);
            String lines = loc.substring(mark + 1);
            try {
                int dash = lines.indexOf('-');
                if (dash < 0) {
                    int line = Integer.parseInt(lines.trim());
                    return new Location(path, line, line);
                }
                int start = Integer.parseInt(lines.substring(0, dash).trim());
                int end = Integer.parseInt(lines.substring(dash + 1).trim());
                return new Location(path, start, Math.max(start, end));
            } catch (NumberFormatException notALine) {
                return null;
            }
        }
    }

    /** Decrypt-and-parse with the same posture the read model uses: never throw, degrade to empty. */
    private <T> List<T> parse(String stored, String reviewId, Class<T> type) {
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
            return mapper.readerForListOf(type).readValue(json);
        } catch (Exception e) {
            LOG.debugf("Backfill could not read a stored column for %s: %s", reviewId, e.getMessage());
            return List.of();
        }
    }

    private boolean alreadyDone(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM app_setting WHERE key = ?")) {
            ps.setString(1, DONE_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void markDone(Connection c) throws SQLException {
        String sql = """
                INSERT INTO app_setting (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO NOTHING
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, DONE_KEY);
            ps.setString(2, String.valueOf(java.time.Instant.now()));
            ps.executeUpdate();
        }
    }
}

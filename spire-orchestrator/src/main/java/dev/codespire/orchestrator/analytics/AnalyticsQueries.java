package dev.codespire.orchestrator.analytics;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * What the reviewer has actually been doing (P4 / FR-11).
 *
 * <p>Every read here runs over {@code review_finding}'s <b>clear</b> columns — path, severity,
 * category, verdict, timestamps. Nothing decrypts to build a chart, which is the point of the
 * encryption split: coordinates and classification are queryable, the message that quotes source is
 * not.
 *
 * <p>Analytics ships with the projection rather than after it, because it is the only way to tell a
 * correct projection from a wrong one — a bad number is visible immediately, a bad row is not. That
 * is ADR-023's sequence, where building the ledger and then reading it back is what exposed four
 * places that had turned <em>unknown</em> into <em>zero</em>.
 */
@ApplicationScoped
public class AnalyticsQueries {

    /**
     * The verdicts that mean the team declined a finding.
     *
     * <p>{@code STILL_OPEN} is deliberately absent: it means the finding survived, not that anyone
     * rejected it. {@code SUPERSEDED} is absent too — the finding was replaced by circumstance rather
     * than judged. Only these two are a human saying "no".
     */
    private static final String DISMISSED = "('ACKNOWLEDGED', 'UNCHANGED')";

    /** One row of the by-kind breakdown. {@code category} is null for uncategorized findings. */
    public record Breakdown(String severity, String category, long raised, long dismissed,
                            long resolved, long unjudged) {
    }

    /** Headline numbers for one lens. {@code dismissalRate} is null when nothing has been judged. */
    public record Totals(long findings, long judged, long dismissed, long resolved,
                         Double dismissalRate, Double medianRoundsToResolve, long reviews,
                         long suppressed) {
    }

    @Inject
    DataSource dataSource;

    /** Repository lens. A null workspace means the whole deployment. */
    public Totals totalsForRepo(String workspace, String slug) {
        return totals(repoFilter(workspace, slug), binderFor(workspace, slug));
    }

    /**
     * Author lens, keyed on {@code (provider_type, author_id)} and never on the author id alone —
     * the same id on two platforms is two unrelated people.
     */
    public Totals totalsForAuthor(String providerType, String authorId) {
        return totals(" AND s.provider_type = ? AND s.author_id = ? ", ps -> {
            ps.setString(1, providerType);
            ps.setString(2, authorId);
        });
    }

    public List<Breakdown> breakdownForRepo(String workspace, String slug) {
        return breakdown(repoFilter(workspace, slug), binderFor(workspace, slug));
    }

    public List<Breakdown> breakdownForAuthor(String providerType, String authorId) {
        return breakdown(" AND s.provider_type = ? AND s.author_id = ? ", ps -> {
            ps.setString(1, providerType);
            ps.setString(2, authorId);
        });
    }

    /**
     * The repositories that have findings, for the index screen.
     *
     * <p>Sourced from the reviews this deployment actually ran, not from the gateway's registrations:
     * a repository registered but never reviewed has nothing to show, and one whose registration was
     * removed still has history worth reading.
     */
    public List<String> repositories() {
        List<String> repos = new ArrayList<>();
        String sql = """
                SELECT DISTINCT s.workspace || '/' || s.slug AS repo
                  FROM review_finding f JOIN review_status s ON s.review_id = f.review_id
                 WHERE s.archived_at IS NULL
                 ORDER BY repo
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                repos.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not list analytics repositories", e);
        }
        return repos;
    }

    private Totals totals(String filter, Binder binder) {
        String sql = """
                SELECT count(*)                                                        AS findings,
                       count(*) FILTER (WHERE f.verdict IS NOT NULL)                   AS judged,
                       count(*) FILTER (WHERE f.verdict IN %s)                         AS dismissed,
                       count(*) FILTER (WHERE f.verdict = 'RESOLVED')                  AS resolved,
                       count(DISTINCT f.review_id)                                     AS reviews,
                       count(*) FILTER (WHERE f.suppressed_by IS NOT NULL)             AS suppressed,
                       -- Rounds TAKEN, not the round raised. ORDER BY f.round answered the median
                       -- round a resolved finding was RAISED in, so a finding raised in round 1 and
                       -- fixed in round 4 contributed 1 and the tile read 1.0 forever -- confidently,
                       -- on any healthy repository. Rows judged before verdict_round existed are
                       -- excluded rather than counted as zero.
                       percentile_cont(0.5) WITHIN GROUP (
                           ORDER BY (f.verdict_round - f.round + 1))
                           FILTER (WHERE f.verdict = 'RESOLVED'
                                     AND f.verdict_round IS NOT NULL)                   AS median_round
                  FROM review_finding f JOIN review_status s ON s.review_id = f.review_id
                 WHERE s.archived_at IS NULL %s
                """.formatted(DISMISSED, filter);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new Totals(0, 0, 0, 0, null, null, 0, 0);
                }
                long judged = rs.getLong("judged");
                long dismissed = rs.getLong("dismissed");
                Double median = (Double) rs.getObject("median_round");
                // Null rather than zero when nothing has been judged. A rate of 0.0 asserts "this team
                // dismisses nothing", which is a claim about them; null says we do not know yet -- the
                // same distinction the nullable verdict column exists for.
                Double rate = judged == 0 ? null : (double) dismissed / judged;
                return new Totals(rs.getLong("findings"), judged, dismissed, rs.getLong("resolved"),
                        rate, median, rs.getLong("reviews"), rs.getLong("suppressed"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not compute analytics totals", e);
        }
    }

    private List<Breakdown> breakdown(String filter, Binder binder) {
        String sql = """
                SELECT f.severity,
                       f.category,
                       count(*)                                            AS raised,
                       count(*) FILTER (WHERE f.verdict IN %s)             AS dismissed,
                       count(*) FILTER (WHERE f.verdict = 'RESOLVED')      AS resolved,
                       count(*) FILTER (WHERE f.verdict IS NULL)           AS unjudged
                  FROM review_finding f JOIN review_status s ON s.review_id = f.review_id
                 WHERE s.archived_at IS NULL %s
                 GROUP BY f.severity, f.category
                 ORDER BY raised DESC, f.severity, f.category
                """.formatted(DISMISSED, filter);
        List<Breakdown> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Breakdown(rs.getString("severity"), rs.getString("category"),
                            rs.getLong("raised"), rs.getLong("dismissed"), rs.getLong("resolved"),
                            rs.getLong("unjudged")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not compute the analytics breakdown", e);
        }
        return rows;
    }

    /** Fragments are chosen from a closed set here; the VALUES are always bound. */
    private static String repoFilter(String workspace, String slug) {
        return workspace == null ? "" : " AND s.workspace = ? AND s.slug = ? ";
    }

    private static Binder binderFor(String workspace, String slug) {
        if (workspace == null) {
            return ps -> { };
        }
        return ps -> {
            ps.setString(1, workspace);
            ps.setString(2, slug);
        };
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}

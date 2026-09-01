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

    /**
     * An SCM account this deployment has actually reviewed.
     *
     * <p>The point of exposing these: an operator identity should be PICKED from what the system
     * has seen, never typed. {@code author_id} is a stable provider id — {@code 3218389}, or a
     * Bitbucket UUID — and it appears nowhere else in the dashboard, so asking an admin to enter one
     * asks them to find a value the product never shows them.
     */
    public record ObservedAuthor(String providerType, String authorId, String displayName,
                                 long reviews) {
    }

    /** One lens's numbers plus its by-kind breakdown. */
    public record Breakdown(String severity, String category, long raised, long dismissed,
                            long resolved, long unjudged) {
    }

    /** Headline numbers for one lens. {@code dismissalRate} is null when nothing has been judged. */
    public record Totals(long findings, long judged, long dismissed, long resolved,
                         Double dismissalRate, Double medianRoundsToResolve, long reviews,
                         long suppressed) {
    }

    /** Lifted out of the method it serves: the text is most of its length and none of its logic. */
    private static final String TOTALS = """
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
                """;;

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
     * One person's numbers across every SCM account they own.
     *
     * <p>A human legitimately has several: the same developer is a GitHub id, a GitLab id and a
     * Bitbucket UUID. Reporting only one of them — which an earlier version did, by taking the first
     * mapping — showed an arbitrary slice of someone's work and called it their activity.
     */
    public Totals totalsForIdentities(List<OperatorIdentities.Link> links) {
        return links.isEmpty() ? emptyTotals() : totals(identityFilter(links), identityBinder(links));
    }

    public List<Breakdown> breakdownForIdentities(List<OperatorIdentities.Link> links) {
        return links.isEmpty() ? List.of() : breakdown(identityFilter(links), identityBinder(links));
    }

    /**
     * Every SCM account this deployment has reviewed, most active first.
     *
     * <p>Sourced from the reviews themselves rather than from any registry: the authors a
     * deployment has seen ARE the candidates, and nothing else knows them.
     */
    public List<ObservedAuthor> observedAuthors() {
        String sql = """
                SELECT s.provider_type, s.author_id, max(s.author) AS display_name,
                       count(*) AS reviews
                  FROM review_status s
                 WHERE s.author_id <> '' AND s.provider_type <> ''
                 GROUP BY s.provider_type, s.author_id
                 ORDER BY reviews DESC, display_name
                """;
        List<ObservedAuthor> authors = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                authors.add(new ObservedAuthor(rs.getString("provider_type"), rs.getString("author_id"),
                        rs.getString("display_name"), rs.getLong("reviews")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not list observed authors", e);
        }
        return authors;
    }

    /** One {@code (provider_type, author_id)} pair per mapped identity, all bound. */
    private static String identityFilter(List<OperatorIdentities.Link> links) {
        return " AND (s.provider_type, s.author_id) IN ("
                + String.join(", ", java.util.Collections.nCopies(links.size(), "(?, ?)")) + ") ";
    }

    private static Binder identityBinder(List<OperatorIdentities.Link> links) {
        return ps -> {
            int index = 1;
            for (OperatorIdentities.Link link : links) {
                ps.setString(index++, link.providerType());
                ps.setString(index++, link.authorId());
            }
        };
    }

    private static Totals emptyTotals() {
        return new Totals(0, 0, 0, 0, null, null, 0, 0);
    }

    /**
     * The repositories that have findings, for the index screen.
     *
     * <p>Sourced from the reviews this deployment actually ran, not from the gateway's registrations:
     * a repository registered but never reviewed has nothing to show, and one whose registration was
     * removed still has history worth reading.
     */
    /**
     * A repository that has recorded findings, with enough beside its name to read AS a repository.
     *
     * <p>The list used to be bare {@code workspace/slug} strings, and on a real deployment an
     * operator read {@code artyomsv/pr-test} as a pull request title. Nothing on the card said
     * otherwise: a slash-separated name with no figure next to it is ambiguous, and it only resolves
     * in favour of "repository" once a count of reviews sits beside it.
     */
    public record RepositoryRow(String repo, long reviews, long findings) {
    }

    public List<RepositoryRow> repositories() {
        List<RepositoryRow> repos = new ArrayList<>();
        String sql = """
                SELECT s.workspace || '/' || s.slug AS repo,
                       count(DISTINCT s.review_id)  AS reviews,
                       count(f.id)                  AS findings
                  FROM review_finding f JOIN review_status s ON s.review_id = f.review_id
                 WHERE s.archived_at IS NULL
                 GROUP BY s.workspace, s.slug
                 ORDER BY findings DESC, repo
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                repos.add(new RepositoryRow(rs.getString("repo"), rs.getLong("reviews"), rs.getLong("findings")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not list analytics repositories", e);
        }
        return repos;
    }

    private Totals totals(String filter, Binder binder) {
        String sql = TOTALS.formatted(DISMISSED, filter);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return emptyTotals();
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

package dev.codespire.orchestrator.analytics;

import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingCategory;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.Severity;
import dev.codespire.orchestrator.readmodel.FindingProjection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic behind the dashboard (P4 / FR-11).
 *
 * <p>Seeded through the real projection and read through the real SQL, because every number here is
 * one an operator will act on and two of them were already wrong in ways a status-code test could not
 * see: the median measured the round a finding was RAISED, and a rate of zero was going to be
 * reported where nothing had been judged.
 */
@QuarkusTest
class AnalyticsQueriesTest {

    private static final String WS = "TEST-ANALYTICS-WS";
    private static final String SLUG = "TEST-REPO";
    private static final String OTHER_SLUG = "TEST-OTHER";
    private static final String COMMIT = "TESTSHA000000000000000000000000000000abc";

    @Inject
    AnalyticsQueries queries;

    @Inject
    FindingProjection findings;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM review_finding WHERE review_id LIKE 'review::TEST-ANALYTICS-WS/%'");
        exec("DELETE FROM review_status WHERE workspace = '" + WS + "'");
    }

    /**
     * A rate of {@code 0.0} asserts "this team dismisses nothing", which is a claim about them. Until
     * something has been judged the honest answer is that nobody knows — the same distinction the
     * nullable verdict column exists for.
     */
    @Test
    void anUnjudgedCorpusReportsNoRateRatherThanZero() {
        seedReview("#1", "TEST-AUTHOR-A");
        findings.recordGenerated(reviewId("#1"), 1, COMMIT,
                List.of(finding("src/A.java", 1, Severity.NIT, FindingCategory.NAMING)));

        AnalyticsQueries.Totals totals = queries.totalsForRepo(WS, SLUG);

        assertEquals(1, totals.findings());
        assertEquals(0, totals.judged());
        assertNull(totals.dismissalRate(), "no judgments means no rate, not a rate of zero");
        assertNull(totals.medianRoundsToResolve());
    }

    @Test
    void theDismissalRateCountsOnlyTheVerdictsThatMeanSomeoneSaidNo() {
        seedReview("#2", "TEST-AUTHOR-A");
        String review = reviewId("#2");
        findings.recordGenerated(review, 1, COMMIT, List.of(
                finding("src/A.java", 1, Severity.NIT, FindingCategory.NAMING),
                finding("src/B.java", 2, Severity.NIT, FindingCategory.NAMING),
                finding("src/C.java", 3, Severity.NIT, FindingCategory.NAMING),
                finding("src/D.java", 4, Severity.NIT, FindingCategory.NAMING)));
        findings.recordVerdicts(review, 2, List.of(
                verdict("src/A.java", 1, FindingVerdict.Status.ACKNOWLEDGED),
                verdict("src/B.java", 2, FindingVerdict.Status.UNCHANGED),
                verdict("src/C.java", 3, FindingVerdict.Status.RESOLVED),
                // STILL_OPEN means the finding survived, not that anyone rejected it.
                verdict("src/D.java", 4, FindingVerdict.Status.STILL_OPEN)));

        AnalyticsQueries.Totals totals = queries.totalsForRepo(WS, SLUG);

        assertEquals(4, totals.judged());
        assertEquals(2, totals.dismissed());
        assertEquals(1, totals.resolved());
        assertEquals(0.5, totals.dismissalRate(), 0.0001);
    }

    /**
     * The tile says "median rounds to FIX". It used to order by the round a finding was raised in, so
     * a finding raised in round 1 and fixed in round 4 contributed 1 — and the number read 1.0 forever
     * on any healthy repository.
     */
    @Test
    void theMedianMeasuresRoundsTakenRatherThanTheRoundRaised() {
        seedReview("#3", "TEST-AUTHOR-A");
        String review = reviewId("#3");
        findings.recordGenerated(review, 1, COMMIT,
                List.of(finding("src/Slow.java", 9, Severity.MAJOR, FindingCategory.CORRECTNESS)));
        findings.recordVerdicts(review, 4,
                List.of(verdict("src/Slow.java", 9, FindingVerdict.Status.RESOLVED)));

        Double median = queries.totalsForRepo(WS, SLUG).medianRoundsToResolve();

        assertEquals(4.0, median, 0.0001,
                "raised in round 1, fixed in round 4 — four rounds, not one");
    }

    @Test
    void oneRepositorysNumbersDoNotLeakIntoAnothers() {
        seedReview("#4", "TEST-AUTHOR-A");
        seedReview("#5", "TEST-AUTHOR-B", OTHER_SLUG);
        findings.recordGenerated(reviewId("#4"), 1, COMMIT,
                List.of(finding("src/A.java", 1, Severity.NIT, FindingCategory.NAMING)));
        findings.recordGenerated(reviewId("#5", OTHER_SLUG), 1, COMMIT, List.of(
                finding("src/B.java", 2, Severity.NIT, FindingCategory.STYLE),
                finding("src/C.java", 3, Severity.NIT, FindingCategory.STYLE)));

        assertEquals(1, queries.totalsForRepo(WS, SLUG).findings());
        assertEquals(2, queries.totalsForRepo(WS, OTHER_SLUG).findings());
    }

    /**
     * The author lens is keyed on the platform as well as the id, because the same
     * {@code providerUserId} on two SCMs belongs to two unrelated people.
     */
    @Test
    void theAuthorLensIsScopedToOnePlatform() {
        seedReview("#6", "TEST-SHARED-ID", SLUG, "github");
        seedReview("#7", "TEST-SHARED-ID", OTHER_SLUG, "gitlab");
        findings.recordGenerated(reviewId("#6"), 1, COMMIT,
                List.of(finding("src/A.java", 1, Severity.NIT, FindingCategory.NAMING)));
        findings.recordGenerated(reviewId("#7", OTHER_SLUG), 1, COMMIT, List.of(
                finding("src/B.java", 2, Severity.NIT, FindingCategory.STYLE),
                finding("src/C.java", 3, Severity.NIT, FindingCategory.STYLE)));

        assertEquals(1, queries.totalsForAuthor("github", "TEST-SHARED-ID").findings());
        assertEquals(2, queries.totalsForAuthor("gitlab", "TEST-SHARED-ID").findings());
    }

    @Test
    void theBreakdownSplitsBySeverityAndCategoryAndLabelsTheUncategorized() {
        seedReview("#8", "TEST-AUTHOR-A");
        findings.recordGenerated(reviewId("#8"), 1, COMMIT, List.of(
                finding("src/A.java", 1, Severity.BLOCKER, FindingCategory.SECURITY),
                finding("src/B.java", 2, Severity.NIT, FindingCategory.NAMING),
                finding("src/C.java", 3, Severity.NIT, null)));

        List<AnalyticsQueries.Breakdown> rows = queries.breakdownForRepo(WS, SLUG);

        assertEquals(3, rows.size(), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> r.category() == null && "NIT".equals(r.severity())),
                "an uncategorized finding is its own row, not folded into OTHER");
    }

    /** An archived review is history, so it leaves the live lens. */
    @Test
    void anArchivedReviewIsExcluded() {
        seedReview("#9", "TEST-AUTHOR-A");
        findings.recordGenerated(reviewId("#9"), 1, COMMIT,
                List.of(finding("src/A.java", 1, Severity.NIT, FindingCategory.NAMING)));
        exec("UPDATE review_status SET archived_at = now() WHERE review_id = '" + reviewId("#9") + "'");

        assertEquals(0, queries.totalsForRepo(WS, SLUG).findings());
    }

    private static Finding finding(String path, int line, Severity severity, FindingCategory category) {
        return new Finding(path, new LineRange(line, line), severity, category, "why", null);
    }

    /**
     * The choices the Operators screen offers, which are the authors this deployment has reviewed.
     *
     * <p>The screen used to ask an admin to TYPE a stable provider id such as {@code 3218389} — a
     * value the product displays nowhere, so the field could only be filled by someone willing to
     * query the database, while every one of those ids had already been recorded dozens of times by
     * the reviews themselves. This is that recording, read back.
     *
     * <p>The count matters as much as the name: an admin picking between two accounts with the same
     * display name needs to see which one this deployment has actually seen work from.
     */
    @Test
    void theReviewedAuthorsAreOfferedWithTheirPlatformAndHowMuchHasBeenSeen() {
        seedAuthor("#801", "TEST-AUTHOR-A", "TEST-A-ID", "github");
        seedAuthor("#802", "TEST-AUTHOR-A", "TEST-A-ID", "github");
        seedAuthor("#803", "TEST-AUTHOR-A", "TEST-A-GITLAB-ID", "gitlab");

        List<AnalyticsQueries.ObservedAuthor> offered = queries.observedAuthors();

        AnalyticsQueries.ObservedAuthor onGithub = only(offered, "github", "TEST-A-ID");
        assertEquals("TEST-AUTHOR-A", onGithub.displayName());
        assertEquals(2L, onGithub.reviews());
        // The same human on a second platform is a SEPARATE choice, never merged: one id on two
        // platforms is two different people, the collision this project has been bitten by twice.
        assertEquals(1L, only(offered, "gitlab", "TEST-A-GITLAB-ID").reviews());
    }

    /**
     * A review whose author the SCM never gave us cannot be picked, because the resulting mapping
     * would match no review at all and leave that operator permanently unlinked with nothing on
     * screen explaining why.
     */
    @Test
    void anAuthorWithNoRecordedIdIsNotOffered() {
        seedAuthor("#804", "TEST-AUTHOR-NAMELESS", "", "github");

        assertTrue(queries.observedAuthors().stream()
                .noneMatch(a -> "TEST-AUTHOR-NAMELESS".equals(a.displayName())));
    }

    /**
     * The same {@code workspace/slug} on two platforms is two repositories, and the list must say so.
     * Collapsing them would give one row whose badge could only name one platform — the collision
     * this project has already been bitten by twice, arriving on the analytics index.
     */
    @Test
    void listsASameNamedRepositoryOnTwoPlatformsAsTwoRows() {
        seedReview("#901", "TEST-A", SLUG, "github");
        seedReview("#902", "TEST-A", SLUG, "gitlab");
        findings.recordGenerated(reviewId("#901"), 1, COMMIT,
                List.of(finding("src/A.java", 1, Severity.MAJOR, FindingCategory.CORRECTNESS)));
        findings.recordGenerated(reviewId("#902"), 1, COMMIT,
                List.of(finding("src/A.java", 1, Severity.MAJOR, FindingCategory.CORRECTNESS)));

        List<AnalyticsQueries.RepositoryRow> rows = queries.repositories().stream()
                .filter(r -> r.repo().equals(WS + "/" + SLUG))
                .toList();

        assertEquals(2, rows.size(), "one row per platform, never one row for both");
        assertTrue(rows.stream().anyMatch(r -> r.providerType().equals("github") && r.findings() == 1));
        assertTrue(rows.stream().anyMatch(r -> r.providerType().equals("gitlab") && r.findings() == 1));
    }

    /** Exactly one row per {@code (platform, id)} pair — the grouping the picker's keys rely on. */
    private static AnalyticsQueries.ObservedAuthor only(List<AnalyticsQueries.ObservedAuthor> authors,
                                                        String providerType, String authorId) {
        List<AnalyticsQueries.ObservedAuthor> matches = authors.stream()
                .filter(a -> a.providerType().equals(providerType) && a.authorId().equals(authorId))
                .toList();
        assertEquals(1, matches.size(), "expected one row for " + providerType + "/" + authorId);
        return matches.getFirst();
    }

    private static FindingVerdict verdict(String path, int line, FindingVerdict.Status status) {
        return new FindingVerdict(null, path, line, status, null);
    }

    private static String reviewId(String pr) {
        return reviewId(pr, SLUG);
    }

    private static String reviewId(String pr, String slug) {
        return "review::" + WS + "/" + slug + pr;
    }

    private void seedReview(String pr, String author) {
        seedReview(pr, author, SLUG, "github");
    }

    private void seedReview(String pr, String author, String slug) {
        seedReview(pr, author, slug, "github");
    }

    private void seedReview(String pr, String author, String slug, String providerType) {
        String sql = """
                INSERT INTO review_status (review_id, workspace, slug, pr_id, author_id,
                                           provider_type, status)
                VALUES (?, ?, ?, ?, ?, ?, 'completed')
                ON CONFLICT (review_id) DO NOTHING
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId(pr, slug));
            ps.setString(2, WS);
            ps.setString(3, slug);
            ps.setLong(4, Long.parseLong(pr.substring(1)));
            ps.setString(5, author);
            ps.setString(6, providerType);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed a review", e);
        }
    }

    /**
     * A review that also records the author's DISPLAY name.
     *
     * <p>{@link #seedReview} leaves it at the column default, which is right for the totals — they
     * key on {@code author_id} alone. The picker shows the name, so this is the only seed that has
     * to set both.
     */
    private void seedAuthor(String pr, String displayName, String authorId, String providerType) {
        String sql = """
                INSERT INTO review_status (review_id, workspace, slug, pr_id, author, author_id,
                                           provider_type, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'completed')
                ON CONFLICT (review_id) DO NOTHING
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId(pr));
            ps.setString(2, WS);
            ps.setString(3, SLUG);
            ps.setLong(4, Long.parseLong(pr.substring(1)));
            ps.setString(5, displayName);
            ps.setString(6, authorId);
            ps.setString(7, providerType);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed an author", e);
        }
    }

    private void exec(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}

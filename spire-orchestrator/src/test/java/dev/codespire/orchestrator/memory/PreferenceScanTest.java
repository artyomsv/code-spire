package dev.codespire.orchestrator.memory;

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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nightly scan, driven end to end (P4 / FR-10).
 *
 * <p>{@code scan()} is package-private and its javadoc says "so a test can drive it" — and until now
 * none did. Everything that decides <em>which</em> groups become proposals lives in its SQL and its
 * glob folding: the never-suppressed floor, the distinct-review floor, the exclusion of uncategorized
 * and already-suppressed rows, and the per-platform scope. {@code qualifies} arithmetic is tested
 * separately; none of that reaches this.
 */
@QuarkusTest
class PreferenceScanTest {

    private static final String WS = "TEST-SCAN-WS";
    private static final String SLUG = "TEST-REPO";
    private static final String COMMIT = "TESTSHA000000000000000000000000000000fed";

    @Inject
    PreferenceProposals proposals;

    @Inject
    LearnedPreferences preferences;

    @Inject
    FindingProjection findings;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM review_finding WHERE review_id LIKE 'review::" + WS + "/%'");
        exec("DELETE FROM review_status WHERE workspace = '" + WS + "'");
        exec("DELETE FROM learned_preference WHERE scope_value LIKE '%" + WS + "%'");
    }

    @Test
    void proposesAGroupThatClearsBothFloors() {
        seedDismissed(FindingCategory.NAMING, Severity.NIT, "src/test/java/dev/T", 12, 2);

        assertEquals(1, proposals.scan());

        LearnedPreferences.Preference proposal = onlyProposal();
        assertEquals("NAMING", proposal.category());
        assertEquals("**/test/**", proposal.pathGlob(), "the glob ladder folds the paths, not the scan");
        assertEquals("github:" + WS + "/" + SLUG, proposal.scopeValue(),
                "the scope carries the platform, or two SCMs sharing a name pool their evidence");
        assertEquals(12, proposal.evidenceTotal());
        assertEquals(2, proposal.evidenceReviews());
    }

    /**
     * The floor that stops a team teaching the reviewer to stop mentioning security. Evidence for it
     * is manufacturable — an {@code ACKNOWLEDGED} verdict comes from the model reading the pull
     * request author's own reply — so this is refused at the source as well as at the filter.
     */
    @Test
    void neverProposesHidingSecurityFindingsHoweverUnanimous() {
        seedDismissed(FindingCategory.SECURITY, Severity.MAJOR, "src/test/java/dev/S", 40, 4);

        assertEquals(0, proposals.scan());
        assertTrue(preferences.all().stream().noneMatch(p -> "SECURITY".equals(p.category())));
    }

    /** Same floor on the other axis: a blocker is by definition not a matter of preference. */
    @Test
    void neverProposesHidingBlockers() {
        seedDismissed(FindingCategory.STYLE, Severity.BLOCKER, "src/test/java/dev/B", 40, 4);

        assertEquals(0, proposals.scan());
    }

    /**
     * One author on one pull request is one person's opinion, however unanimous. The card cannot show
     * an admin the difference, so the scan refuses it rather than presenting it as a team preference.
     */
    @Test
    void neverProposesFromASinglePullRequest() {
        seedDismissed(FindingCategory.NAMING, Severity.NIT, "src/test/java/dev/One", 30, 1);

        assertEquals(0, proposals.scan());
    }

    /**
     * A finding with no category cannot be grouped, and is excluded rather than bucketed into
     * {@code OTHER} — which would put "the model did not say" and "the model said OTHER" in one pile.
     * This is the ordinary case for a repository with a customized review prompt.
     */
    @Test
    void ignoresUncategorizedFindingsEntirely() {
        seedDismissed(null, Severity.NIT, "src/test/java/dev/U", 30, 3);

        assertEquals(0, proposals.scan());
    }

    /** An already-hidden finding must not be re-counted as evidence for the rule that hid it. */
    @Test
    void ignoresFindingsAPreferenceAlreadySuppressed() {
        seedDismissed(FindingCategory.NAMING, Severity.NIT, "src/test/java/dev/T", 12, 2);
        long preferenceId = seedApprovedPreference();
        exec("UPDATE review_finding f SET suppressed_by = " + preferenceId + " FROM review_status s"
                + " WHERE s.review_id = f.review_id AND s.workspace = '" + WS + "'");

        assertEquals(0, proposals.scan(), "suppressed rows are the OUTCOME, never the evidence");
    }

    /**
     * <b>The guarantee the whole {@code REJECTED} state exists for.</b>
     *
     * <p>Asserted across two consecutive runs of the real job, because it depends on {@code path_glob}
     * being derived identically each night — which only a second {@code scan()} can show. Testing the
     * upsert directly proves the SQL, not the determinism it rests on.
     */
    @Test
    void aRejectedProposalIsNotReProposedByTheNextNightsScan() {
        seedDismissed(FindingCategory.NAMING, Severity.NIT, "src/test/java/dev/T", 12, 2);
        assertEquals(1, proposals.scan());
        preferences.decide(onlyProposal().id(), LearnedPreferences.REJECTED, "TEST-ADMIN");

        proposals.scan();

        assertEquals(LearnedPreferences.REJECTED, onlyProposal().state(),
                "a decided group must not be dragged back to PROPOSED by a later run");
    }

    private LearnedPreferences.Preference onlyProposal() {
        List<LearnedPreferences.Preference> mine = preferences.all().stream()
                .filter(p -> p.scopeValue().contains(WS))
                .filter(p -> !"**/nowhere/**".equals(p.pathGlob()))
                .toList();
        assertEquals(1, mine.size(), mine.toString());
        return mine.getFirst();
    }

    /**
     * Seeds {@code count} dismissed findings spread over {@code reviews} distinct pull requests.
     *
     * <p>All of one review's findings go in ONE {@code recordGenerated} call, because that write is
     * delete-then-insert per round: calling it once per finding replaced the round each time and
     * silently wiped the verdicts already recorded against it. The first version of this fixture did
     * exactly that and produced an empty corpus that looked like a scan bug.
     */
    private void seedDismissed(FindingCategory category, Severity severity, String pathStem,
                               int count, int reviews) {
        java.util.Map<String, List<Finding>> byReview = new java.util.LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int pr = i % reviews;
            byReview.computeIfAbsent("review::" + WS + "/" + SLUG + "#" + (100 + pr),
                    k -> new ArrayList<>()).add(new Finding(pathStem + i + ".java",
                            new LineRange(i + 1, i + 1), severity, category, "why", null));
        }
        int pr = 0;
        for (var entry : byReview.entrySet()) {
            seedReview(entry.getKey(), pr++);
            findings.recordGenerated(entry.getKey(), 1, COMMIT, entry.getValue());
            findings.recordVerdicts(entry.getKey(), 2, entry.getValue().stream()
                    .map(f -> new FindingVerdict(null, f.path(), f.range().startLine(),
                            FindingVerdict.Status.ACKNOWLEDGED, null))
                    .toList());
        }
    }

    /** A real preference row, so {@code suppressed_by} can point at something the FK accepts. */
    private long seedApprovedPreference() {
        preferences.propose(new LearnedPreferences.Preference(0, LearnedPreferences.SCOPE_REPO,
                "github:" + WS + "/" + SLUG, "STYLE", "**/nowhere/**", "INFO",
                LearnedPreferences.PROPOSED, 1, 1, 1));
        return preferences.all().stream()
                .filter(p -> "**/nowhere/**".equals(p.pathGlob())).findFirst().orElseThrow().id();
    }
    private void seedReview(String reviewId, int pr) {
        String sql = """
                INSERT INTO review_status (review_id, workspace, slug, pr_id, author_id,
                                           provider_type, status)
                VALUES (?, ?, ?, ?, 'TEST-AUTHOR', 'github', 'completed')
                ON CONFLICT (review_id) DO NOTHING
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            ps.setString(2, WS);
            ps.setString(3, SLUG);
            ps.setLong(4, 100 + pr);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed a review", e);
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

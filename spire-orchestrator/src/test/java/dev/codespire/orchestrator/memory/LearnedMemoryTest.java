package dev.codespire.orchestrator.memory;

import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingCategory;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Learned memory (P4 / FR-10, ADR-027).
 *
 * <p>The property under test throughout is that <b>only an operator's approval changes a review</b>,
 * and that what it changes is visible. A filter that quietly hid the wrong findings would look
 * exactly like a reviewer that had got better — which is why prompt injection was rejected and why
 * these tests are about state transitions rather than about the model.
 */
@QuarkusTest
class LearnedMemoryTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");

    @Inject
    LearnedPreferences preferences;

    @Inject
    PreferenceFilter filter;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM learned_preference WHERE scope_value LIKE 'TEST-%' OR scope_value = ''");
    }

    @Test
    void aProposedPreferenceHidesNothing() {
        propose(FindingCategory.NAMING, "**/test/**", Severity.NIT);

        PreferenceFilter.Filtered filtered = filter.apply(REPO, resultWith(
                finding("src/test/Thing.java", FindingCategory.NAMING, Severity.NIT)));

        assertEquals(1, filtered.result().findings().size(),
                "nothing is hidden until an operator has agreed to hide it");
        assertEquals(0, filtered.suppressedCount());
    }

    @Test
    void anApprovedPreferenceHidesMatchingFindingsAndKeepsTheRest() {
        long id = propose(FindingCategory.NAMING, "**/test/**", Severity.NIT);
        preferences.decide(id, LearnedPreferences.APPROVED, "TEST-ADMIN");

        PreferenceFilter.Filtered filtered = filter.apply(REPO, resultWith(
                finding("src/test/Thing.java", FindingCategory.NAMING, Severity.NIT),
                finding("src/main/Thing.java", FindingCategory.NAMING, Severity.NIT),
                finding("src/test/Other.java", FindingCategory.SECURITY, Severity.NIT)));

        assertEquals(2, filtered.result().findings().size());
        assertEquals(1, filtered.suppressedCount());
        assertEquals(id, filtered.suppressed().get(0).preferenceId(),
                "the suppression names the preference responsible, or nothing can be audited");
    }

    /** Revoking is immediate and needs no rebuild — the property that makes a wrong rule cheap. */
    @Test
    void revokingStopsTheHidingOnTheNextReview() {
        long id = propose(FindingCategory.NAMING, "**/test/**", Severity.NIT);
        preferences.decide(id, LearnedPreferences.APPROVED, "TEST-ADMIN");
        preferences.revoke(id);

        PreferenceFilter.Filtered filtered = filter.apply(REPO, resultWith(
                finding("src/test/Thing.java", FindingCategory.NAMING, Severity.NIT)));

        assertEquals(1, filtered.result().findings().size());
    }

    /**
     * A finding with no category can never be hidden.
     *
     * <p>Not an edge case: a repository with a customized review prompt produces nothing but
     * uncategorized findings, so learned memory is structurally inert there. Better that it hides
     * nothing than that it hides by severity and path alone, which would be a far blunter rule than
     * anyone approved.
     */
    @Test
    void anUncategorizedFindingIsNeverHidden() {
        long id = propose(FindingCategory.NAMING, "**/test/**", Severity.NIT);
        preferences.decide(id, LearnedPreferences.APPROVED, "TEST-ADMIN");

        PreferenceFilter.Filtered filtered = filter.apply(REPO,
                resultWith(new Finding("src/test/Thing.java", new LineRange(1, 1), Severity.NIT,
                        null, "no category", null)));

        assertEquals(1, filtered.result().findings().size());
    }

    /** A repo-scoped preference speaks only for its own repository. */
    @Test
    void aRepoScopedPreferenceDoesNotHideInAnotherRepository() {
        long id = propose(FindingCategory.NAMING, "**/test/**", Severity.NIT);
        preferences.decide(id, LearnedPreferences.APPROVED, "TEST-ADMIN");

        PreferenceFilter.Filtered filtered = filter.apply(new RepoRef("TEST-WS", "TEST-OTHER"),
                resultWith(finding("src/test/Thing.java", FindingCategory.NAMING, Severity.NIT)));

        assertEquals(1, filtered.result().findings().size());
    }

    /**
     * A rejected group is a question already answered.
     *
     * <p>Without this the nightly job asks the same thing every night, and an operator learns to
     * ignore the screen — which is how a genuinely useful proposal gets missed.
     */
    @Test
    void aRejectedProposalIsNotReProposed() {
        long id = propose(FindingCategory.NAMING, "**/test/**", Severity.NIT);
        preferences.decide(id, LearnedPreferences.REJECTED, "TEST-ADMIN");

        propose(FindingCategory.NAMING, "**/test/**", Severity.NIT);

        assertEquals(LearnedPreferences.REJECTED, stateOf(id),
                "a decided group must not be dragged back to PROPOSED by the next scan");
    }

    @Test
    void anApprovedPreferenceIsNotQuietlyReopenedByTheNextScan() {
        long id = propose(FindingCategory.NAMING, "**/test/**", Severity.NIT);
        preferences.decide(id, LearnedPreferences.APPROVED, "TEST-ADMIN");

        propose(FindingCategory.NAMING, "**/test/**", Severity.NIT);

        assertEquals(LearnedPreferences.APPROVED, stateOf(id));
    }

    /**
     * A decided row keeps the evidence it was decided on.
     *
     * <p>This is what the {@code WHERE state = PROPOSED} guard on the upsert actually protects, and
     * the two state tests above do NOT prove it: the upsert never writes {@code state}, so a decided
     * row stays decided whether the guard is there or not, and both of those tests held with it
     * deleted. They are kept because the invariant is worth locking; this is the one that
     * discriminates.
     *
     * <p>It matters because the card shows the evidence an operator judged. Silently moving those
     * numbers under a decision already made would leave the screen describing a proposal nobody
     * approved.
     */
    @Test
    void aDecidedPreferenceKeepsTheEvidenceItWasDecidedOn() {
        long id = propose(FindingCategory.NAMING, "**/test/**", Severity.NIT);
        preferences.decide(id, LearnedPreferences.REJECTED, "TEST-ADMIN");

        preferences.propose(new LearnedPreferences.Preference(0, LearnedPreferences.SCOPE_REPO,
                REPO.workspace() + "/" + REPO.slug(), FindingCategory.NAMING.name(), "**/test/**",
                Severity.NIT.name(), LearnedPreferences.PROPOSED, 999, 998, 9));

        LearnedPreferences.Preference stored = preferences.all().stream()
                .filter(p -> p.id() == id).findFirst().orElseThrow();
        assertEquals(16, stored.evidenceTotal(), "a decided row must not be rewritten by a rescan");
        assertEquals(14, stored.evidenceDismissed());
    }

    /** With nothing approved, the result object is returned untouched rather than rebuilt. */
    @Test
    void aDeploymentWithNoPreferencesIsUnaffected() {
        ReviewResult original = resultWith(finding("src/main/A.java", FindingCategory.STYLE, Severity.NIT));

        PreferenceFilter.Filtered filtered = filter.apply(REPO, original);

        assertTrue(filtered.result() == original, "the ordinary path should not rebuild the result");
        assertTrue(filtered.suppressed().isEmpty());
    }

    /**
     * The glob ladder has to be deterministic, because group identity depends on it: a rejected
     * group must be recognisable tomorrow, and the filter must match what the proposer grouped.
     */
    @Test
    void theGlobLadderIsStableAndCoarse() {
        assertEquals("**/test/**", PathGlobs.of("src/test/java/dev/Thing.java"));
        assertEquals("**/*.test.*", PathGlobs.of("spire-ui/src/components/Thing.test.tsx"));
        assertEquals("spire-ui/src/**", PathGlobs.of("spire-ui/src/components/Thing.tsx"));
        // A file at the root groups with nothing: a preference about one file is not a preference.
        assertNull(PathGlobs.of("README.md"));
        assertFalse(PathGlobs.matches("**/test/**", "src/main/java/dev/Thing.java"));
    }

    private long propose(FindingCategory category, String glob, Severity severity) {
        preferences.propose(new LearnedPreferences.Preference(0, LearnedPreferences.SCOPE_REPO,
                REPO.workspace() + "/" + REPO.slug(), category.name(), glob, severity.name(),
                LearnedPreferences.PROPOSED, 16, 14, 3));
        return preferences.all().stream()
                .filter(p -> p.category().equals(category.name()) && p.pathGlob().equals(glob))
                .findFirst().orElseThrow().id();
    }

    private static Finding finding(String path, FindingCategory category, Severity severity) {
        return new Finding(path, new LineRange(1, 1), severity, category, "why it matters", null);
    }

    private static ReviewResult resultWith(Finding... findings) {
        return new ReviewResult(List.of(findings), "TEST summary", null, false, false);
    }

    private String stateOf(long id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT state FROM learned_preference WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the preference state", e);
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

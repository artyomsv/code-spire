package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
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
 * A purge hard-deletes the review row and stamps its charges. The PR is then registrable again, and
 * {@code review_id} is stable per PR — so the new review reads the old rows unless every ledger query
 * filters. This is the only test that exercises what {@code llm_charge.archived_at} is for.
 *
 * <p>No purge exists yet, so the purge is simulated through the {@code DataSource} directly: stamp the
 * charges, delete the {@code review_status} row, re-register the PR. Writing it that way is the point —
 * it is what makes the real purge safe to write later, because the filters it depends on are already
 * pinned.
 *
 * <p>Every assertion about what the new review must NOT see is paired with an assertion that the OLD
 * review did see it. Four of the six read as {@code ""}, {@code 0} or empty on a review that never had a
 * ledger at all, so without the pre-purge half this whole test would pass with every filter missing.
 */
@QuarkusTest
class PurgedChargeIsolationIT {

    /**
     * The vendor badge is a catalog lookup on the charged model's name, so the model has to BE in the
     * catalog for the badge to carry anything — an uncatalogued model renders the very empty string the
     * filter is supposed to produce, and the assertion could not fail.
     *
     * <p>Named for this suite rather than reusing the repo-wide {@code TEST-MODEL}, which several other
     * suites catalog as {@code openai} on a first-one-wins check. Sharing it would make this class read
     * whichever vendor ran first, and the cleanup below would delete a row those suites rely on.
     */
    private static final String OWN_MODEL = "TEST-PURGED-MODEL";
    private static final String OWN_VENDOR = "TEST-PURGED-VENDOR";

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @AfterEach
    void forgetTheCatalogEntry() {
        update("DELETE FROM llm_model WHERE name = ?", OWN_MODEL);
    }

    @Test
    void aReRegisteredPrInheritsNothingFromAPurgedReview() {
        catalogTheBadgeModel();
        long pr = ReviewFixtures.newPr();
        String id = ReviewFixtures.reviewIdFor(pr);
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        chargeOneCallThatCouldNotBePriced(id, pr);

        assertEverySiteReportsThePurgedReviewsLedger(id, pr);

        stampEveryCharge(id);
        deleteTheReviewRowAsAPurgeWould(id);
        ReviewFixtures.seedCompletedReviewWithoutCharges(projection, pr);

        ReviewProjection.CostSummary cost = projection.costOf(id);
        assertEquals(0L, cost.knownCostMillicents(),
                "a re-registered PR must not inherit a purged review's spend");
        assertEquals(0, cost.unpricedCalls(), "nor its unpriced-call count");
        assertNull(cost.lastModel(), "nor the model that spent it");
        assertTrue(projection.chargeLines(id).isEmpty(), "nor its charge lines");

        ReviewSummary row = rowFor(pr);
        assertEquals(0L, row.costMillicents(), "nor any of that on the list row");
        assertEquals("", row.model(), "nor its model badge");
        assertEquals("", row.llmType(), "nor the vendor badge derived from that model");
        assertEquals(0, row.unpricedCalls(), "nor the list row's unpriced-call count");
    }

    /**
     * The half that makes the assertions above mean something: before the purge, every one of those six
     * sites reports the ledger. {@code llmType} in particular is read through a subquery NESTED inside a
     * query over {@code llm_model}, which is the single easiest site to leave unfiltered.
     */
    private void assertEverySiteReportsThePurgedReviewsLedger(String id, long pr) {
        ReviewProjection.CostSummary cost = projection.costOf(id);
        assertTrue(cost.knownCostMillicents() > 0, "the fixture recorded real spend");
        assertEquals(1, cost.unpricedCalls(), "and one call it could not price");
        assertEquals(OWN_MODEL, cost.lastModel(), "the most recent call names the model");
        assertEquals(3, projection.chargeLines(id).size());

        ReviewSummary row = rowFor(pr);
        assertTrue(row.costMillicents() > 0);
        assertEquals(OWN_MODEL, row.model());
        assertEquals(OWN_VENDOR, row.llmType());
        assertEquals(1, row.unpricedCalls());
    }

    /**
     * One unpriceable call beside the fixture's priced ones, and the review's most recent — so it
     * supplies both badges as well as the unpriced count. Without it that count would read zero before
     * AND after the purge, so its assertion could not fail; a catalogued model is still unpriceable
     * when it has no rates, so this is a real shape rather than a contrived one.
     */
    private void chargeOneCallThatCouldNotBePriced(String reviewId, long pr) {
        projection.recordCharges(new ChargeCall(reviewId, "CANARY-UNPRICED-" + pr, ChargeKind.REVIEW,
                OWN_MODEL, List.of(ChargeLine.unknown(TokenType.CACHE_WRITE, 500_000))));
    }

    private ReviewSummary rowFor(long pr) {
        return projection.listSummaries(false).stream()
                .filter(summary -> summary.pr() == pr)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no live list row for PR " + pr));
    }

    private void catalogTheBadgeModel() {
        update("""
                INSERT INTO llm_model (id, type, name, label, pricing_mode)
                VALUES (gen_random_uuid(), ?, ?, ?, 'METERED')
                ON CONFLICT (name) DO NOTHING
                """, OWN_VENDOR, OWN_MODEL, OWN_MODEL);
    }

    /** What the future purge will do in the same transaction as the delete below. */
    private void stampEveryCharge(String reviewId) {
        update("UPDATE llm_charge SET archived_at = now() WHERE review_id = ?", reviewId);
    }

    private void deleteTheReviewRowAsAPurgeWould(String reviewId) {
        update("DELETE FROM review_status WHERE review_id = ?", reviewId);
    }

    private void update(String sql, String... binds) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < binds.length; i++) {
                ps.setString(i + 1, binds[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}

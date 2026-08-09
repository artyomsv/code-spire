package dev.codespire.orchestrator.caps;

import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.readmodel.ArchiveOutcome;
import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cap's view of the ledger. Three properties this pins, each of which fails silently if broken.
 *
 * <p>Every assertion is a DELTA against a baseline measured moments earlier, never an absolute figure.
 * This read is deployment-wide by design and the module shares one database across test classes, so
 * another suite's charges are always in the window — an {@code assertEquals(0L, spent)} here would be
 * asserting that no other suite ran first.
 */
@QuarkusTest
class SpendWindowIT {

    private static final Duration LAST_HOUR = Duration.ofHours(1);

    @Inject
    SpendWindow window;

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    /**
     * Archiving must not refund the budget, or archiving becomes a way to reset the cap and an operator
     * tidying the reviews list silently buys themselves more spend.
     *
     * <p>On its own this does NOT discriminate against the mistake the design warns about, and saying so
     * is the point: {@code archiveReview} stamps {@code review_status.archived_at} and never
     * {@code llm_charge.archived_at}, so copying the ledger filter from the ten reads beside this one
     * would leave this test green. What it does catch is the other shape of the same error — joining to
     * {@code review_status} to count only live reviews. {@link #aStampedChargeStillCountsAgainstTheCap}
     * is the guard for the copied column filter.
     */
    @Test
    void archivingAReviewDoesNotRestoreBudget() {
        long baseline = lastHour().spentMillicents();
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);

        long withCharges = lastHour().spentMillicents();
        assertTrue(withCharges > baseline, "the fixture must record real spend or this proves nothing");

        assertEquals(ArchiveOutcome.ARCHIVED,
                projection.archiveReview(ReviewFixtures.WS, ReviewFixtures.REPO, pr));

        assertEquals(withCharges, lastHour().spentMillicents(),
                "archiving must not refund the budget, or archiving becomes a way to reset the cap");
    }

    /**
     * The guard for the single most likely implementation error in this feature: ten ledger reads beside
     * this one filter {@code archived_at IS NULL}, and this one must not.
     *
     * <p>A stamped charge is what a future purge leaves behind. Every other read excludes it because it
     * belongs to a review that no longer has a page; a cap does not care about pages, only about money
     * already spent. If a stamped charge stopped counting, purging would hand budget back exactly as
     * archiving would — the same defeat, one button along.
     */
    @Test
    void aStampedChargeStillCountsAgainstTheCap() {
        long baseline = lastHour().spentMillicents();
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);

        long withCharges = lastHour().spentMillicents();
        assertTrue(withCharges > baseline, "the fixture must record real spend or this proves nothing");

        // What the future purge stamps, in the transaction that deletes the review row.
        update("UPDATE llm_charge SET archived_at = now() WHERE review_id = ?",
                ReviewFixtures.reviewIdFor(pr));

        assertEquals(withCharges, lastHour().spentMillicents(),
                "the cap reads every charge in the window — a filter here refunds a purged review's spend");
    }

    /**
     * The whole reason the cap carries two axes. An {@code UNKNOWN}-priced row has a NULL cost that
     * {@code SUM} skips, so a money-only cap cannot see the call at all — which is precisely the
     * accounting hole ADR-023 identified.
     */
    @Test
    void callsAreCountedEvenWhenTheyCouldNotBePriced() {
        SpendWindow.Usage before = lastHour();
        long pr = ReviewFixtures.newPr();
        seedUnknownPricedCall(pr);

        SpendWindow.Usage after = lastHour();

        assertEquals(before.spentMillicents(), after.spentMillicents(), "SUM skips a NULL cost");
        assertEquals(before.calls() + 1, after.calls(),
                "but the call axis still counts it — this is the ADR-023 hole");
    }

    /** Capacity returns as usage ages out, so a charge older than the window contributes to neither axis. */
    @Test
    void chargesOutsideTheWindowAreExcluded() {
        SpendWindow.Usage before = lastHour();
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        assertTrue(lastHour().spentMillicents() > before.spentMillicents(),
                "the fixture must land inside the window first, or ageing it out proves nothing");

        update("UPDATE llm_charge SET priced_at = now() - interval '2 days' WHERE review_id = ?",
                ReviewFixtures.reviewIdFor(pr));

        SpendWindow.Usage after = lastHour();
        assertEquals(before.spentMillicents(), after.spentMillicents(), "aged-out money stops counting");
        assertEquals(before.calls(), after.calls(), "and so does the aged-out call");
    }

    /**
     * A read failure reports no usage instead of propagating. A cap that refuses every review because
     * its own query failed is worse than a cap that misses a window, and the failure is not silent — it
     * logs at ERROR, and the ledger write path shares this connection pool, so it reports the outage too.
     */
    @Test
    void aReadFailureFailsOpenRatherThanRefusingEveryReview() {
        SpendWindow broken = new SpendWindow();
        broken.dataSource = unreachableDataSource();

        SpendWindow.Usage usage = broken.since(Instant.now().minus(LAST_HOUR));

        assertEquals(0L, usage.spentMillicents(), "an unreadable ledger reports no spend, not a refusal");
        assertEquals(0, usage.calls());
    }

    private SpendWindow.Usage lastHour() {
        return window.since(Instant.now().minus(LAST_HOUR));
    }

    /**
     * One call the ledger could not price, written through the production writer rather than raw SQL so
     * the fixture cannot drift into a row shape {@code LlmModelPricer} would never produce.
     */
    private void seedUnknownPricedCall(long pr) {
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        projection.recordCharges(new ChargeCall(reviewId, "CANARY-UNPRICED-" + pr, ChargeKind.REVIEW,
                "TEST-MODEL", List.of(ChargeLine.unknown(TokenType.INPUT, 1_000_000))));
    }

    /**
     * A DataSource that cannot hand out a connection. A proxy rather than a hand-written stub so it stays
     * one method long — what is under test is the failure, not the interface.
     */
    private static DataSource unreachableDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                SpendWindowIT.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "TEST-UNREACHABLE-DATASOURCE";
                    }
                    throw new SQLException("TEST-DB-UNREACHABLE");
                });
    }

    private void update(String sql, String bind) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, bind);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}

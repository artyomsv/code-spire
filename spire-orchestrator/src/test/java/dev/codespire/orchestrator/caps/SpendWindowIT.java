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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cap's view of the ledger — both what it reports and when it says capacity can return. Every
 * property here fails silently if broken.
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
     * A read failure is reported as a failed read, not as an empty window. The gate still fails open —
     * a cap that refuses every review because its own query failed is an outage that looks like policy —
     * but {@code Usage(0, 0)} made "unreadable" and "nothing spent" the same answer, so the attention
     * panel reported health while the cap was refusing nothing.
     */
    @Test
    void aReadFailureIsDistinguishableFromAnEmptyWindow() {
        SpendWindow broken = new SpendWindow();
        broken.dataSource = unreachableDataSource();

        assertTrue(broken.since(Instant.now().minus(LAST_HOUR)).isEmpty(),
                "an unreadable ledger must not answer the same as a window with no charges");
        assertTrue(lastHour().spentMillicents() >= 0L,
                "and a readable one still answers, so the empty above is the failure and not the shape");
    }

    /**
     * {@code oldestChargeAt} had no direct test at all — {@code CapAttentionTest} only asserted that the
     * attention row's message CONTAINED the recovery wording, never that the instant in it was right. So
     * the query that names when capacity can return could have returned any timestamp in the table.
     *
     * <p>Deterministic despite the shared database, without a delta: the window starts EXACTLY at the
     * older charge's own timestamp. Every ambient row is then either older than the anchor (excluded by
     * the {@code from} bound) or newer than it (so it cannot be smaller), which makes the anchor the
     * minimum by construction rather than by hoping no other suite wrote first.
     *
     * <p><b>A SECOND, newer charge is seeded on purpose.</b> Without it the fixture leaves one distinct
     * timestamp in scope, where {@code MIN} and {@code MAX} return the same row — and this test passed
     * under a {@code MIN} → {@code MAX} mutation when it happened to run first in the class. The
     * newer row is what makes "earliest" mean something.
     */
    @Test
    void theOldestChargeIsTheEarliestOneInsideTheWindow() {
        // Milliseconds: the column is TIMESTAMPTZ (microseconds) and Instant carries nanos, so an
        // untruncated anchor would compare a value against a rounded copy of itself.
        Instant anchor = Instant.now().minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MILLIS);
        seedChargesAt(anchor);
        seedChargesNow();

        assertEquals(java.util.Optional.of(anchor), window.oldestChargeAt(anchor),
                "the earliest charge at or after the window start is the one capacity waits on, and a "
                        + "newer charge is in scope too — so this cannot be satisfied by any aggregate");
    }

    /**
     * The other half, and the one that catches a query ignoring its {@code from} bound: move the window
     * start one millisecond past the anchor and that charge must stop being the answer. The newer charge
     * is still in scope, so there IS a valid later answer — a broken bound would return the anchor.
     */
    @Test
    void aChargeBeforeTheWindowStartIsNotTheOldestInIt() {
        Instant anchor = Instant.now().minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MILLIS);
        seedChargesAt(anchor);
        seedChargesNow();
        assertEquals(java.util.Optional.of(anchor), window.oldestChargeAt(anchor),
                "the charge must be inside the window first, or excluding it proves nothing");

        assertTrue(window.oldestChargeAt(anchor.plusMillis(1)).map(anchor::isBefore).orElse(false),
                "a charge older than the window start cannot be what capacity waits on, and the newer "
                        + "charge means an empty answer would be wrong too");
    }

    private SpendWindow.Usage lastHour() {
        return window.since(Instant.now().minus(LAST_HOUR)).orElseThrow(
                () -> new AssertionError("the ledger must be readable in this suite"));
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

    /** A review whose charges all sit at one exact instant, so the window can start exactly on them. */
    private void seedChargesAt(Instant priced) {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        backdateChargesTo(ReviewFixtures.reviewIdFor(pr), priced);
    }

    /** A second review's charges, left where the production writer put them: now. */
    private void seedChargesNow() {
        ReviewFixtures.seedCompletedReviewWithCharges(projection, ReviewFixtures.newPr());
    }

    /** Every charge line of one review moved to one exact instant — the window anchor. */
    private void backdateChargesTo(String reviewId, Instant priced) {
        String sql = "UPDATE llm_charge SET priced_at = ? WHERE review_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(priced));
            ps.setString(2, reviewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
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

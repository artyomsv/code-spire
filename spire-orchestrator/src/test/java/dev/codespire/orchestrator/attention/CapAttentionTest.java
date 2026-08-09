package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.caps.CapPolicy;
import dev.codespire.orchestrator.caps.SpendWindow;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CAP_REACHED} describes usage right NOW, unlike {@link CostAttentionRow}'s two rows about the
 * past — so it carries no acknowledgement, and the test that matters is not that it appears but that it
 * clears on its own once the charges that tripped it age out of the window. A row that only proved the
 * first half would equally be satisfied by a permanently-lit row, which is exactly what this panel's
 * "fixing the cause removes the row" contract forbids.
 *
 * <p>Drives the call axis, not the money one: it can be tripped with zero real spend (UNMETERED
 * charges), same as {@code SpendCapGateTest} prefers. Every assertion against the cap is a delta
 * against a baseline measured moments earlier — the module shares one Postgres container across
 * suites, so ambient charges from other tests are already inside the window.
 */
@QuarkusTest
class CapAttentionTest {

    @Inject
    AttentionQueries queries;

    @Inject
    ReviewProjection projection;

    @Inject
    CapPolicy capPolicy;

    @Inject
    SpendWindow spendWindow;

    @Inject
    AppSettingRepository settings;

    @Inject
    DataSource dataSource;

    @AfterEach
    void clearTheCap() {
        settings.set(CapPolicy.KEY_CALLS, "");
        settings.set(CapPolicy.KEY_SPEND_CAP, "");
    }

    @Test
    void aCapReachedRowAppearsWhileOverAndClearsOnceUsageAgesOut() {
        SpendWindow.Usage baseline = spendWindow.since(Instant.now().minus(capPolicy.window()));
        settings.set(CapPolicy.KEY_CALLS, String.valueOf(baseline.calls() + 2));
        String reviewId = ReviewFixtures.reviewIdFor(ReviewFixtures.newPr());
        seedUnmeteredCalls(reviewId, 3);

        List<AttentionView> whileOver = queries.collect();
        AttentionView row = whileOver.stream()
                .filter(v -> "CAP_REACHED".equals(v.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CAP_REACHED row in " + whileOver));
        assertEquals(AttentionView.Severity.BLOCKING, row.severity(),
                "every future paid call is refused until capacity returns -- that is what BLOCKING means");
        assertTrue(row.message().toLowerCase().contains("call cap"),
                "names which axis tripped: " + row.message());
        assertTrue(row.message().contains("Capacity returns at"),
                "a rolling window can name the exact instant capacity returns: " + row.message());
        assertNull(row.dismiss(), "current state, not a past event -- there is nothing to acknowledge");

        backdateCharges(reviewId);

        List<AttentionView> afterAgingOut = queries.collect();
        assertFalse(afterAgingOut.stream().anyMatch(v -> "CAP_REACHED".equals(v.code())),
                "the half that proves this is current state, not a row that merely CAN appear");
    }

    @Test
    void anUnsetCapNeverRaisesTheRowHoweverMuchIsSpent() {
        seedUnmeteredCalls(ReviewFixtures.reviewIdFor(ReviewFixtures.newPr()), 50);

        assertFalse(queries.collect().stream().anyMatch(v -> "CAP_REACHED".equals(v.code())),
                "an unset cap must be a no-op, or every existing deployment starts alarming on upgrade");
    }

    /**
     * UNMETERED calls, written through the production writer (not raw SQL) so the fixture cannot drift
     * into a row shape {@link dev.codespire.orchestrator.llm.LlmModelPricer} would never produce. Cost
     * is an asserted zero, so only the call axis can register them.
     */
    private void seedUnmeteredCalls(String reviewId, int count) {
        for (int i = 0; i < count; i++) {
            projection.recordCharges(new ChargeCall(reviewId, "CANARY-CAP-ATTENTION-" + reviewId + "-" + i,
                    ChargeKind.REVIEW, "TEST-MODEL", List.of(ChargeLine.unmetered(TokenType.INPUT, 1_000))));
        }
    }

    /** What the passage of time does to a rolling window: the charge falls outside it entirely. */
    private void backdateCharges(String reviewId) {
        String sql = "UPDATE llm_charge SET priced_at = now() - interval '2 days' WHERE review_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}

package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.orchestrator.attention.AttentionQueries;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.readmodel.ArchiveOutcome;
import dev.codespire.orchestrator.readmodel.ReviewDetail;
import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A cap refuses routinely and by design, so a refused review needs an end state an operator can
 * clear. The refusal this copies ({@code skipUnspendable}) writes a note and nothing else: the review
 * sits in {@code reviewing} until {@code REVIEW_STUCK} fires blaming a webhook or a worker, and since
 * ADR-024 {@code archiveRow} refuses anything still {@code reviewing}, so it cannot be cleared at all.
 *
 * <p>Every test here asserts the RAISED state first, because an absence-only assertion passes when the
 * fixture never produced the state being filtered.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class RefusedReviewLifecycleTest {

    @Inject
    ResultSaga saga;

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    AttentionQueries queries;

    @Inject
    DataSource dataSource;

    private static final String PHASE = "GenerateReview";

    /** Well past {@code spire.attention.stuck-minutes} (15 in %test), and older than any stuck row a
     *  sibling suite leaves in this module's shared database — the panel lists only the five oldest. */
    private static final String AGED = "1 day";

    @Test
    void aRefusedReviewIsTerminalAndArchivable() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, pr);

        saga.refuseForTest(ReviewFixtures.reviewIdFor(pr), commitFor(pr), PHASE,
                ReviewProjection.STAGE_REVIEW, CapRefusal.spendCapReached(750_000L, 500_000L));

        ReviewDetail detail = projection.loadDetail(WS, REPO, pr).orElseThrow();
        assertEquals("refused", detail.status());
        assertTrue(detail.note().contains("spend cap reached"), "the note says why: " + detail.note());
        assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr),
                "a refused review must be clearable — archiveRow rejects anything still 'reviewing'");
    }

    /**
     * The refusal is a deliberate decision, so it must not surface as the row that blames a delivery
     * path or a worker.
     *
     * <p>The row is aged TWICE. {@code refuse} writes the status through {@code updateStatus}, which
     * bumps {@code updated_at}, so a review refused a moment ago is too young to be stuck whatever its
     * status — assert on that and the test passes with {@code refused} absent from the terminal list,
     * which is the one line it exists to cover.
     */
    @Test
    void aRefusedReviewDoesNotRaiseTheStuckRow() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, pr);
        age(ReviewFixtures.reviewIdFor(pr));
        assertTrue(stuckRowExistsFor(pr), "a reviewing row raises REVIEW_STUCK once it ages");

        saga.refuseForTest(ReviewFixtures.reviewIdFor(pr), commitFor(pr), PHASE,
                ReviewProjection.STAGE_REVIEW, CapRefusal.spendCapReached(750_000L, 500_000L));
        age(ReviewFixtures.reviewIdFor(pr));

        assertFalse(stuckRowExistsFor(pr),
                "a routine refusal must not produce a row blaming a webhook or a worker");
    }

    /**
     * The refusal drives the aggregate terminal through {@code RecordFailure}, whose one domain event
     * is {@code ReviewFailedTerminally} — and that event comes back through cs.events into
     * {@code DomainEventSink}, which owns the read model's terminal statuses. The two tests above seed
     * only the read model, so the decider no-ops and no event is ever published; here the aggregate
     * really is REVIEWING at this commit, which is the production case.
     *
     * <p>If the sink projects that event as {@code failed} unconditionally, a policy refusal is
     * relabelled an infrastructure failure one Kafka round trip after the saga wrote it — silently,
     * because everything the operator sees (the list badge, the archive guard, REVIEW_FAILED) reads
     * the status and not the note.
     */
    @Test
    void aRefusalSurvivesTheAggregatesOwnTerminalFailureEvent() throws InterruptedException {
        long pr = ReviewFixtures.newPr();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        ReviewFixtures.seedReviewingReview(projection, pr);
        lifecycle.handle(reviewId, new RecordCommand.RequestReview(commitFor(pr), "TEST", false));

        saga.refuseForTest(reviewId, commitFor(pr), PHASE,
                ReviewProjection.STAGE_REVIEW, CapRefusal.callCapReached(120, 100));

        assertStaysRefusedOnceProjected(pr);
    }

    /**
     * The other half of the guard {@code aRefusalSurvives...} installs. Nothing else asserts it: the
     * only other emitter of {@code RecordFailure} is {@code onReviewFailed}, which writes {@code failed}
     * itself before the event is published, so the sink's own write is redundant on that path and a
     * guard that matched nothing at all would look green.
     */
    @Test
    void anOrdinaryTerminalFailureIsStillProjectedAsFailed() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, pr);

        projection.projectTerminalFailure(ReviewFixtures.reviewIdFor(pr));

        assertEquals("failed", projection.loadDetail(WS, REPO, pr).orElseThrow().status());
    }

    /**
     * Watch the status until the sink has projected the terminal-failure event, then a little longer:
     * the sink appends the event row and writes the status in the same handler, so sampling the status
     * the instant the row appears could read it just before the write this is here to catch.
     */
    private void assertStaysRefusedOnceProjected(long pr) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        long settleUntil = 0;
        while (System.currentTimeMillis() < deadline) {
            ReviewDetail detail = projection.loadDetail(WS, REPO, pr).orElseThrow();
            assertEquals("refused", detail.status(),
                    "the aggregate's terminal-failure event relabelled a policy refusal as '"
                            + detail.status() + "'");
            if (settleUntil == 0 && projectedTerminalFailure(detail)) {
                settleUntil = System.currentTimeMillis() + 2_000;
            }
            if (settleUntil > 0 && System.currentTimeMillis() > settleUntil) {
                return;
            }
            Thread.sleep(100);
        }
        fail("ReviewFailedTerminally was never projected — the refusal never reached the aggregate, "
                + "so this test proves nothing about what the sink does with it");
    }

    private static boolean projectedTerminalFailure(ReviewDetail detail) {
        return detail.events().stream()
                .anyMatch(e -> "ReviewFailedTerminally".equals(e.type()));
    }

    /** The commit the fixture's header carries, so the aggregate's current run matches the refusal. */
    private static String commitFor(long pr) {
        return "TESTSHA" + pr;
    }

    /** Whether the panel currently names this PR in a REVIEW_STUCK row. */
    private boolean stuckRowExistsFor(long pr) {
        String subject = WS + "/" + REPO + "#" + pr;
        return queries.collect().stream()
                .filter(v -> "REVIEW_STUCK".equals(v.code()))
                .map(AttentionView::subject)
                .anyMatch(subject::equals);
    }

    /** Backdate the row past the stuck threshold; no production path writes an old updated_at. */
    private void age(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE review_status SET updated_at = now() - interval '" + AGED
                             + "' WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to age " + reviewId, e);
        }
    }
}

package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.port.EventStore;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewDetail;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A re-run is a second genuinely paid call on an <b>unchanged</b> {@code (reviewId, commit)}: the
 * Re-run button and the {@code /review} PR comment both clear the worker's idempotency claim on
 * purpose, so the LLM really runs again. The ledger identity therefore cannot be the commit alone —
 * that ref already belongs to run 1, and {@code recordCharges}' {@code ON CONFLICT DO NOTHING} would
 * discard run 2's entire call: no row, no log, no attention row, and a cost card still showing run
 * 1's figure however many times the operator re-runs.
 *
 * <p>Driven against the real event store and the real ledger rather than fakes, because the property
 * under test is precisely that the run number advances <em>because the aggregate recorded a second
 * request</em> — a fake run counter would assert the wiring while assuming the fact.
 *
 * <p>Each test owns its own review id, and the WORKSPACE is unique to this class too: charges aggregate
 * by {@code review_id} with no teardown here, and the saga resolves an SCM provider by workspace name —
 * under the shared {@code TEST-WS} it picked up another suite's provider fixture and failed decrypting
 * that fixture's secret rather than testing anything about charges.
 */
@QuarkusTest
class RerunChargeIdentityIT {

    private static final String MODEL = "TEST-RERUN-MODEL";

    @Inject
    ResultSaga saga;

    @Inject
    EventStore eventStore;

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void aSecondRunOnTheSameCommitRecordsItsOwnCharges() {
        String reviewId = "review::TEST-RERUN-WS/TEST-RERUN-REPO#9201";
        String commit = "TESTSHA9201";
        requestReview(reviewId, commit, "OPENED");

        saga.on(reviewGenerated(reviewId, commit));
        // The operator pressed Re-run: the aggregate records a second request on the SAME commit, and
        // the worker's cached result was dropped, so this really is a second paid call.
        requestReview(reviewId, commit, "rerun");
        saga.on(reviewGenerated(reviewId, commit));

        List<String> refs = callRefs(reviewId);
        assertEquals(2, refs.size(),
                "the second run's charges were discarded as a redelivery of the first — refs: " + refs);
    }

    /**
     * The other half of the same property: the derived ref exists so a <em>redelivered</em> result
     * charges once. Without this, "make the ref unique per run" could be satisfied by making it unique
     * per delivery, which would double-charge every redelivery.
     */
    @Test
    void aRedeliveredResultFromTheSameRunIsStillChargedOnce() {
        String reviewId = "review::TEST-RERUN-WS/TEST-RERUN-REPO#9202";
        String commit = "TESTSHA9202";
        requestReview(reviewId, commit, "OPENED");

        saga.on(reviewGenerated(reviewId, commit));
        saga.on(reviewGenerated(reviewId, commit));

        assertEquals(1, callRefs(reviewId).size(),
                "a redelivered result is the same call and must resolve to the same ref");
    }

    /**
     * The retry counter must never be part of a paid call's identity.
     *
     * <p>{@code review_status.attempt} is the obvious column to reach for, and it is wrong in both
     * directions: a re-run leaves it untouched (so it would fix nothing), while the ADR-016 auto-retry
     * bumps it on the one path whose charges must COLLAPSE — the claims are kept, so the worker
     * re-emits its persisted result and one paid call is dispatched twice. Folding it in would turn
     * silently lost charges into silently inflated ones.
     *
     * <p>This drives the column the way the auto-retry does and asserts the identity did not move. The
     * read-back of {@code attempt} is not decoration: without a {@code review_status} row the UPDATE is
     * a no-op and the test would pass while proving nothing.
     */
    @Test
    void theRetryCounterIsNotPartOfAPaidCallsIdentity() {
        long pr = 9203L;
        String reviewId = "review::TEST-RERUN-WS/TEST-RERUN-REPO#" + pr;
        String commit = "TESTSHA9203";
        projection.registerHeader(reviewId, new RepoRef("TEST-RERUN-WS", "TEST-RERUN-REPO"), pr,
                "t", "a", "aid", "src", "dst", commit, "http://example.invalid/pr", "github",
                "reviewing", 0);
        requestReview(reviewId, commit, "OPENED");

        saga.on(reviewGenerated(reviewId, commit));
        bumpRetryCounter(reviewId);
        saga.on(reviewGenerated(reviewId, commit));

        assertEquals(2, projection.loadDetail("TEST-RERUN-WS", "TEST-RERUN-REPO", pr).orElseThrow().attempt(),
                "the fixture must actually have moved the counter, or this asserts nothing");
        assertEquals(1, callRefs(reviewId).size(),
                "the retry counter is not the run identity — one paid call, charged once");
    }

    /** What {@code scheduleRetry} does to the row, without needing a provider to dispatch a real retry. */
    private void bumpRetryCounter(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE review_status SET attempt = 2 WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not bump the retry counter", e);
        }
    }

    private List<String> callRefs(String reviewId) {
        return projection.chargeLines(reviewId).stream()
                .map(ReviewDetail.ChargeLineView::callRef)
                .distinct()
                .toList();
    }

    /** Append a real {@code ReviewRequested} so the aggregate is REVIEWING at this commit. */
    private void requestReview(String reviewId, String commit, String trigger) {
        eventStore.append(reviewId, eventStore.load(reviewId).size(),
                List.of(EventEnvelope.domain(reviewId, -1, reviewId, null,
                        new DomainEvent.ReviewRequested(commit, trigger))));
    }

    private static ReviewGenerated reviewGenerated(String reviewId, String commit) {
        ReviewResult result = new ReviewResult(List.of(), "summary",
                ModelUsage.of(MODEL, 1_000_000, 500_000));
        return new ReviewGenerated(reviewId, 9201L, commit, result);
    }
}

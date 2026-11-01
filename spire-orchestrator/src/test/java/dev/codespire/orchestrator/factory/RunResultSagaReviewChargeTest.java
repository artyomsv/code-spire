package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.llm.ChargeKind;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Through the real result consumer, the real ledger writer and the real V107 row: a review run's answer finishes its
 * row and leaves exactly one charge, under the review's call slot and never under the run, even when redelivered.
 */
@QuarkusTest
class RunResultSagaReviewChargeTest {

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    @Inject
    RunResultSaga saga;

    @Inject
    FactoryRunProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void aReviewResultIsChargedByTheReviewChargerOnly() {
        String reviewId = "review::TEST-V107L/TEST-REPO#" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000);
        ReviewRunBinding call = new ReviewRunBinding(reviewId, ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot");
        String runId = "run::github:TEST-V107L/TEST-REPO:review-" + UUID.randomUUID() + ":1";
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(runId, "TEST-harness", "TEST-MODEL", "TEST-source",
                HEAD, "TEST-source", "TEST-bot", null).asReviewFor(call), null, null));
        RunResult artifact = new RunReviewArtifact(runId, call, "TEST-findings", false, Map.of("INPUT", 10L), 4, List.of());

        String callRef = CallRefs.of(reviewId, "TEST-slot", ChargeKind.REVIEW);

        saga.on(artifact);
        int lines = count("SELECT count(*) FROM llm_charge WHERE call_ref = ?", callRef);
        saga.on(artifact);

        assertEquals("succeeded", status(runId));
        assertTrue(lines > 0, "the review call is charged");
        assertEquals(lines, count("SELECT count(*) FROM llm_charge WHERE call_ref = ?", callRef), "and a redelivery adds nothing");
        assertEquals(1, count("SELECT count(DISTINCT call_ref) FROM llm_charge WHERE subject_id = ?", reviewId),
                "one call under the review");
        assertEquals(0, count("SELECT count(*) FROM llm_charge WHERE subject_id = ?", runId), "never charged as a run");
    }

    /** An answer over the cap is a degraded review, never a lost paid run: the row finishes like any other answer. */
    @Test
    void anOverCapReviewAnswerStillFinishesTheRun() {
        String reviewId = "review::TEST-V107L/TEST-REPO#" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000);
        ReviewRunBinding call = new ReviewRunBinding(reviewId, ReviewRunBinding.Kind.RECONCILE, 7, HEAD, "TEST-slot");
        String runId = "run::github:TEST-V107L/TEST-REPO:review-" + UUID.randomUUID() + ":1";
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(runId, "TEST-harness", "TEST-MODEL", "TEST-source",
                HEAD, "TEST-source", "TEST-bot", null).asReviewFor(call), null, null));

        projection.apply(new RunReviewArtifact(runId, call, null, true, Map.of("INPUT", 10L), 4, List.of()));

        assertEquals("succeeded", status(runId));
    }

    /** A failure carries no binding, so the row is where the review charger finds the call it answers. */
    @Test
    void aReviewRowReadsBackItsBinding() {
        String reviewId = "review::TEST-V107L/TEST-REPO#" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000);
        ReviewRunBinding call = new ReviewRunBinding(reviewId, ReviewRunBinding.Kind.FOLLOWUP, 7, HEAD, "TEST-slot");
        String review = "run::github:TEST-V107L/TEST-REPO:review-" + UUID.randomUUID() + ":1";
        String build = "run::github:TEST-V107L/TEST-REPO:build-" + UUID.randomUUID() + ":1";
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(review, "TEST-harness", "TEST-MODEL", "TEST-source",
                HEAD, "TEST-source", "TEST-bot", null).asReviewFor(call), null, null));
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(build, "TEST-harness", "TEST-MODEL", "main",
                HEAD, "spire/TEST-build", "TEST-bot", null), null, null));

        assertEquals(java.util.Optional.of(call), projection.reviewBindingOf(review));
        assertEquals(java.util.Optional.of(RunKind.FOLLOWUP), projection.kindOf(review));
        assertEquals(java.util.Optional.empty(), projection.reviewBindingOf(build));
    }

    /** A re-arm takes only the identical request: a retry answering another call would charge this answer under it. */
    @Test
    void aRetryAnsweringAnotherCallIsNotReArmed() {
        String reviewId = "review::TEST-V107L/TEST-REPO#" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000);
        ReviewRunBinding call = new ReviewRunBinding(reviewId, ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot");
        ReviewRunBinding otherSlot = new ReviewRunBinding(reviewId, ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot-2");
        String runId = "run::github:TEST-V107L/TEST-REPO:review-" + UUID.randomUUID() + ":1";
        FactoryRunProjection.QueuedRun row = new FactoryRunProjection.QueuedRun(runId, "TEST-harness", "TEST-MODEL",
                "TEST-source", HEAD, "TEST-source", "TEST-bot", null);
        assertTrue(projection.queued(row.asReviewFor(call), null, null));
        projection.dispatchFailed(runId, "TEST-no-ack");

        assertEquals(false, projection.queued(row.asReviewFor(otherSlot), null, null), "another call is another request");
        assertTrue(projection.queued(row.asReviewFor(call), null, null), "the identical request re-arms");
    }

    private String status(String runId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM factory_run WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no row for " + runId);
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read back the run row", e);
        }
    }

    private int count(String sql, String value) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the ledger rows", e);
        }
    }
}

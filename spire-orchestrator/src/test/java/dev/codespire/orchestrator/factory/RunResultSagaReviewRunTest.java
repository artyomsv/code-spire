package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.factory.RunResultSagaFixture.RecordingCharges;
import dev.codespire.orchestrator.factory.RunResultSagaFixture.RecordingProjection;
import dev.codespire.orchestrator.factory.RunResultSagaFixture.RecordingReviewCharges;
import dev.codespire.orchestrator.factory.RunResultSagaFixture.RecordingReviewResults;
import dev.codespire.orchestrator.review.run.ReviewRunBindings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.codespire.orchestrator.factory.RunResultSagaFixture.REVIEW_CALL;
import static dev.codespire.orchestrator.factory.RunResultSagaFixture.RUN_ID;
import static dev.codespire.orchestrator.factory.RunResultSagaFixture.saga;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A review run's result through the saga (harness reviews, steps 3 and 4): the review-run port, the review charger,
 * and a refused credential reaching the pool.
 */
class RunResultSagaReviewRunTest {

    /**
     * A review run's result (harness reviews, step 4) goes to the review-run port and no further: it pushed nothing to
     * propose and has no work-run or draft row. A review-kind FAILURE is the discriminating case, because a review
     * artifact could also be stopped by its type alone.
     */
    @Test
    void aReviewArtifactNeverReachesThePullRequestStep() {
        RecordingProjection projection = new RecordingProjection();
        projection.kind = RunKind.REVIEW;
        projection.binding = REVIEW_CALL;
        RunResultSaga saga = saga(projection);
        List<RunResult> continued = new ArrayList<>();
        List<RunResult> proposed = new ArrayList<>();
        saga.drafts = new dev.codespire.orchestrator.work.WorkDraftResults() {
            @Override public boolean acceptsBinding(RunResult result) { return true; }
            @Override public boolean accept(RunResult result) { continued.add(result); return false; }
        };
        saga.workItems = new dev.codespire.orchestrator.work.WorkItemRunBridge() {
            @Override public boolean accept(RunResult result) { continued.add(result); return false; }
        };
        saga.pullRequests = new FactoryPullRequests() {
            @Override public void propose(RunResult result) { proposed.add(result); }
        };
        RunResult failed = new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 5L), List.of());
        RunResult artifact = new RunReviewArtifact(RUN_ID, REVIEW_CALL, "TEST-findings", false, Map.of("INPUT", 5L), 2, List.of());

        saga.on(failed);
        saga.on(artifact);

        assertEquals(List.of(failed, artifact), ((RecordingReviewResults) saga.reviewResults).accepted);
        assertTrue(continued.isEmpty(), "a review run has no draft or work-run row");
        assertTrue(proposed.isEmpty(), "a review run is never proposed");
    }

    /**
     * A review result is charged by the review charger, with the binding its row stores, after the projection and the
     * run plane (which skips review kinds) and before the review-run port.
     */
    @Test
    void aReviewResultIsChargedByTheReviewChargerOnly() {
        List<String> order = new ArrayList<>();
        RecordingProjection projection = new RecordingProjection() {
            @Override public void apply(RunResult result) { order.add("project"); }
        };
        projection.kind = RunKind.RECONCILE;
        projection.binding = REVIEW_CALL;
        RunResultSaga saga = saga(projection, new RecordingCharges() {
            @Override public void record(RunResult result) { order.add("run-plane"); }
        });
        RecordingReviewCharges reviewCharges = new RecordingReviewCharges() {
            @Override public void record(RunResult result, ReviewRunBinding binding) {
                super.record(result, binding);
                order.add("review-charge");
            }
        };
        saga.reviewCharges = reviewCharges;
        saga.reviewResults = (result, binding) -> order.add("review-result");
        RunResult failed = new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 5L), List.of());

        saga.on(failed);

        assertEquals(List.of(failed), reviewCharges.charged);
        assertEquals(List.of(REVIEW_CALL), reviewCharges.bindings, "a failure carries no binding; the row's is used");
        assertEquals(List.of("project", "run-plane", "review-charge", "review-result"), order);
    }

    /** A review artifact answering a call its row does not store is neither projected nor charged nor accepted. */
    @Test
    void aReviewArtifactForAnotherCallStopsTheResult() {
        RecordingProjection projection = new RecordingProjection();
        projection.kind = RunKind.REVIEW;
        projection.binding = REVIEW_CALL;
        RecordingCharges charges = new RecordingCharges();
        RunResultSaga saga = saga(projection, charges);
        saga.reviewRuns = new ReviewRunBindings() {
            @Override public boolean acceptsBinding(RunResult result) { return false; }
        };

        saga.on(new RunReviewArtifact(RUN_ID, REVIEW_CALL, "TEST-findings", false, Map.of("INPUT", 5L), 2, List.of()));

        assertTrue(projection.applied.isEmpty());
        assertTrue(charges.charged.isEmpty());
        assertTrue(((RecordingReviewCharges) saga.reviewCharges).charged.isEmpty());
        assertTrue(((RecordingReviewResults) saga.reviewResults).accepted.isEmpty());
    }

    /**
     * The seam the deleted CredentialRefusalHasNoProducerTest asked for (harness reviews step 3): the run worker reports
     * CREDENTIAL_REJECTED for a proxied review unit whose model call answered 401, and the REAL feedback marks the
     * member the run's factory_run row names. Only its two database reads are faked.
     */
    @Test
    void aReviewRunsRefusedCredentialMarksThePoolMember() {
        UUID member = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
        List<String> reads = new ArrayList<>();
        List<UUID> marked = new ArrayList<>();
        RunResultSaga saga = saga(reviewProjectionNaming(member, reads));
        saga.credentials = feedbackMarkingInto(marked, saga);

        RunResult refused = new RunResult.RunFailed(RUN_ID, "CREDENTIAL_REJECTED",
                "the model vendor refused the run's credential", false, Map.of("INPUT", 5L), List.of());
        saga.on(refused);

        assertEquals(List.of(RUN_ID), reads);
        assertEquals(List.of(member), marked);
        assertEquals(List.of(refused), ((RecordingReviewResults) saga.reviewResults).accepted,
                "the refusal still reaches the review as its failure");
    }

    /** A review run's projection whose factory_run row names {@code member}; the real method reads the row. */
    private static RecordingProjection reviewProjectionNaming(UUID member, List<String> reads) {
        RecordingProjection projection = new RecordingProjection() {
            @Override
            public Optional<UUID> harnessCredentialOf(String runId) {
                reads.add(runId);
                return Optional.of(member);
            }
        };
        projection.kind = RunKind.REVIEW;
        projection.binding = REVIEW_CALL;
        return projection;
    }

    /** The REAL feedback over the saga's projection, its pool's one write recorded; the real one updates the row. */
    private static RunCredentialFeedback feedbackMarkingInto(List<UUID> marked, RunResultSaga saga) {
        RunCredentialFeedback feedback = new RunCredentialFeedback();
        feedback.projection = saga.projection;
        feedback.pool = new HarnessCredentialPool() {
            @Override
            public boolean markRejected(UUID id) {
                marked.add(id);
                return true;
            }
        };
        return feedback;
    }
}

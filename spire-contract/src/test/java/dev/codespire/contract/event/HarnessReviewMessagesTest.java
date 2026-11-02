package dev.codespire.contract.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Harness reviews, step 6: the prepare and finish messages between the orchestrator and the review worker. */
class HarnessReviewMessagesTest {

    private static final String REVIEW_ID = "review::TEST-work/app#7";
    private static final RepoRef REPO = new RepoRef("TEST-work", "app");
    private static final String COMMIT = "a".repeat(40);

    private final ObjectMapper mapper = new ObjectMapper();

    private static PriorRun priorWith(int findings) {
        List<PriorFinding> list = new java.util.ArrayList<>();
        for (int i = 1; i <= findings; i++) {
            list.add(new PriorFinding("src/A.java", i, Severity.MAJOR, "TEST-finding-" + i, "TEST-thread-" + i));
        }
        return new PriorRun("b".repeat(40), "TEST-summary", list);
    }

    private static ActionCommand.PrepareHarnessReview prepare(PriorRun prior, ReviewRunBinding.Kind kind) {
        return new ActionCommand.PrepareHarnessReview(REVIEW_ID, REPO, 7, COMMIT, "TEST-context-ref",
                "TEST-scm-ciphertext", prior, 2, kind);
    }

    private static ActionCommand.FinishHarnessReview finish(String answer) {
        return new ActionCommand.FinishHarnessReview(REVIEW_ID, REPO, 7, COMMIT, 2, ReviewRunBinding.Kind.RECONCILE,
                answer, ModelUsage.of("TEST-model", 1, 1), "TEST-scm-ciphertext");
    }

    private static HarnessReviewInputs inputs() {
        return new HarnessReviewInputs(List.of(
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.CONTEXT, "TEST-CONTEXT"),
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.PRIOR_FINDINGS, "TEST-PRIOR")));
    }

    private static IntegrationEvent.HarnessReviewGenerated generated() {
        ReviewResult result = new ReviewResult(List.of(new Finding("src/A.java", new LineRange(3, 3), Severity.MAJOR,
                "TEST-finding", null)), "TEST-summary", ModelUsage.of("TEST-model", 1, 1));
        List<FindingVerdict> verdicts = List.of(new FindingVerdict("TEST-thread-1", "src/A.java", 1,
                FindingVerdict.Status.RESOLVED, "TEST-note"));
        return new IntegrationEvent.HarnessReviewGenerated(REVIEW_ID, 7, COMMIT, 2, result, verdicts);
    }

    @Test
    void aPrepareRoundTrips() throws Exception {
        ActionCommand.PrepareHarnessReview command = prepare(priorWith(2), ReviewRunBinding.Kind.RECONCILE);

        ActionCommand decoded = mapper.readValue(mapper.writeValueAsString(command), ActionCommand.class);

        assertEquals(command, decoded);
        assertEquals("TEST-scm-ciphertext", decoded.scmCredential(), "the worker unpacks the forge credential");
    }

    @Test
    void aReconcilePrepareNeedsPriorFindings() {
        assertThrows(IllegalArgumentException.class, () -> prepare(null, ReviewRunBinding.Kind.RECONCILE));
        assertThrows(IllegalArgumentException.class, () -> prepare(priorWith(0), ReviewRunBinding.Kind.RECONCILE));
        assertDoesNotThrow(() -> prepare(priorWith(1), ReviewRunBinding.Kind.RECONCILE));
    }

    @Test
    void aReviewPrepareCarriesNoPriorRun() {
        assertThrows(IllegalArgumentException.class, () -> prepare(priorWith(1), ReviewRunBinding.Kind.REVIEW));
        assertDoesNotThrow(() -> prepare(null, ReviewRunBinding.Kind.REVIEW));
    }

    @Test
    void aFollowupPrepareIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> prepare(null, ReviewRunBinding.Kind.FOLLOWUP));
    }

    @Test
    void aPrepareNamesItsRoundFromOne() {
        assertThrows(IllegalArgumentException.class, () -> new ActionCommand.PrepareHarnessReview(REVIEW_ID, REPO, 7,
                COMMIT, null, "TEST-scm-ciphertext", null, 0, ReviewRunBinding.Kind.REVIEW));
    }

    @Test
    void aPrepareNeverPrintsItsCredentialOrFindings() {
        String printed = prepare(priorWith(2), ReviewRunBinding.Kind.RECONCILE).toString();

        assertFalse(printed.contains("TEST-scm-ciphertext"), printed);
        assertFalse(printed.contains("TEST-finding-1"), printed);
        assertTrue(printed.contains("scmCredential=***"), printed);
    }

    @Test
    void aFinishRoundTripsWithAndWithoutAnAnswer() throws Exception {
        for (ActionCommand.FinishHarnessReview command : List.of(finish("{\"summary\":\"TEST\"}"), finish(null))) {
            ActionCommand decoded = mapper.readValue(mapper.writeValueAsString(command), ActionCommand.class);

            assertEquals(command, decoded);
        }
    }

    @Test
    void aFinishNeverPrintsItsAnswerOrCredential() {
        String printed = finish("TEST-ANSWER-TEXT").toString();

        assertFalse(printed.contains("TEST-ANSWER-TEXT"), printed);
        assertFalse(printed.contains("TEST-scm-ciphertext"), printed);
        assertTrue(printed.contains("scmCredential=***"), printed);
        assertTrue(printed.contains("16"), "the answer's length: " + printed);
    }

    @Test
    void aPreparedRoundTripsItsInputs() throws Exception {
        IntegrationEvent.HarnessReviewPrepared event = new IntegrationEvent.HarnessReviewPrepared(REVIEW_ID, 7, COMMIT,
                2, ReviewRunBinding.Kind.RECONCILE, inputs());

        IntegrationEvent decoded = mapper.readValue(mapper.writeValueAsString(event), IntegrationEvent.class);

        assertEquals(event, decoded);
    }

    @Test
    void aPreparedAlwaysCarriesItsInputs() {
        assertThrows(NullPointerException.class, () -> new IntegrationEvent.HarnessReviewPrepared(REVIEW_ID, 7, COMMIT,
                2, ReviewRunBinding.Kind.REVIEW, null));
    }

    @Test
    void aGeneratedRoundTrips() throws Exception {
        IntegrationEvent.HarnessReviewGenerated event = generated();

        IntegrationEvent decoded = mapper.readValue(mapper.writeValueAsString(event), IntegrationEvent.class);

        assertEquals(event, decoded);
    }

    @Test
    void aGeneratedWithNoVerdictsReadsAsEmpty() {
        IntegrationEvent.HarnessReviewGenerated event = new IntegrationEvent.HarnessReviewGenerated(REVIEW_ID, 7,
                COMMIT, 1, generated().result(), null);

        assertEquals(List.of(), event.verdicts());
    }

    @Test
    void aGeneratedBecomesAReviewGeneratedWithNoReconcileUsage() {
        IntegrationEvent.HarnessReviewGenerated event = generated();

        IntegrationEvent.ReviewGenerated review = event.toReviewGenerated();

        assertEquals(REVIEW_ID, review.reviewId());
        assertEquals(7, review.prId());
        assertEquals(COMMIT, review.commit());
        assertEquals(event.result(), review.result());
        assertEquals(event.verdicts(), review.verdicts());
        assertNull(review.reconcileUsage(), "the run was charged once, by ReviewRunCharges");
    }

    @Test
    void everyNewMessageIsKeyedByItsReview() {
        IntegrationEvent prepared = new IntegrationEvent.HarnessReviewPrepared(REVIEW_ID, 7, COMMIT, 2,
                ReviewRunBinding.Kind.REVIEW, inputs());

        assertEquals(REVIEW_ID, EventKeys.of(prepared));
        assertEquals(REVIEW_ID, EventKeys.of(generated()));
        assertEquals(REVIEW_ID, prepare(null, ReviewRunBinding.Kind.REVIEW).reviewId());
        assertEquals(REVIEW_ID, finish(null).reviewId());
        assertEquals(REVIEW_ID, EventKeys.of(failed()));
    }

    /** The round rides the wire, so a late failure of an ended round can be told from the current round's. */
    @Test
    void aFailedRoundTripsWithItsRound() throws Exception {
        IntegrationEvent decoded = mapper.readValue(mapper.writeValueAsString(failed()), IntegrationEvent.class);

        assertEquals(failed(), decoded);
        assertEquals(2, ((IntegrationEvent.HarnessReviewFailed) decoded).round());
    }

    @Test
    void aFailedBecomesTheApiPathsFailureAtAttemptOne() {
        IntegrationEvent.ReviewFailed failure = failed().toReviewFailed();

        assertEquals(new IntegrationEvent.ReviewFailed(REVIEW_ID, COMMIT, "harness-prepare", "TEST-error", true, 1,
                true), failure);
    }

    private static IntegrationEvent.HarnessReviewFailed failed() {
        return new IntegrationEvent.HarnessReviewFailed(REVIEW_ID, COMMIT, 2, "harness-prepare", "TEST-error", true,
                true);
    }
}

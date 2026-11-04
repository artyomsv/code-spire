package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.TestRepositoryProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which answers {@link ResultSaga} counts as turns (review of PR #371, code-quality/M-1): a {@code FollowUpPosted}
 * that says {@code countsTurn} is counted on its conversation root; one recorded before the turn receipts existed
 * (the flag absent, so false) is filed there and never counted, whatever order or path brings it back. The thread
 * view is a fake that overrides every method the handler reaches, so no database is opened (CLAUDE.md).
 */
class ResultSagaTurnReceiptTest {

    private static final ThreadRef ROOT = new ThreadRef("TEST-root");

    private final List<String> counted = new ArrayList<>();
    private final List<String> filed = new ArrayList<>();

    @Test
    void anAnswerPostedSinceTheReceiptsExistIsCountedOnItsRoot() {
        saga().on(new FollowUpPosted(HarnessSagaFakes.REVIEW_ID, new ThreadRef("TEST-reply"), "TEST-answer-new", true));

        assertEquals(List.of("TEST-root/TEST-answer-new"), counted);
        assertTrue(filed.isEmpty(), "a counted answer is not filed a second time");
    }

    @Test
    void anAnswerFromBeforeTheReceiptsIsFiledAndNeverCounted() {
        saga().on(new FollowUpPosted(HarnessSagaFakes.REVIEW_ID, new ThreadRef("TEST-reply"), "TEST-answer-old",
                false));

        assertEquals(List.of("TEST-root/TEST-answer-old"), filed, "filed under the root, as a counted answer is");
        assertTrue(counted.isEmpty(), "an old answer may already be in the count; it never counts again");
    }

    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.findings = HarnessSagaFakes.SILENT_FINDINGS;
        saga.preferenceFilter = HarnessSagaFakes.NO_PREFERENCES;
        saga.timeline = HarnessSagaFakes.timeline(new ArrayList<>());
        saga.lifecycle = HarnessSagaFakes.lifecycle(new ArrayList<>());
        saga.threads = threads();
        saga.projection = new TestRepositoryProjection() {
            @Override
            public Optional<String> summaryRefOf(String reviewId) {
                return Optional.empty();
            }

            @Override
            public void setAnswering(String reviewId, boolean answering) {
            }
        };
        return saga;
    }

    /** Every reply resolves to {@link #ROOT}; the two receipt writes are recorded, the ownership marks ignored. */
    private ReviewThreadView threads() {
        return new ReviewThreadView() {
            @Override
            public ThreadRef rootOf(String reviewId, ThreadRef thread) {
                return ROOT;
            }

            @Override
            public void bumpTurn(String reviewId, ThreadRef thread, String lastCommentId) {
                counted.add(thread.value() + "/" + lastCommentId);
            }

            @Override
            public void fileAnswer(String reviewId, ThreadRef thread, String commentId) {
                filed.add(thread.value() + "/" + commentId);
            }

            @Override
            public void markOurThread(String reviewId, ThreadRef thread) {
            }

            @Override
            public void markAnswerThread(String reviewId, ThreadRef answer, ThreadRef root) {
            }
        };
    }
}

package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined;
import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined.Reason;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.TestRepositoryProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reply the worker stayed quiet on (production PR #456): {@link ResultSaga} records it under the conversation root
 * and clears "answering", and counts no turn. The projection and thread view override every method the path reaches,
 * and a turn write fails the test, so no database is opened (CLAUDE.md, saga test fakes).
 */
class ResultSagaFollowUpDeclinedTest {

    private static final ThreadRef ROOT = new ThreadRef("TEST-root");

    private final List<String> answering = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    @Test
    void aDeclinedReplyClearsAnsweringAndIsRecordedUnderItsRoot() {
        saga().on(new FollowUpDeclined(HarnessSagaFakes.REVIEW_ID, new ThreadRef("TEST-reply"), "TEST-comment-5",
                Reason.MULTI_PARTY_NO_MENTION));

        assertEquals(List.of(HarnessSagaFakes.REVIEW_ID + ":false"), answering);
        assertEquals(List.of("FollowUpDeclined|TEST-root|reply not answered: another person is in the thread and the"
                + " reply did not mention the bot"), events);
    }

    @Test
    void aDeclineWithNoThreadIsRecordedWithoutOne() {
        saga().on(new FollowUpDeclined(HarnessSagaFakes.REVIEW_ID, null, "TEST-comment-5", Reason.ALREADY_ANSWERED));

        assertEquals(List.of(HarnessSagaFakes.REVIEW_ID + ":false"), answering);
        assertEquals(List.of("FollowUpDeclined|null|reply not answered: this comment was answered already"), events);
    }

    @Test
    void everyReasonReadsAsUnanswered() {
        for (Reason reason : Reason.values()) {
            String detail = FollowUpDeclines.detail(reason);
            assertTrue(detail.startsWith("reply not answered: ") && !detail.endsWith("no reason given"),
                    reason + " has its own words: " + detail);
        }
    }

    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.timeline = HarnessSagaFakes.timeline(new ArrayList<>());
        FollowUpDeclines declines = new FollowUpDeclines();
        declines.threads = threads();
        declines.projection = new TestRepositoryProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
                events.add(type + "|" + threadRef + "|" + detail);
            }

            @Override
            public void setAnswering(String reviewId, boolean value) {
                answering.add(reviewId + ":" + value);
            }
        };
        saga.followUpDeclines = declines;
        return saga;
    }

    /** Every reply resolves to {@link #ROOT}; a turn written here would be the bug this event exists to avoid. */
    private static ReviewThreadView threads() {
        return new ReviewThreadView() {
            @Override
            public ThreadRef rootOf(String reviewId, ThreadRef thread) {
                return ROOT;
            }

            @Override
            public void bumpTurn(String reviewId, ThreadRef thread, String lastCommentId) {
                throw new AssertionError("a declined reply consumes no turn");
            }

            @Override
            public void fileAnswer(String reviewId, ThreadRef thread, String commentId) {
                throw new AssertionError("a declined reply files no answer");
            }
        };
    }
}

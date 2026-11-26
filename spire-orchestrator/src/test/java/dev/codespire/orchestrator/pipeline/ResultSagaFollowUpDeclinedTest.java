package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined;
import dev.codespire.contract.event.FollowUpDeclineReason;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.TestRepositoryProjection;
import dev.codespire.orchestrator.readmodel.AnsweringMarks;
import dev.codespire.orchestrator.readmodel.ReplyKey;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reply the worker stayed quiet on (production PR #456): {@link ResultSaga} records it under the conversation root
 * and clears "answering" for its own comment, and counts no turn. The projection, the marks and the thread view
 * override every method the path reaches, and a turn write fails the test, so no database is opened (CLAUDE.md, saga
 * test fakes). Which mark a finish clears is the SQL's, proven against Postgres in {@code AnsweringMarksIT}.
 */
class ResultSagaFollowUpDeclinedTest {

    private static final ThreadRef ROOT = new ThreadRef("TEST-root");

    private final List<String> answering = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    @Test
    void aDeclinedReplyFinishesItsOwnCommentsMarkAndIsRecordedUnderItsRoot() {
        saga().on(new FollowUpDeclined(HarnessSagaFakes.REVIEW_ID, new ThreadRef("TEST-reply"), "TEST-comment-5",
                FollowUpDeclineReason.MULTI_PARTY_NO_MENTION));

        assertEquals(List.of(HarnessSagaFakes.REVIEW_ID + ":TEST-comment-5"), answering);
        assertEquals(List.of("FollowUpDeclined|TEST-root|reply not answered: another person is in the thread and the"
                + " reply did not mention the bot"), events);
    }

    @Test
    void aDeclineWithNoThreadIsRecordedWithoutOne() {
        saga().on(new FollowUpDeclined(HarnessSagaFakes.REVIEW_ID, null, "TEST-comment-5",
                FollowUpDeclineReason.NO_THREAD_SOURCE));

        assertEquals(List.of(HarnessSagaFakes.REVIEW_ID + ":TEST-comment-5"), answering);
        assertEquals(List.of("FollowUpDeclined|null|reply not answered: this forge cannot read a reply thread"),
                events);
    }

    /** A redelivery of a comment the bot did answer: the mark clears, and no history says it went unanswered. */
    @Test
    void anAlreadyAnsweredCommentFinishesItsMarkAndRecordsNothing() {
        saga().on(new FollowUpDeclined(HarnessSagaFakes.REVIEW_ID, new ThreadRef("TEST-reply"), "TEST-comment-5",
                FollowUpDeclineReason.ALREADY_ANSWERED));

        assertEquals(List.of(HarnessSagaFakes.REVIEW_ID + ":TEST-comment-5"), answering);
        assertEquals(List.of(), events);
    }

    @Test
    void everyReasonReadsAsUnanswered() {
        for (FollowUpDeclineReason reason : FollowUpDeclineReason.values()) {
            String detail = FollowUpEndings.detail(reason);
            assertTrue(detail.startsWith("reply not answered: ") && !detail.endsWith("no reason given"),
                    reason + " has its own words: " + detail);
        }
    }

    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.timeline = HarnessSagaFakes.timeline(new ArrayList<>());
        FollowUpEndings endings = new FollowUpEndings();
        endings.threads = threads();
        endings.projection = new TestRepositoryProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
                events.add(type + "|" + threadRef + "|" + detail);
            }

            @Override
            public void setAnswering(String reviewId, boolean value) {
                throw new AssertionError("a decline clears only its own comment's mark");
            }
        };
        endings.answering = new AnsweringMarks() {
            @Override
            public void finish(ReplyKey reply, Runnable recordEnding) {
                answering.add(reply.reviewId() + ":" + reply.commentId());
                recordEnding.run();
            }
        };
        saga.followUpEndings = endings;
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

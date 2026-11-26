package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.FollowUpDeclineReason;
import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined;
import dev.codespire.contract.event.IntegrationEvent.TurnCapNotified;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.readmodel.AnsweringMarks;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A reply that ended without a model's answer, so no turn is counted, unlike {@code FollowUpPosted}: the worker stayed
 * quiet on it (production PR #456), or the thread reached its turn cap and got the fixed hand-off notice.
 */
@ApplicationScoped
public class FollowUpEndings {

    static final String DECLINED = "FollowUpDeclined";

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewThreadView threads;

    @Inject
    AnsweringMarks answering;

    /**
     * Said under the conversation root, and the "responding" mark cleared only while it is still this comment's (V122,
     * review of PR #457): reply A's decline must not clear the mark reply B set. {@code ALREADY_ANSWERED} is a
     * redelivery of a comment the bot did answer, so it clears the mark but records nothing: the history would
     * otherwise say an answered reply went unanswered, once per redelivery.
     */
    public void declined(FollowUpDeclined declined) {
        if (declined.reason() != FollowUpDeclineReason.ALREADY_ANSWERED) {
            // A null thread only from a hand-built record (a DLQ replay): rootOf binds it into a statement.
            String root = declined.threadRef() == null
                    ? null : threads.rootOf(declined.reviewId(), declined.threadRef()).value();
            projection.appendEvent(declined.reviewId(), "result", DECLINED, detail(declined.reason()), root);
        }
        answering.finish(declined.reviewId(), declined.triggeringCommentId());
    }

    /**
     * The turn-cap notice: no bumpTurn, since the notice about running out of turns must not consume one. Linked to
     * the root anyway, so a human replying to the notice (on an SCM that threads by immediate parent) is still
     * recognized as this conversation. The event names no triggering comment, so the mark clears unconditionally.
     */
    public void capped(TurnCapNotified e) {
        ThreadRef root = threads.rootOf(e.reviewId(), e.threadRef());
        projection.appendEvent(e.reviewId(), "result", "TurnCapNotified",
                "turn cap reached — handed back to the team", root.value());
        threads.markAnswerThread(e.reviewId(), new ThreadRef(e.commentId()), root);
        projection.setAnswering(e.reviewId(), false);
    }

    static String detail(FollowUpDeclineReason reason) {
        String why = switch (reason) {
            case NO_THREAD_SOURCE -> "this forge cannot read a reply thread";
            case MULTI_PARTY_NO_MENTION -> "another person is in the thread and the reply did not mention the bot";
            case ALREADY_ANSWERED -> "this comment was answered already";
            case null -> "no reason given";
        };
        return "reply not answered: " + why;
    }
}

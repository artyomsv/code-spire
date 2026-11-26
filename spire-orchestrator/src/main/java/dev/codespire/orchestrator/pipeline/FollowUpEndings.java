package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.FollowUpDeclineReason;
import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined;
import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.event.IntegrationEvent.TurnCapNotified;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.readmodel.AnsweringMarks;
import dev.codespire.orchestrator.readmodel.ReplyKey;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * How a reply ends for the "responding" mark (V122), and the endings without a model's answer, which count no turn,
 * unlike {@code FollowUpPosted}: the worker stayed quiet on it (production PR #456), or the thread reached its turn cap
 * and got the fixed hand-off notice.
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
     * The answer landed: its own reply's mark ends, and the mark stays while another reply is outstanding. An answer
     * posted before V122 names no triggering comment, so every mark of the review ends, as before.
     */
    public void posted(FollowUpPosted e) {
        if (e.triggeringCommentId() == null) {
            projection.setAnswering(e.reviewId(), false);
            return;
        }
        answering.finish(new ReplyKey(e.reviewId(), e.triggeringCommentId()));
    }

    /**
     * Said under the conversation root, and only this comment's outstanding reply ended (V122, review of PR #457):
     * reply B's decline must not clear the mark while reply A is still being answered. The line is recorded only by
     * the decline that consumed the reply's outstanding row, so a redelivered decline, or one arriving after the reply
     * was answered, after the pull request closed or after a newer ask, adds no line (code-quality/M-1). {@code
     * ALREADY_ANSWERED} is a redelivery of a comment the bot did answer, so its reply ends but nothing is recorded: the
     * history would otherwise say an answered reply went unanswered.
     */
    public void declined(FollowUpDeclined declined) {
        ReplyKey reply = new ReplyKey(declined.reviewId(), declined.triggeringCommentId());
        if (declined.reason() == FollowUpDeclineReason.ALREADY_ANSWERED) {
            answering.finish(reply);
            return;
        }
        answering.finish(reply, () -> record(declined));
    }

    private void record(FollowUpDeclined declined) {
        // A null thread only from a hand-built record (a DLQ replay): rootOf binds it into a statement.
        String root = declined.threadRef() == null
                ? null : threads.rootOf(declined.reviewId(), declined.threadRef()).value();
        projection.appendEvent(declined.reviewId(), "result", DECLINED, detail(declined.reason()), root);
    }

    /**
     * The turn-cap notice: no bumpTurn, since the notice about running out of turns must not consume one. Linked to
     * the root anyway, so a human replying to the notice (on an SCM that threads by immediate parent) is still
     * recognized as this conversation. The "responding" mark is left alone: a cap notice never set one (ReplyDispatch
     * marks only a real answer), so clearing here would only drop another reply's mark while it is being answered. The
     * review is still broadcast, so an open dashboard shows the notice (code-quality/M-3).
     */
    public void capped(TurnCapNotified e) {
        ThreadRef root = threads.rootOf(e.reviewId(), e.threadRef());
        projection.appendEvent(e.reviewId(), "result", "TurnCapNotified",
                "turn cap reached — handed back to the team", root.value());
        threads.markAnswerThread(e.reviewId(), new ThreadRef(e.commentId()), root);
        projection.touch(e.reviewId());
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

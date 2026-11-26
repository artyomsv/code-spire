package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The worker stayed quiet on a reply it was asked to answer (production PR #456): the review says so under the
 * conversation root, and "answering", set when the ask was sent, is cleared. Nothing was said, so no turn is counted,
 * unlike {@code FollowUpPosted}.
 */
@ApplicationScoped
public class FollowUpDeclines {

    static final String TYPE = "FollowUpDeclined";

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewThreadView threads;

    public void apply(FollowUpDeclined declined) {
        // A null thread only from a hand-built record (a DLQ replay): rootOf binds it into a statement.
        String root = declined.threadRef() == null
                ? null : threads.rootOf(declined.reviewId(), declined.threadRef()).value();
        projection.appendEvent(declined.reviewId(), "result", TYPE, detail(declined.reason()), root);
        // Also bumps updated_at and broadcasts, so the dashboard drops the "responding" mark at once.
        projection.setAnswering(declined.reviewId(), false);
    }

    static String detail(FollowUpDeclined.Reason reason) {
        String why = switch (reason) {
            case NO_THREAD_SOURCE -> "this forge cannot read a reply thread";
            case MULTI_PARTY_NO_MENTION -> "another person is in the thread and the reply did not mention the bot";
            case ALREADY_ANSWERED -> "this comment was answered already";
            case null -> "no reason given";
        };
        return "reply not answered: " + why;
    }
}

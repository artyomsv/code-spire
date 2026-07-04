package dev.codespire.contract.event;

/**
 * Kafka partition key per integration event — everything about one PR is keyed
 * by its reviewId so cs.* topics preserve per-PR ordering (CONTRACT §9).
 */
public final class EventKeys {

    private EventKeys() {
    }

    public static String of(IntegrationEvent event) {
        return switch (event) {
            case IntegrationEvent.PullRequestEventReceived e -> ReviewIds.reviewId(e.repo(), e.prId());
            case IntegrationEvent.PullRequestClosed e -> ReviewIds.reviewId(e.repo(), e.prId());
            case IntegrationEvent.ManualCommandReceived e -> ReviewIds.reviewId(e.repo(), e.prId());
            case IntegrationEvent.AuthorReplied e -> e.reviewId();
            case IntegrationEvent.PushReceived e -> e.repo().full();
            case IntegrationEvent.DiffFetched e -> e.reviewId();
            case IntegrationEvent.ContextRequested e -> e.request().reviewId();
            case IntegrationEvent.ContextContributed e -> e.reviewId();
            case IntegrationEvent.ContextAssembled e -> e.reviewId();
            case IntegrationEvent.ReviewGenerated e -> e.reviewId();
            case IntegrationEvent.ReviewFailed e -> e.reviewId();
            case IntegrationEvent.CommentsPosted e -> e.reviewId();
            case IntegrationEvent.FollowUpGenerated e -> e.reviewId();
            case IntegrationEvent.FollowUpPosted e -> e.reviewId();
        };
    }
}

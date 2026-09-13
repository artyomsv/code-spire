package dev.codespire.contract.event;

/** Product routing authority; an SCM endpoint cannot receive ISSUE source events. */
public enum RepositoryEventKind {
    REVIEWER, FACTORY, ISSUE;

    public boolean accepts(IntegrationEvent event) {
        return switch (this) {
            case REVIEWER -> event instanceof IntegrationEvent.PullRequestEventReceived
                    || event instanceof IntegrationEvent.PullRequestClosed
                    || event instanceof IntegrationEvent.ManualCommandReceived
                    || event instanceof IntegrationEvent.AuthorReplied;
            case FACTORY -> event instanceof IntegrationEvent.PullRequestEventReceived
                    || event instanceof IntegrationEvent.PullRequestClosed
                    || event instanceof IntegrationEvent.PushReceived;
            case ISSUE -> false;
        };
    }
}

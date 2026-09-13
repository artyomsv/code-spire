package dev.codespire.worksource;

import java.util.Set;

/** A source instance is bound to one registered scope and explicit account by composition. */
public interface WorkSource {
    enum Capability { CANDIDATES, LABEL_AUDIT, COMMENT, TRANSITION }

    Set<Capability> capabilities();
    WorkPage<WorkIssueLocation> candidates(String cursor);
    Fetch fetch(WorkIssueLocation issue);
    WorkPage<LabelEvent> labelEvents(WorkIssueLocation issue, String cursor);

    /** Writes use a distinct facade from the context provider's read-only interface. */
    String comment(WorkIssueLocation issue, String text, String effectId);
    void transition(WorkIssueLocation issue, String transitionId, String effectId);

    sealed interface Fetch {
        record Found(WorkTicket ticket) implements Fetch {}
        record Unavailable(String reason) implements Fetch {}
        /** Use only for confirmed deletion; a hidden issue or token outage is Unavailable. */
        record Deleted() implements Fetch {}
        /** Confirmed move; the workflow must explicitly re-admit under the destination policy. */
        record Transferred(WorkIssueLocation destination) implements Fetch {}
    }
}

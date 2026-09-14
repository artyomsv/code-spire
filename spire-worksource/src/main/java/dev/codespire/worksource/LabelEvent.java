package dev.codespire.worksource;

import java.time.Instant;

/** A control fact, not proof of a current label until current state and full history reconcile. */
public record LabelEvent(String eventId, WorkIssueRef issue, String label, Action action,
                         String trackerActorId, Instant occurredAt, String orderingToken, Origin origin) {
    public enum Action { ADD, REMOVE }
    /** UNATTRIBUTED stays unknown even if a payload happens to carry an allowed actor hint. */
    public enum Origin { WEBHOOK, AUDIT_TRAIL, UNATTRIBUTED }
}

package dev.codespire.worksource;

/** Reconciled control evidence. An actor value is only a hint when origin is UNATTRIBUTED. */
public record CurrentLabel(String label, String trackerActorId, LabelEvent.Origin origin,
                           String eventId, Reason reason) {
    public enum Reason { ATTRIBUTED, AUDIT_INCOMPLETE, NO_CURRENT_ADD, ISSUE_MISMATCH,
        MALFORMED_EVENT, CONFLICTING_EVENT, AMBIGUOUS_ORDER, UNATTRIBUTED, UNKNOWN_ACTOR }
}

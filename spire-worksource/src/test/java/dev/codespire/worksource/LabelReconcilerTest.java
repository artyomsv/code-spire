package dev.codespire.worksource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static dev.codespire.worksource.CurrentLabel.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class LabelReconcilerTest {
    private static final WorkIssueRef ISSUE = new WorkIssueRef(WorkSourceType.GITHUB,
            "https://github.example.test", "TEST-project", "TEST-issue");
    private static final Instant WHEN = Instant.parse("2026-09-13T12:00:00Z");
    private static final String LABEL = "TEST-suggest";

    @Test void completeAuditAttributesTheCurrentAddition() {
        CurrentLabel current = current(List.of(add("TEST-old", "900123", 0), add("TEST-new", "900456", 2)), true);
        assertEquals(ATTRIBUTED, current.reason());
        assertEquals("900456", current.trackerActorId());
        assertEquals("TEST-new", current.eventId());
    }
    @Test void anAuditGapCannotBorrowAnEarlierAllowedActor() {
        CurrentLabel current = current(List.of(add("TEST-old", "900123", 0)), false);
        assertEquals(AUDIT_INCOMPLETE, current.reason());
        assertEquals(LabelEvent.Origin.UNATTRIBUTED, current.origin());
        assertEquals("900123", current.trackerActorId(), "the hint is present, so only provenance can prevent selection");
    }
    @Test void aLaterRemovalInvalidatesTheEarlierAddition() {
        assertEquals(NO_CURRENT_ADD, current(List.of(add("TEST-add", "900123", 0),
                event("TEST-remove", ISSUE, LABEL, LabelEvent.Action.REMOVE, "900456", WHEN.plusSeconds(1), LabelEvent.Origin.AUDIT_TRAIL)), true).reason());
    }
    @Test void aReadditionUsesItsOwnActor() {
        CurrentLabel current = current(List.of(add("TEST-add", "900123", 0),
                event("TEST-remove", ISSUE, LABEL, LabelEvent.Action.REMOVE, "900456", WHEN.plusSeconds(1), LabelEvent.Origin.AUDIT_TRAIL),
                add("TEST-readd", "900789", 2)), true);
        assertEquals("900789", current.trackerActorId());
        assertEquals(ATTRIBUTED, current.reason());
    }
    @Test void oldEvidenceCannotResurrectAnAbsentLabel() {
        assertTrue(LabelReconciler.reconcile(ISSUE, Set.of(), List.of(add("TEST-old", "900123", 0)), true).isEmpty());
    }
    @Test void evidenceForAnotherLabelCannotSupplyAnActor() {
        assertEquals(NO_CURRENT_ADD, current(List.of(event("TEST-other", ISSUE, "TEST-autonomous",
                LabelEvent.Action.ADD, "900123", WHEN, LabelEvent.Origin.AUDIT_TRAIL)), true).reason());
    }
    @Test void evidenceForAnotherIssueCannotSupplyAnActor() {
        WorkIssueRef other = new WorkIssueRef(ISSUE.type(), ISSUE.origin(), ISSUE.projectId(), "TEST-other-issue");
        assertEquals(ISSUE_MISMATCH, current(List.of(event("TEST-other", other, LABEL,
                LabelEvent.Action.ADD, "900123", WHEN, LabelEvent.Origin.AUDIT_TRAIL)), true).reason());
    }
    @Test void unattributedEvidenceRetainsItsHintWithoutGrantingProvenance() {
        CurrentLabel current = current(List.of(event("TEST-hint", ISSUE, LABEL, LabelEvent.Action.ADD,
                "900123", WHEN, LabelEvent.Origin.UNATTRIBUTED)), true);
        assertEquals("900123", current.trackerActorId());
        assertEquals(LabelEvent.Origin.UNATTRIBUTED, current.origin());
        assertEquals(UNATTRIBUTED, current.reason());
    }
    @Test void unknownWireOriginCannotBecomeAttribution() {
        assertEquals(UNATTRIBUTED, current(List.of(event("TEST-hint", ISSUE, LABEL,
                LabelEvent.Action.ADD, "900123", WHEN, null)), true).reason());
    }
    @Test void aMissingActorCannotBecomeAttribution() {
        assertEquals(UNKNOWN_ACTOR, current(List.of(add("TEST-add", null, 0)), true).reason());
    }
    @Test void aBlankActorCannotBecomeAttribution() {
        assertEquals(UNKNOWN_ACTOR, current(List.of(add("TEST-add", " ", 0)), true).reason());
    }
    @Test void noAuditAdditionCannotBecomeAttribution() {
        assertEquals(NO_CURRENT_ADD, current(List.of(), true).reason());
    }
    @Test void ambiguousTimestampsCannotChooseTheLastArrayEntry() {
        assertEquals(AMBIGUOUS_ORDER, current(List.of(add("TEST-one", "900123", 0), add("TEST-two", "900456", 0)), true).reason());
    }
    @Test void exactRedeliveryIsIdempotent() {
        LabelEvent event = add("TEST-same", "900123", 0);
        assertEquals(ATTRIBUTED, current(List.of(event, event), true).reason());
    }
    @Test void conflictingEventIdentityCannotChooseEitherActor() {
        assertEquals(CONFLICTING_EVENT, current(List.of(add("TEST-same", "900123", 0), add("TEST-same", "900456", 1)), true).reason());
    }
    @Test void missingEventIdentityCannotBeReconciled() {
        assertEquals(MALFORMED_EVENT, current(List.of(add(null, "900123", 0)), true).reason());
    }
    @Test void blankEventIdentityCannotBeReconciled() {
        assertEquals(MALFORMED_EVENT, current(List.of(add(" ", "900123", 0)), true).reason());
    }
    @Test void missingOccurrenceTimeCannotBeReconciled() {
        assertEquals(MALFORMED_EVENT, current(List.of(event("TEST-add", ISSUE, LABEL,
                LabelEvent.Action.ADD, "900123", null, LabelEvent.Origin.AUDIT_TRAIL)), true).reason());
    }
    private static CurrentLabel current(List<LabelEvent> events, boolean complete) {
        return LabelReconciler.reconcile(ISSUE, Set.of(LABEL), events, complete).getFirst();
    }
    private static LabelEvent add(String id, String actor, int second) {
        return event(id, ISSUE, LABEL, LabelEvent.Action.ADD, actor, WHEN.plusSeconds(second), LabelEvent.Origin.AUDIT_TRAIL);
    }
    private static LabelEvent event(String id, WorkIssueRef issue, String label, LabelEvent.Action action,
                                    String actor, Instant at, LabelEvent.Origin origin) {
        return new LabelEvent(id, issue, label, action, actor, at, null, origin);
    }
}

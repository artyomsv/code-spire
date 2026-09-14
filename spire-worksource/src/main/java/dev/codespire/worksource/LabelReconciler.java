package dev.codespire.worksource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import static dev.codespire.worksource.CurrentLabel.Reason.*;

/** Same reconciliation for polling and verified webhook triggers; no profile policy lives here. */
public final class LabelReconciler {
    private LabelReconciler() {}

    public static List<CurrentLabel> reconcile(WorkIssueRef issue, Set<String> currentLabels,
                                               List<LabelEvent> events, boolean completeHistory) {
        List<CurrentLabel> result = new ArrayList<>();
        for (String label : new TreeSet<>(currentLabels)) {
            List<LabelEvent> history = events.stream().filter(e -> label.equals(e.label())).toList();
            result.add(current(issue, label, history, completeHistory));
        }
        return List.copyOf(result);
    }

    private static CurrentLabel current(WorkIssueRef issue, String label, List<LabelEvent> history,
                                        boolean completeHistory) {
        if (!completeHistory) {
            // Keep the trigger's actor only as a hint. An audit gap never turns an earlier
            // witnessed addition into proof of who applied the label's current incarnation.
            String hint = history.stream().map(LabelEvent::trackerActorId).filter(Objects::nonNull).findFirst().orElse(null);
            return new CurrentLabel(label, hint, LabelEvent.Origin.UNATTRIBUTED, null, AUDIT_INCOMPLETE);
        }
        Map<String, LabelEvent> unique = new LinkedHashMap<>();
        for (LabelEvent event : history) {
            if (!issue.equals(event.issue())) return unknown(label, ISSUE_MISMATCH);
            if (event.eventId() == null || event.eventId().isBlank() || event.occurredAt() == null)
                return unknown(label, MALFORMED_EVENT);
            LabelEvent previous = unique.putIfAbsent(event.eventId(), event);
            if (previous != null && !previous.equals(event)) return unknown(label, CONFLICTING_EVENT);
        }
        if (unique.isEmpty()) return unknown(label, NO_CURRENT_ADD);
        LabelEvent latest = unique.values().stream().max(Comparator.comparing(LabelEvent::occurredAt)).orElseThrow();
        // Opaque provider ordering tokens are not lexically comparable. Equal times without a
        // provider-specific proven total order are ambiguous, even if one happens to appear last.
        if (unique.values().stream().filter(e -> latest.occurredAt().equals(e.occurredAt())).count() != 1)
            return unknown(label, AMBIGUOUS_ORDER);
        if (latest.action() != LabelEvent.Action.ADD) return unknown(label, NO_CURRENT_ADD);
        if (latest.origin() != LabelEvent.Origin.WEBHOOK && latest.origin() != LabelEvent.Origin.AUDIT_TRAIL)
            return new CurrentLabel(label, latest.trackerActorId(), LabelEvent.Origin.UNATTRIBUTED,
                    latest.eventId(), UNATTRIBUTED);
        if (latest.trackerActorId() == null || latest.trackerActorId().isBlank()) return unknown(label, UNKNOWN_ACTOR);
        return new CurrentLabel(label, latest.trackerActorId(), latest.origin(), latest.eventId(), ATTRIBUTED);
    }

    private static CurrentLabel unknown(String label, CurrentLabel.Reason reason) {
        return new CurrentLabel(label, null, LabelEvent.Origin.UNATTRIBUTED, null, reason);
    }
}

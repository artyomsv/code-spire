package dev.codespire.orchestrator.work;

import dev.codespire.worksource.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Complete audit and two matching current-label reads, bounded as one remote observation. */
public record WorkEvidence(WorkIssueLocation issue, List<CurrentLabel> labels, String failure) {
    private static final int MAX_AUDIT_PAGES = 20;
    private static final int MAX_AUDIT_EVENTS = 2000;
    public WorkEvidence { labels = List.copyOf(labels); }

    public static WorkEvidence collect(Supplier<WorkSource> factory, WorkIssueLocation issue, LabelEvent hint) {
        FutureTask<WorkEvidence> task = new FutureTask<>(() -> read(factory.get(), issue, hint));
        Thread.ofVirtual().start(task);
        try { return task.get(20, TimeUnit.SECONDS); }
        catch (Exception failure) {
            task.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return new WorkEvidence(issue, List.of(), "tracker_unavailable");
        }
    }

    static WorkEvidence read(WorkSource source, WorkIssueLocation requested, LabelEvent hint) {
        WorkSource.Fetch first = source.fetch(requested);
        if (!(first instanceof WorkSource.Fetch.Found before)) return new WorkEvidence(requested, List.of(), "tracker_unavailable");
        if (!requested.ref().equals(before.ticket().location().ref())) return new WorkEvidence(requested, List.of(), "issue_identity_changed");
        List<LabelEvent> history = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        boolean complete = false;
        try {
            for (int page = 0; page < MAX_AUDIT_PAGES; page++) {
                WorkPage<LabelEvent> result = source.labelEvents(before.ticket().location(), cursor);
                history.addAll(result.items());
                if (history.size() > MAX_AUDIT_EVENTS) break;
                cursor = result.nextCursor();
                if (cursor == null) { complete = true; break; }
                if (!cursors.add(cursor)) break;
            }
        } catch (RuntimeException unavailable) { complete = false; }
        WorkSource.Fetch second = source.fetch(before.ticket().location());
        if (!(second instanceof WorkSource.Fetch.Found after)) return new WorkEvidence(requested, List.of(), "tracker_unavailable");
        if (!before.ticket().location().ref().equals(after.ticket().location().ref())
                || !before.ticket().currentLabels().equals(after.ticket().currentLabels()))
            return new WorkEvidence(requested, List.of(), "tracker_changed_during_read");
        // A verified hint can explain an unknown label, but cannot complete a missing audit page.
        if (!complete && hint != null) history.addFirst(hint);
        return new WorkEvidence(after.ticket().location(), LabelReconciler.reconcile(
                requested.ref(), after.ticket().currentLabels(), history, complete), null);
    }
}

package dev.codespire.worksource;

import java.util.Set;

/** Transient tracker content. Never persist this record in a workflow projection, event or inbox. */
public record WorkTicket(WorkIssueLocation location, String title, String body,
                         String trackerStatus, Set<String> currentLabels) {
    public WorkTicket { currentLabels = Set.copyOf(currentLabels); }
}

package dev.codespire.contract.review;

import java.util.List;
import java.util.Set;

/** Stored encrypted in the object store, referenced by {@code contextId} (DATA-MODEL.md §4). */
public record AssembledContext(String contextId,
                               List<ContextItem> items,
                               Set<String> contributingSources,
                               Set<String> missingSources) {

    public AssembledContext {
        items = items == null ? null : List.copyOf(items);
        contributingSources = contributingSources == null ? null : Set.copyOf(contributingSources);
        missingSources = missingSources == null ? null : Set.copyOf(missingSources);
    }
}

package dev.codespire.contract.review;

import java.util.List;
import java.util.Set;

/** Stored encrypted in the object store, referenced by {@code contextId} (DATA-MODEL.md §4). */
public record AssembledContext(String contextId,
                               List<ContextItem> items,
                               Set<String> contributingSources,
                               Set<String> missingSources) {
}

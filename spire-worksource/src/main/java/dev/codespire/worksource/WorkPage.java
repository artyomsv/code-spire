package dev.codespire.worksource;

import java.util.List;

/** Opaque provider cursor; null means the complete final page. Failures are never empty pages. */
public record WorkPage<T>(List<T> items, String nextCursor) {
    public WorkPage { items = List.copyOf(items); }
}

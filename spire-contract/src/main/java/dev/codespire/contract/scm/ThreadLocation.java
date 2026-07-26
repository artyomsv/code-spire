package dev.codespire.contract.scm;

/**
 * Where a comment thread sits in the diff: a file and a line on it.
 *
 * <p>Deliberately NOT {@link InlineAnchor}, which exists to POST a comment — it carries a
 * {@link Side}, an optional end line and an idempotency key, none of which an inbound thread has or
 * needs. An ingress reporting where a human commented would have to fabricate all three.
 *
 * <p>Null throughout the contract means "this thread has no location": a summary comment, a
 * top-level PR comment, or a provider that did not tell us. The line is the NEW side as the provider
 * reports it, matching the numbering findings are anchored at.
 */
public record ThreadLocation(String path, int line) {

    /** Null unless BOTH parts are present — a path with no line cannot be matched to a finding. */
    public static ThreadLocation of(String path, Integer line) {
        return path == null || path.isBlank() || line == null || line <= 0
                ? null : new ThreadLocation(path, line);
    }

    /** {@code path:line} — the same key {@code ReviewProjection} indexes findings' threads by. */
    public String loc() {
        return path + ":" + line;
    }
}

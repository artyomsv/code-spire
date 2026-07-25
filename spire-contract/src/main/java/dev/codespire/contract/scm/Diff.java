package dev.codespire.contract.scm;

import java.util.List;

/**
 * Canonical diff. NEVER persisted (ADR-011) — re-fetched by commit on demand.
 *
 * <p>{@code headCommit} is the commit the diff was produced against, which is all the core needs.
 * An API that requires more than that to place a comment — a base and start SHA alongside the head —
 * derives those inside its own adapter instead of carrying them through here.
 */
public record Diff(String headCommit, List<FilePatch> files, boolean truncated) {
}

package dev.codespire.contract.scm;

import java.util.List;

/** Canonical diff. NEVER persisted (ADR-011) — re-fetched by commit on demand. */
public record Diff(DiffRefs refs, List<FilePatch> files, boolean truncated) {
}

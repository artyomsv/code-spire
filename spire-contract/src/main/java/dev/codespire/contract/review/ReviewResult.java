package dev.codespire.contract.review;

import java.util.List;

/** The review output. Rides INLINE in events — never stored as a blob (ADR-011). */
public record ReviewResult(List<Finding> findings, String summary, ModelUsage usage) {

    public ReviewResult {
        findings = findings == null ? null : List.copyOf(findings);
    }
}

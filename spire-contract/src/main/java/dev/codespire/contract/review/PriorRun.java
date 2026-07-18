package dev.codespire.contract.review;

import java.util.List;

/** The last posted run's snapshot a follow-up review reconciles against (ADR-019). */
public record PriorRun(String headCommit, String summaryCommentId, List<PriorFinding> findings) {
    public PriorRun {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}

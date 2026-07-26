package dev.codespire.contract.review;

import java.util.List;

/**
 * The last posted run's snapshot a follow-up review reconciles against (ADR-019).
 *
 * <p>{@code summaryThreadRef} is the summary's THREAD — the one ref that both locates the
 * conversation and, via {@code CommentSink.updateComment}, the comment to rewrite in place. It is
 * opaque: whether a provider's thread and comment ids coincide is the adapter's business.
 */
public record PriorRun(String headCommit, String summaryThreadRef, List<PriorFinding> findings) {
    public PriorRun {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}

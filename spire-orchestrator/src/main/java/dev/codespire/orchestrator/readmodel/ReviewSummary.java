package dev.codespire.orchestrator.readmodel;

import java.time.Instant;

/**
 * One row of the reviews list (GET /api/reviews, and the /ws/reviews live feed).
 * {@code repo} duplicates {@code slug} as the display name; {@code stage} is the
 * active pipeline step index (0..5, or 6 = done).
 */
public record ReviewSummary(
        String id,
        String workspace,
        String slug,
        String repo,
        long pr,
        String title,
        String author,
        String authorId,
        String branch,
        String base,
        String sha,
        String htmlUrl,
        String providerType,
        String status,
        int stage,
        int findings,
        int blockerCount,
        /** How many of {@code findings} were already open before this run — the rest are new here. */
        int carriedOverFindings,
        long costMillicents,
        String model,
        String llmType,
        Instant updatedAt,
        boolean answering,
        String prState,
        /** Distinct calls the ledger could not price — lets the UI tell "zero spend" apart from
         *  "some calls have no known price yet" (never conflated into {@code costMillicents}). */
        int unpricedCalls,
        /** When this review was archived, or null while it is live. A third dimension beside
         *  {@code status} and {@code prState}: archiving must not overwrite whether the run
         *  completed or failed, which is the very statistic the row is retained for. */
        Instant archivedAt) {
}

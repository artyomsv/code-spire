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
        Instant updatedAt) {
}

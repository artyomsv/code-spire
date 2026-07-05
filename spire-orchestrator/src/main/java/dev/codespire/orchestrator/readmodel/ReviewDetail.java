package dev.codespire.orchestrator.readmodel;

import java.time.Instant;
import java.util.List;

/**
 * The per-PR detail payload (GET /api/reviews/{workspace}/{slug}/{pr}). Flat —
 * carries every {@link ReviewSummary} field plus the detail-only extras — so it
 * matches the frontend's {@code ReviewDetail extends ReviewSummary} shape.
 */
public record ReviewDetail(
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
        String status,
        int stage,
        int findings,
        Instant updatedAt,
        List<String> stages,
        List<String> timings,
        List<FindingView> findingsList,
        UsageView usage,
        String note,
        List<EventView> events) {

    /** A finding as the UI renders it: severity slug, "path:line" location, message. */
    public record FindingView(String sev, String loc, String msg) {
    }

    /** Model usage as display strings (tokens formatted, cost as dollars). */
    public record UsageView(String model, String prompt, String completion, String cost, String latency) {
    }

    /** One line of the review's scoped event stream. {@code at} is relative, e.g. "+0.8s". */
    public record EventView(String at, String lane, String type, String det) {
    }
}

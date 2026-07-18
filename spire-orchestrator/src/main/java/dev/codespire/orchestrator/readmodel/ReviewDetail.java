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
        String providerType,
        String status,
        int stage,
        int findings,
        int blockerCount,
        Instant updatedAt,
        int attempt,
        List<String> stages,
        List<String> timings,
        List<FindingView> findingsList,
        List<ReconciliationView> reconciliation,
        UsageView usage,
        List<LlmCall> llmCalls,
        String note,
        String errorDetail,
        List<EventView> events) {

    /** A finding as the UI renders it: severity slug, "path:line" location, message, and the SCM
     *  thread it owns ({@code threadRef}, null when it has no conversation / predates thread linking). */
    public record FindingView(String sev, String loc, String msg, String threadRef) {
    }

    /** One reconciliation verdict (ADR-019) as the UI renders it: the prior finding's severity/
     *  location/message, the verdict {@code status} ("still open" | "resolved" | "acknowledged" |
     *  "superseded"), the LLM's {@code note}, the owning SCM thread ({@code threadRef}, null when the
     *  prior finding was never posted), and whether that thread is now resolved on the SCM side. */
    public record ReconciliationView(String sev, String loc, String msg, String status, String note,
                                     String threadRef, boolean resolvedThread) {
    }

    /** Model usage as display strings (tokens formatted, cost as dollars). */
    public record UsageView(String model, String prompt, String completion, String cost, String latency) {
    }

    /**
     * One LLM call in the review's lifetime — the review generation ({@code kind = "review"}) or a
     * conversation follow-up ({@code kind = "followup"}) — for the cost-breakdown UI (roadmap 11).
     */
    public record LlmCall(String kind, String model, int tokensIn, int tokensOut, long costMillicents,
                          String createdAt) {
    }

    /**
     * One line of the review's scoped event stream. {@code ts}/{@code at}: see above. {@code threadRef}
     * is the SCM thread a conversation turn belongs to (null otherwise); {@code threadKind} classifies it
     * as "finding" | "summary" | "mention" for the UI (null for non-conversation turns).
     */
    public record EventView(String ts, String at, String lane, String type, String det,
                            String threadRef, String threadKind) {
    }
}

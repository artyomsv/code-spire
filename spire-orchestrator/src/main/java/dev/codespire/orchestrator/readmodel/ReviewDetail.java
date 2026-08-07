package dev.codespire.orchestrator.readmodel;

import java.time.Instant;
import java.util.List;

/**
 * The per-PR detail payload (GET /api/reviews/{workspace}/{slug}/{pr}). Flat —
 * carries every {@link ReviewSummary} field plus the detail-only extras — so it
 * matches the frontend's {@code ReviewDetail extends ReviewSummary} shape.
 *
 * <p>{@code findings}/{@code blockerCount} are this RUN's raw counts (the findings card's
 * "+ N more" math depends on that meaning). {@code openFindings}/{@code openBlockers} are the
 * reconciled currently-open counts (this run's new findings + still-open/unchanged
 * reconciliation, deduped) — the same figures the reviews list shows via
 * {@code ReviewProjection.openCounts}, so the header badge agrees with the list row instead of
 * quoting a stale per-run outcome.
 *
 * <p>{@code unpricedCalls} travels with {@code chargeLines} for the same reason it travels with the
 * list row's total: the page sums the lines itself, and an unpriced line contributes nothing to that
 * sum. Without the count, a review whose every call was unpriceable renders a confident total that
 * cannot be told apart from an asserted zero — so it is part of the payload, not something the client
 * infers.
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
        boolean answering,
        int stage,
        int findings,
        int blockerCount,
        int openFindings,
        int openBlockers,
        Instant updatedAt,
        int attempt,
        List<String> stages,
        List<String> timings,
        List<FindingView> findingsList,
        List<ReconciliationView> reconciliation,
        List<ChargeLineView> chargeLines,
        int unpricedCalls,
        String note,
        String errorDetail,
        List<EventView> events,
        String prState) {

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

    /**
     * One token dimension of one LLM call, priced. {@code rateMillicentsPerMillion} and
     * {@code costMillicents} are null exactly when {@code pricingMode} is "UNKNOWN" — never 0, which
     * would be indistinguishable from an UNMETERED model's asserted zero.
     *
     * <p>{@code callRef} is the ledger's own call identity ({@code UNIQUE (call_ref, token_type)}) — the
     * UI groups lines back into calls by it rather than by {@code pricedAt}, because that identity is
     * what defines a call. {@code pricedAt} would now agree (one call's lines are written in one
     * transaction, so they share {@code now()}), but agreeing by side effect is not the same as being
     * the key: two calls of the same review can share a timestamp, and only {@code call_ref} tells them
     * apart.
     *
     * <p>{@code kind}, {@code tokenType} and {@code pricingMode} are Strings here, not the enums they
     * mirror, deliberately: this is an outbound view read straight from the ledger, whose CHECK
     * constraints already restrict those columns. The enums exist to protect the WRITE path, where a
     * typo'd literal costs a lost charge; a display type has nothing to lose by carrying the stored
     * value verbatim, and typing it would force this record to be defined after the enums rather than
     * alongside the query that fills it.
     */
    public record ChargeLineView(String callRef, String kind, String model, String tokenType, int tokens,
                                 Long rateMillicentsPerMillion, Long costMillicents,
                                 String pricingMode, String pricedAt) {
    }

    /**
     * One line of the review's scoped event stream. {@code ts}/{@code at}: see above. {@code threadRef}
     * is the SCM thread a conversation turn belongs to (null otherwise); {@code threadKind} classifies it
     * as "finding" | "summary" | "mention" for the UI (null for non-conversation turns).
     */
    /**
     * {@code loc} is {@code path:line} when the event's thread sits somewhere in the diff, else null.
     *
     * <p>Present so a conversation the bot did not start can still be shown as anchored. The UI places
     * a conversation by matching {@code threadRef} against the threads a FINDING owns, so a
     * human-started thread on a line had nowhere to go and rendered as though it had no location at
     * all — even once the read model knew exactly where it was.
     */
    public record EventView(String ts, String at, String lane, String type, String det,
                            String threadRef, String threadKind, String loc) {
    }
}

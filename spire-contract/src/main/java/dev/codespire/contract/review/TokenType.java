package dev.codespire.contract.review;

/**
 * The neutral token-billing dimensions, as a PARTITION: every token a call consumed belongs to
 * exactly one of these. Vendors disagree on whether their detail counts are included in or additional
 * to the headline numbers, so each adapter subtracts as needed to produce disjoint counts — see
 * {@code TokenUsageMapper}.
 *
 * <p>{@link #TOTAL} is the degraded case, not a dimension: it carries a call's whole token count when
 * the per-type breakdown could not be reconciled against the vendor's own total. A TOTAL line can
 * never be priced at a metered rate, because there is no split to apply rates to.
 */
public enum TokenType {
    /** Fresh prompt tokens — the vendor's input count minus any cached portion. */
    INPUT,
    /** Prompt tokens served from the vendor's cache, billed at a reduced rate. */
    CACHED_INPUT,
    /** Prompt tokens written INTO the vendor's cache, billed at a premium. */
    CACHE_WRITE,
    /** Generated tokens — the vendor's output count minus any separately reported reasoning. */
    OUTPUT,
    /** Reasoning/thinking tokens, where the vendor reports them apart from output. */
    REASONING,
    /** Degraded: an unreconcilable call's whole token count. Never metered. */
    TOTAL
}

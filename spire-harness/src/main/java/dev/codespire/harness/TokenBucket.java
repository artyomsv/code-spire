package dev.codespire.harness;

/**
 * The token dimensions a harness may report, as a PARTITION: every token a run consumed belongs to
 * exactly one of these. Neutral — each adapter maps its vendor's shape onto them.
 *
 * <p><b>Vendors disagree about whether a detail count is INCLUDED in the headline number or
 * ADDITIONAL to it, so an adapter must subtract to produce disjoint counts.</b> OpenAI's input count
 * includes its cached portion and its output count includes reasoning; Anthropic's cache reads are
 * additional and excluded from input entirely. Writing both raw is the mistake that looks correct:
 * for a call reporting 120 input of which 8 were cached, an unsubtracted mapping reports 128 tokens
 * for a 120-token call — inflated most on exactly the runs that were cheapest. {@code TokenUsageMapper}
 * in spire-llm is the worked precedent for three vendors; follow it rather than inventing a second
 * convention.
 *
 * <p>{@link #TOTAL} is the degraded case, not a dimension: a run's whole token count when the
 * breakdown could not be reconciled against the vendor's own total. It is never mixed with the
 * others — a consumer summing {@code asMap().values()} would double-count the entire run — and it
 * can never be priced at a metered rate, because there is no split to apply rates to.
 *
 * <p>Deliberately a separate enum from {@code dev.codespire.contract.review.TokenType} rather than a
 * reuse of it: the factory tier does not inherit the review domain. The cost is that the two must map
 * one-to-one forever, and the translation sits exactly where ADR-023's failures lived — so
 * {@code TokenBucketMatchesLedgerDimensionsTest} fails the build on drift instead of letting a new
 * constant map silently to the wrong bucket.
 */
public enum TokenBucket {
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
    /** Degraded: an unreconcilable run's whole token count. Never metered, never mixed. */
    TOTAL
}

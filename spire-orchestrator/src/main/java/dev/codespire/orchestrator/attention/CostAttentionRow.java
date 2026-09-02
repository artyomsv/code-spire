package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;

import java.util.Arrays;
import java.util.Optional;

/**
 * The ledger-wide cost conditions, each carrying everything that makes it one row: the query that
 * counts it, the acknowledgement key that quiets it, and the sentence an operator reads.
 *
 * <p>Each is scoped to ONE {@code subject_kind}. The ledger is written by the review pipeline AND by
 * the factory, so a query reading both diagnoses one of them wrongly — which is how a run reporting no
 * usable token split came to be reported as a defect in a review-path class in another module.
 *
 * <p>These are the panel's only rows about the PAST rather than about current state, and that is why
 * they need an acknowledgement at all. A charge line's rate is immutable by design — the whole point of
 * snapshotting it — so entering the missing rates does not repair the rows already written, and the
 * count would otherwise be permanent and monotonically growing from the first unpriced call ever made.
 * V30 guarantees that state on any upgraded deployment (every legacy zero-priced model is left
 * rateless), so without a watermark these two would be the first rows in a panel built on "fixing the
 * cause removes the row" that no fix can remove.
 *
 * <p>The watermark is a timestamp rather than a flag, so a NEW unpriced call after the acknowledgement
 * raises the row again with only the unacknowledged calls in its count. A plain time window ("unpriced
 * in the last 7 days") was the simpler alternative and is rejected: it silently forgets a real backlog
 * nobody acted on, which is the failure this panel exists to prevent.
 *
 * <p>Each constant carries its own whole query, following {@code AttentionQueries.Registry}: nothing is
 * assembled at runtime, so there is no string building to reason about.
 *
 * <p>All exclude purged charges ({@code archived_at IS NULL}), like every other ledger read. A purge
 * hard-deletes the review these calls belonged to, so there is no page left to send an operator to and
 * no rate anyone can now enter — the row would name a backlog nothing can act on, and no watermark
 * clears it because the count is not what makes it unactionable.
 */
enum CostAttentionRow {

    /**
     * A call the ledger could not price. {@code TOTAL} is excluded so the unreconciled case below is
     * reported as the mapping defect it is, rather than sent to a settings page where entering a rate
     * would do nothing — the call is unpriced BECAUSE it did not reconcile. Do not "simplify" that
     * exclusion away: it is what keeps the two rows mutually exclusive.
     */
    UNPRICED("LLM_COST_UNPRICED", "attention.llm-cost-unpriced.ack-at",
            """
            SELECT count(DISTINCT call_ref) FROM llm_charge
             WHERE subject_kind = 'REVIEW' AND pricing_mode = 'UNKNOWN' AND token_type <> 'TOTAL'
               AND archived_at IS NULL AND priced_at > ?""",
            " LLM call(s) could not be priced, so the reported cost is lower than the real spend.",
            "/settings/llm"),

    /**
     * A call whose reported token breakdown did not sum to its own total, recorded as one undivided
     * {@code TOTAL} line. {@code LlmModelPricer.priceCall} returns EITHER per-type lines OR one
     * {@code TOTAL}, never both, so a {@code TOTAL} row is always this case.
     *
     * <p>No action link on purpose: a reconciliation failure is a {@code TokenUsageMapper} defect, so
     * there is no operator page that helps.
     */
    UNRECONCILED("LLM_USAGE_UNRECONCILED", "attention.llm-usage-unreconciled.ack-at",
            """
            SELECT count(DISTINCT call_ref) FROM llm_charge
             WHERE subject_kind = 'REVIEW' AND token_type = 'TOTAL'
               AND archived_at IS NULL AND priced_at > ?""",
            " LLM call(s) reported a token breakdown that did not match their own total, or reported no"
                    + " usage at all, so only an undivided total was recorded. On a metered model such"
                    + " a call cannot be priced at all; on an unmetered one its cost is still the"
                    + " asserted zero. This is a mapping defect rather than a setting, so the recorded"
                    + " breakdown will not change until it is fixed.",
            null),

    /**
     * A factory run whose spend the ledger could not price, so it is missing from the money axis of
     * the deployment-wide cap and only the call count bounds it.
     *
     * <p>Scoped to runs because the two rows above diagnose a REVIEW. Before that scoping, a run
     * reporting no usable split fired {@link #UNRECONCILED}, whose text names a {@code
     * TokenUsageMapper} defect — a review-path class in another module that cannot be the cause of a
     * run's row, and which offers no action. Worse, it fired on the UNMETERED arm too, where the cost
     * is an asserted zero and nothing is missing at all: a harness that reports only a high-water
     * total is behaving exactly as its adapter documents, so routine operation lit a permanent,
     * growing row about a defect that did not exist. That is the wallpaper this panel deliberately
     * excludes.
     *
     * <p>{@code pricing_mode = 'UNKNOWN'} is therefore the whole condition, and it is narrower than
     * "unreconciled" on purpose: an unmetered run records {@code UNMETERED} whatever its split turns
     * out to be, because {@code LlmModelPricer} consults the catalog BEFORE the reconciled check. So
     * marking a genuinely unbilled model unmetered removes this row — which is why it carries the
     * settings action the review-side unreconciled row cannot.
     *
     * <p>Both token types are counted here, unlike the mutually exclusive pair above: a run is one
     * charge, so an uncatalogued model's per-type UNKNOWN lines and an unpriceable total are the same
     * fact about the same call, and {@code subject_kind} is what keeps this row disjoint from those.
     */
    RUN_UNPRICED("RUN_SPEND_UNPRICED", "attention.run-spend-unpriced.ack-at",
            """
            SELECT count(DISTINCT call_ref) FROM llm_charge
             WHERE subject_kind = 'RUN' AND pricing_mode = 'UNKNOWN'
               AND archived_at IS NULL AND priced_at > ?""",
            " factory run(s) spent tokens the ledger could not price, so that spend is missing from the"
                    + " deployment's rolling window and only the call count bounds it. If the model is"
                    + " genuinely unbilled, marking it unmetered records the zero as asserted rather"
                    + " than unknown; otherwise its rates are what the ledger is missing.",
            "/settings/llm");

    /** Stable machine identifier, and the path segment the acknowledgement is addressed by. */
    private final String code;

    /** {@code app_setting} key holding the acknowledgement watermark. */
    private final String ackKey;

    private final String countQuery;
    private final String messageSuffix;
    private final String action;

    CostAttentionRow(String code, String ackKey, String countQuery, String messageSuffix, String action) {
        this.code = code;
        this.ackKey = ackKey;
        this.countQuery = countQuery;
        this.messageSuffix = messageSuffix;
        this.action = action;
    }

    static Optional<CostAttentionRow> byCode(String code) {
        return Arrays.stream(values()).filter(row -> row.code.equals(code)).findFirst();
    }

    String ackKey() {
        return ackKey;
    }

    String countQuery() {
        return countQuery;
    }

    /**
     * The row for {@code calls} unacknowledged calls. Dismissable, unlike almost every other condition:
     * this one describes calls already made, which no repair can un-make.
     */
    AttentionView view(int calls) {
        return new AttentionView(code, Severity.WARNING, null, calls + messageSuffix, action,
                "/api/attention/ack/" + code);
    }
}

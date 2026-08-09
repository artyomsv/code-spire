package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;

import java.util.Arrays;
import java.util.Optional;

/**
 * The two ledger-wide cost conditions, each carrying everything that makes it one row: the query that
 * counts it, the acknowledgement key that quiets it, and the sentence an operator reads.
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
 * <p>Both exclude purged charges ({@code archived_at IS NULL}), like every other ledger read. A purge
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
             WHERE pricing_mode = 'UNKNOWN' AND token_type <> 'TOTAL'
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
             WHERE token_type = 'TOTAL' AND archived_at IS NULL AND priced_at > ?""",
            " LLM call(s) reported a token breakdown that did not match their own total, or reported no"
                    + " usage at all, so only an undivided total was recorded. On a metered model such"
                    + " a call cannot be priced at all; on an unmetered one its cost is still the"
                    + " asserted zero. This is a mapping defect rather than a setting, so the recorded"
                    + " breakdown will not change until it is fixed.",
            null);

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

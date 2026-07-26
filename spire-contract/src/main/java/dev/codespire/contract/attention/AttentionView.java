package dev.codespire.contract.attention;

/**
 * One operator-facing condition that is true RIGHT NOW, derived on demand from state a
 * service already owns. Deliberately not a stored notification: there is no id, no
 * timestamp and no dismissal, because fixing the cause is what removes the row.
 *
 * <p>Shared by every service that reports conditions, so the UI can concatenate feeds from
 * more than one service without knowing which produced what.
 */
public record AttentionView(String code, Severity severity, String subject,
                            String message, String action) {

    /**
     * How badly the condition hurts. Two values on purpose — an operator triaging a bell
     * needs "nothing works" separated from "something is off", and finer grades would only
     * invite argument. Deliberately NOT named after review-finding severities
     * (BLOCKER/HIGH/...), which are a different vocabulary about defects in reviewed code.
     */
    public enum Severity {
        /** No review can complete until this is fixed. */
        BLOCKING,
        /** Some reviews are affected, or work has been dropped. */
        WARNING
    }

    /**
     * @param code     stable machine identifier; NEVER contains a provider name (ADR-020)
     * @param subject  what the condition is about — a provider name or repo target read
     *                 from the database, or null for a system-wide condition
     * @param message  one operator-facing sentence stating the consequence
     * @param action   a spire-ui route to the page that fixes it, or null
     */
    public AttentionView {
    }
}

package dev.codespire.contract.attention;

import java.time.Instant;

/**
 * One operator-facing condition that is true RIGHT NOW, derived on demand from state a
 * service already owns. Deliberately not a stored notification: there is no id and no
 * dismissal of its own, because fixing the cause is what removes the row.
 *
 * <p>Its one time, {@code since}, is read from that same stored state, never from the clock, so it
 * orders the bell newest first ({@link AttentionOrder}) without making the row a notification.
 *
 * <p>Shared by every service that reports conditions, so the UI can concatenate feeds from
 * more than one service without knowing which produced what.
 */
public record AttentionView(String code, Severity severity, String subject,
                            String message, String action, String dismiss, Instant since) {

    /**
     * A row that cannot be dismissed — every condition derived purely from current state.
     *
     * <p>This is the overwhelming majority and the default on purpose. A condition an operator can
     * actually fix must not be dismissable, or a broken system could be made to look healthy.
     */
    public AttentionView(String code, Severity severity, String subject, String message, String action) {
        this(code, severity, subject, message, action, null, null);
    }

    /** A row with no known time: a configuration condition, or a summary of rows not listed. */
    public AttentionView(String code, Severity severity, String subject, String message, String action,
                         String dismiss) {
        this(code, severity, subject, message, action, dismiss, null);
    }

    /**
     * This row with its stored time. The one place that rebuilds a row, so every component is named once and a
     * component added later cannot be dropped at a call site that only wanted to add a time.
     */
    public AttentionView withSince(Instant at) {
        return new AttentionView(code, severity, subject, message, action, dismiss, at);
    }

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
     * @param dismiss  an API path the UI POSTs to acknowledge this row, or null when the row is not
     *                 dismissable. Unlike {@code action} — a UI route — this is a server endpoint,
     *                 so the UI never has to reconstruct one from {@code subject}. Non-null only for
     *                 rows describing a PAST event that no fix can clear.
     * @param since    the stored time of the newest fact behind the row (a review's {@code updated_at}, a
     *                 run's {@code ended_at}, the newest pending dead letter's {@code created_at}), or null
     *                 when no stored time says when the condition arose. NEVER the evaluation's clock: the
     *                 orchestrator pushes only when the evaluated list changes, so a time taken at evaluation
     *                 would push the whole list on every sweep. Serialized as an ISO-8601 string.
     */
    public AttentionView {
    }
}

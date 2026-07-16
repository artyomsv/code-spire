package dev.codespire.contract.review;

import java.util.Locale;

/** How deeply the bot participates in review threads (spec §2). Configurable per provider over a global default. */
public enum ConversationLevel {

    /** Post findings, ignore replies. */
    REPORT_ONLY,
    /** Answer and defend findings; verdict immutable. */
    EXPLAIN,
    /** Can be convinced — verdict may change (Plan 2). */
    INTERACTIVE;

    /** Case-insensitive; null/unknown → REPORT_ONLY (fail safe: no conversation). */
    public static ConversationLevel parse(String value) {
        if (value == null) {
            return REPORT_ONLY;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return REPORT_ONLY;
        }
    }

    /** True when the bot replies at all (EXPLAIN or INTERACTIVE). */
    public boolean answers() {
        return this != REPORT_ONLY;
    }
}

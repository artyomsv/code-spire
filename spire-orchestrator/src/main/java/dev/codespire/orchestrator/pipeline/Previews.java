package dev.codespire.orchestrator.pipeline;

/**
 * ADR-011: the full conversation thread is never persisted — it lives on the SCM and is
 * re-fetchable there. Only a short, single-line preview of each turn is written to the review
 * timeline so the dashboard can show what happened. Shared by {@link IntegrationSaga} (the
 * author's reply) and {@link ResultSaga} (the bot's answer).
 */
final class Previews {

    private static final int MAX_LENGTH = 160;

    private Previews() {
    }

    /**
     * Null-safe preview: collapses any run of whitespace (including newlines) to a single
     * space, trims, and truncates to {@value #MAX_LENGTH} chars with a trailing "…" when longer.
     */
    static String of(String text) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= MAX_LENGTH) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_LENGTH) + "…";
    }
}

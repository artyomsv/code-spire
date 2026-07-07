package dev.codespire.diff;

/**
 * Token estimation + clipping, ported from pr-agent's clip_tokens semantics
 * (chars-per-token heuristic with a safety factor). A model-accurate counter
 * (jtokkit) can replace the estimate behind this same API later without
 * touching callers.
 */
public final class TokenBudget {

    /** Conservative chars-per-token for code-heavy text. */
    private static final double CHARS_PER_TOKEN = 3.2;
    /** Port of pr-agent's 0.9 safety factor on clipping. */
    private static final double SAFETY_FACTOR = 0.9;
    private static final String TRUNCATION_MARKER = "\n...(truncated to fit the model context)";

    private TokenBudget() {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /** Clips text to approximately maxTokens (with safety factor); appends a visible marker when clipped. */
    public static String clip(String text, int maxTokens) {
        if (text == null || maxTokens <= 0) {
            return "";
        }
        if (estimateTokens(text) <= maxTokens) {
            return text;
        }
        // The marker counts against the budget too, so small limits don't overshoot.
        int contentTokens = Math.max(0, maxTokens - estimateTokens(TRUNCATION_MARKER));
        int maxChars = (int) (contentTokens * CHARS_PER_TOKEN * SAFETY_FACTOR);
        maxChars = Math.max(0, Math.min(maxChars, text.length()));
        // Back off to the last line boundary — the LLM cites anchors from the
        // clipped text, and a dangling line fragment invites mis-cited anchors.
        // Raw cut only when there is no usable newline before the limit.
        int lastNewline = text.lastIndexOf('\n', maxChars);
        int cut = lastNewline > 0 ? lastNewline : maxChars;
        return text.substring(0, cut) + TRUNCATION_MARKER;
    }
}

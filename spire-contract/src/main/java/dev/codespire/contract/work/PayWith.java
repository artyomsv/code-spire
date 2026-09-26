package dev.codespire.contract.work;

/**
 * How a build pays for its model calls (M3.5 part F).
 *
 * <p>Two values, as names rather than a boolean: a third way to pay is easy to imagine, and a flag called
 * "subscription" would have to be rewritten to admit one. Every value written before this existed is an
 * API key, because until then that was the only way a run could pay.
 */
public final class PayWith {

    /** Per token, with a key from the credential pool. */
    public static final String API_KEY = "API_KEY";

    /** A signed-in seat: no per-token price, the real token counts still recorded. */
    public static final String SUBSCRIPTION = "SUBSCRIPTION";

    private PayWith() {
    }

    /** The value, with blank read as {@link #API_KEY}; anything else is refused rather than guessed. */
    public static String normalise(String value) {
        if (value == null || value.isBlank()) return API_KEY;
        String stripped = value.strip();
        if (!stripped.equals(API_KEY) && !stripped.equals(SUBSCRIPTION))
            throw new IllegalArgumentException("A build pays with " + API_KEY + " or " + SUBSCRIPTION + ", was: " + value);
        return stripped;
    }
}

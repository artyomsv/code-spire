package dev.codespire.contract.work;

/**
 * The one rule for what a thinking level may look like (M3.5 part M).
 *
 * <p>A level travels from an image label, through a saved setup and an approved binding, into a
 * config override on the harness's command line. The vendor's CLI does not check it (measured
 * 2026-09-22), so the shape is checked here, and every hop uses this same check: a label that
 * declared a level another hop refuses would let a setup be saved that could never prepare.
 */
public final class ThinkingLevel {

    private ThinkingLevel() {
    }

    /**
     * The level, stripped, or null for blank — which means the model's own default.
     *
     * @throws IllegalArgumentException when it is not a short lower-case word; such a word can close
     *                                  no quote and name no second config key
     */
    public static String normalise(String level) {
        if (level == null || level.isBlank()) return null;
        String stripped = level.strip();
        if (!stripped.matches("[a-z]{1,16}"))
            throw new IllegalArgumentException("A thinking level is a short lower-case word, was: " + level);
        return stripped;
    }
}

package dev.codespire.contract.review;

import java.util.Locale;

/**
 * What kind of problem a finding is about — the dimension learned memory groups on (P4, ADR-027).
 *
 * <p><b>Closed, deliberately.</b> A free-text category from a language model produces a long tail of
 * near-duplicate labels ({@code naming}, {@code Naming}, {@code variable naming}, {@code poor name})
 * that groups nothing, and grouping is the entire purpose: severity alone cannot express a preference
 * anyone would recognise as theirs. "The team dismissed 40 MINORs" is not actionable; "the team
 * dismisses naming findings in test files" is.
 *
 * <p>{@link #OTHER} exists so the model always has a valid answer and never has to invent an
 * eleventh label.
 */
public enum FindingCategory {

    NAMING,
    ERROR_HANDLING,
    TEST_COVERAGE,
    PERFORMANCE,
    SECURITY,
    CORRECTNESS,
    STYLE,
    DOCS,
    COMPLEXITY,
    OTHER;

    /**
     * Parses a model-supplied label, or null.
     *
     * <p><b>An unrecognised label is null, not {@link #OTHER}.</b> {@code OTHER} is an answer the
     * model gave — it looked at the finding and judged it to fit nothing more specific. An
     * unparseable label is <em>unknown</em>: nobody knows what the model meant. The same distinction
     * the nullable {@code verdict} column rests on, and the one ADR-023 paid for learning, where four
     * separate places turned <em>unknown</em> into <em>zero</em> and a spend cap built on those
     * numbers would never have fired.
     *
     * <p>Null is also what every value predating this enum parses to — old encrypted
     * {@code findings_json} blobs and command-carried prior runs have no category at all.
     */
    public static FindingCategory parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (FindingCategory candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}

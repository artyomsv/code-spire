package dev.codespire.contract.review;

/**
 * One review finding. {@code message}/{@code suggestion} may quote source code
 * — they are encrypted at rest in read models (DATA-MODEL.md §5, ADR-014).
 * {@code suggestion} is a proposed replacement, nullable; it is rendered as a
 * suggestion the human accepts, never auto-applied (SECURITY.md).
 *
 * <p>{@code category} is nullable and is the dimension learned memory groups on (P4, ADR-027). Null
 * means the model did not supply one — which happens for real, not just in theory: prompts are
 * operator-customizable per repository since E16, so a customized {@code REVIEW} template does not
 * ask for the field, and every value stored before this component existed has none either. Those
 * findings degrade to severity-and-path grouping rather than failing.
 */
public record Finding(String path, LineRange range, Severity severity, FindingCategory category,
                      String message, String suggestion) {

    /**
     * The shape before {@code category} existed. Kept so the thirty-odd construction sites that do
     * not care about it stay readable, and because a category is genuinely optional.
     */
    public Finding(String path, LineRange range, Severity severity, String message, String suggestion) {
        this(path, range, severity, null, message, suggestion);
    }

    /**
     * Same finding, different category.
     *
     * <p>Exists because rebuilding this record by listing its components is the trap the 2026-08-28
     * work recorded: adding a component compiles at every rebuild site, since the shorter convenience
     * constructor above stays valid, and the new field is silently dropped. A wither enumerates the
     * components once, next to the record, so there is one place to update rather than a search.
     */
    public Finding withCategory(FindingCategory replacement) {
        return new Finding(path, range, severity, replacement, message, suggestion);
    }
}

package dev.codespire.orchestrator.factory;

/**
 * What a run cost, or the honest statement that nobody knows.
 *
 * <p><b>Unknown is not zero, and this type exists so it cannot become zero by accident.</b> That is
 * ADR-023's rule and this project has already paid for it once: {@code SUM()} skips NULL, so a run
 * whose charges are unpriced would total to whatever the priced ones came to, and a run with no
 * charges at all would total to nothing — both rendering as "free" beside runs that really were.
 * A caller holding a {@code long} cannot tell those apart; a caller holding this must decide.
 *
 * <p>Three states collapse to one unknown, deliberately, because they are one answer to the question
 * a reader is asking:
 *
 * <ul>
 *   <li>the run has not finished, so no charge has landed yet;</li>
 *   <li>it finished and the model had no usable pricing ({@code pricing_mode = 'UNKNOWN'}, which
 *       V30's own CHECK ties to a null cost);</li>
 *   <li>it finished and reported no usage at all — {@code RunFinished} refuses an empty usage map
 *       precisely so "measured nothing" and "measured zero" stay different.</li>
 * </ul>
 *
 * <p>Distinguishing them is a job for the run's own status, which the caller already has beside this.
 *
 * @param millicents null when unknown. <b>A boxed {@code Long} rather than an {@code OptionalLong},
 *     because this record goes on the wire</b>: an {@code Optional*} serialises only when a Jackson
 *     module is registered for it, and its shape differs between them — a money field whose JSON
 *     depends on module registration is a money field that can silently become {@code 0} or
 *     {@code {"present":false}} in one service and not another. As a nullable Long the wire form is
 *     {@code null} or a number, which is unambiguous everywhere and still is not zero
 */
public record RunCost(Long millicents) {

    private static final RunCost UNKNOWN = new RunCost(null);

    public RunCost {
        if (millicents != null && millicents < 0) {
            // V31 constrains the column non-negative, so this is a caller or a join bug rather than
            // data. Refusing beats rendering a negative cost, which reads as a refund.
            throw new IllegalArgumentException("a run cannot cost less than nothing: " + millicents);
        }
    }

    /** Nobody knows: not charged yet, not priceable, or no usage reported. */
    public static RunCost unknown() {
        return UNKNOWN;
    }

    /**
     * @param millicents the summed charge lines. Zero is a legitimate KNOWN value — an UNMETERED
     *     model is priced at zero by definition (V30 requires exactly that), and reporting it as
     *     unknown would hide a self-hosted deployment's real answer
     */
    public static RunCost of(long millicents) {
        return new RunCost(millicents);
    }

    public boolean isKnown() {
        return millicents != null;
    }

    /**
     * Add another run's cost, staying unknown if either side is.
     *
     * <p><b>A total over a list is unknown if ANY member is</b>, which is the property a list footer
     * needs and the one a naive sum destroys. A caller wanting "the known part plus a count of the
     * rest" should count the rest itself — that is a different question and it should look different.
     */
    public RunCost plus(RunCost other) {
        if (!isKnown() || !other.isKnown()) {
            return unknown();
        }
        return of(millicents + other.millicents);
    }
}

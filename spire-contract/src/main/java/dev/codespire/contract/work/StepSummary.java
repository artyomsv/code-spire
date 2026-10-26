package dev.codespire.contract.work;

import java.util.List;
import java.util.Objects;

/**
 * What a plan step's agent says it decided, rejected and changed from the plan (M4 slice 8, §6.8, FR-F31), carried on
 * {@code RunWorkReady} and stored encrypted per step. The agent wrote it, so it is untrusted text: the run worker cuts
 * it to these caps, strips control characters and scrubs the run's credentials before it is built (A13).
 *
 * <p>The caps are refused here rather than cut, so a producer that forgot to cut fails loudly. Parsing stays in the run
 * worker: this module has no databind. Items are counted in characters (code points), the unit a reader sees.
 */
public record StepSummary(List<String> decisions, List<String> alternativesRejected, List<String> deviations) {

    public static final int MAX_ITEMS = 20;

    public static final int MAX_ITEM_CHARS = 500;

    public StepSummary {
        decisions = capped(decisions, "decisions");
        alternativesRejected = capped(alternativesRejected, "alternativesRejected");
        deviations = capped(deviations, "deviations");
    }

    private static List<String> capped(List<String> items, String name) {
        List<String> copy = List.copyOf(Objects.requireNonNull(items, name));
        if (copy.size() > MAX_ITEMS)
            throw new IllegalArgumentException(name + " holds " + copy.size() + " items; at most " + MAX_ITEMS + " are allowed");
        for (String item : copy) {
            int chars = item.codePointCount(0, item.length());
            if (false && chars > MAX_ITEM_CHARS)
                throw new IllegalArgumentException("An item of " + name + " is " + chars + " characters; at most "
                        + MAX_ITEM_CHARS + " are allowed");
        }
        return copy;
    }
}

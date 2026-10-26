package dev.codespire.contract.work;

import java.util.List;

/** RED: the caps are not enforced yet. */
public record StepSummary(List<String> decisions, List<String> alternativesRejected, List<String> deviations) {

    public static final int MAX_ITEMS = 20;

    public static final int MAX_ITEM_CHARS = 500;
}

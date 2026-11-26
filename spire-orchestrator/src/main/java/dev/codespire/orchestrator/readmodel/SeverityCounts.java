package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.Severity;

import java.util.List;

/**
 * A review's open findings counted per severity, for the reviews list's findings badges. The components are the
 * dashboard's finding slugs, the labels and colours the detail page's findings card uses, so the list and the card
 * name a severity the same way; INFO and NIT share {@code nit} there as they do on the card.
 *
 * <p>The four always sum to the list row's {@code findings}: both are counted from the one set of open findings
 * ({@link OpenFindingCounts}), carried-over ones included.
 */
public record SeverityCounts(int critical, int warning, int suggestion, int nit) {

    /** Counts each severity; an empty list is all zeros. */
    static SeverityCounts of(List<Severity> severities) {
        int[] counts = new int[4];
        for (Severity severity : severities) {
            counts[slot(severity)]++;
        }
        return new SeverityCounts(counts[0], counts[1], counts[2], counts[3]);
    }

    /** The slot of {@code severity}, most severe first, in the order the slugs name them. */
    private static int slot(Severity severity) {
        return switch (severity) {
            case BLOCKER -> 0;
            case MAJOR -> 1;
            case MINOR -> 2;
            case INFO, NIT -> 3;
        };
    }
}

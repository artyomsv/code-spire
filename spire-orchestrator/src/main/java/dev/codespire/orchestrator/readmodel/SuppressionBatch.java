package dev.codespire.orchestrator.readmodel;

import java.util.List;

/**
 * The findings one learned preference hid, and which preference did it (P4 / FR-10).
 *
 * <p>One list of locations rather than parallel lists of paths and lines, which could disagree in
 * length — a guard the previous signature needed and this shape makes unnecessary.
 */
public record SuppressionBatch(long preferenceId, List<Location> hidden) {

    /** Where a hidden finding sat. */
    public record Location(String path, int line) {
    }
}

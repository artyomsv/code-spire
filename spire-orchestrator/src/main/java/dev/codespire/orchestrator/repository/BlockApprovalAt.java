package dev.codespire.orchestrator.repository;

import dev.codespire.contract.review.Severity;

import java.util.Locale;

/**
 * A repository's "Block approval at" (V115): the lowest severity whose open finding makes a review's assessment
 * Request changes and holds a factory land. INFO and NIT never block, so they are not levels.
 */
public enum BlockApprovalAt {
    BLOCKER, MAJOR, MINOR;

    /** What an unknown repository, and every repository from before the setting, blocks at. */
    public static final BlockApprovalAt DEFAULT = BLOCKER;

    /** Whether an open finding of this severity blocks approval at this level; a null severity never does. */
    public boolean blocks(Severity severity) {
        if (severity == null) return false;
        return switch (severity) {
            case BLOCKER -> true;
            case MAJOR -> this != BLOCKER;
            case MINOR -> this == MINOR;
            case INFO, NIT -> false;
        };
    }

    /**
     * The stored form of an input value; null means {@link #DEFAULT}, like an absent "reviewer may approve" means on.
     *
     * @throws IllegalArgumentException for any other value, so the resource answers 400 with the reason
     */
    public static BlockApprovalAt parse(String value) {
        if (value == null) return DEFAULT;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Block approval at: choose BLOCKER, MAJOR or MINOR, not " + value);
        }
    }
}

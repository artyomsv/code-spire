package dev.codespire.orchestrator.repository;

import java.util.Locale;

/**
 * A repository's "Start review" (V121, docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md): whether a
 * review of a new head starts at once or waits until every check on that head has finished.
 */
public enum StartReview {
    IMMEDIATELY, AFTER_CHECKS;

    /** What every repository from before the setting, and an unknown one, does. */
    public static final StartReview DEFAULT = IMMEDIATELY;

    /**
     * The stored form of an input value, or null when none was sent (create: {@link #DEFAULT}; update: keep).
     *
     * @throws IllegalArgumentException for any other value, so the resource answers 400 with the reason
     */
    public static String normalize(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Start review: choose IMMEDIATELY or AFTER_CHECKS, not " + value);
        }
    }
}

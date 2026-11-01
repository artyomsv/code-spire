package dev.codespire.contract.review;

import java.util.Objects;

/**
 * Which review call a harness review run answers (harness reviews, step 4): the review, the kind of call, the pull
 * request, the head it reads and the review's call slot its charge is recorded under. It rides the run command and
 * comes back on the run's artifact, so a result names the call it answers without a lookup.
 */
public record ReviewRunBinding(String reviewId, Kind kind, long prId, String commit, String callSlot) {

    /** The review call a run answers. Each has the run kind and the charge kind of the same name. */
    public enum Kind { REVIEW, RECONCILE, FOLLOWUP }

    public ReviewRunBinding {
        // Blank refused like null: a blank review or slot still builds a well-formed-looking charge key, and two calls
        // sharing it would make the second charge a "redelivery" of the first (CallRefs.of says the same).
        if (reviewId == null || reviewId.isBlank()) throw new IllegalArgumentException("A review run names its review");
        Objects.requireNonNull(kind, "A review run names the kind of call it answers");
        if (prId < 1) throw new IllegalArgumentException("A review run names its pull request, not " + prId);
        if (commit == null || commit.isBlank()) throw new IllegalArgumentException("A review run names the head it reads");
        if (callSlot == null || callSlot.isBlank()) throw new IllegalArgumentException("A review run names its call slot");
    }
}

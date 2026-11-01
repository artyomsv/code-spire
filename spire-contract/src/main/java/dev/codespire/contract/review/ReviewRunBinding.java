package dev.codespire.contract.review;

/**
 * Which review call a harness review run answers (harness reviews, step 4): the review, the kind of call, the pull
 * request, the head it reads and the review's call slot its charge is recorded under. It rides the run command and
 * comes back on the run's artifact, so a result names the call it answers without a lookup.
 */
public record ReviewRunBinding(String reviewId, Kind kind, long prId, String commit, String callSlot) {

    /** The review call a run answers. Each has the run kind and the charge kind of the same name. */
    public enum Kind { REVIEW, RECONCILE, FOLLOWUP }
}

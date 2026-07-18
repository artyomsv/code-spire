package dev.codespire.contract.review;

/**
 * The reconcile call's judgment on one prior finding. {@code note} explains a
 * STILL_OPEN gap or a concession — it may quote source, so at-rest storage is
 * encrypted like findings.
 */
public record FindingVerdict(String threadRef, String path, int line, Status status, String note) {

    public enum Status { RESOLVED, STILL_OPEN, ACKNOWLEDGED, SUPERSEDED, UNCHANGED }
}

package dev.codespire.orchestrator.llm;

import java.util.List;
import java.util.Objects;

/**
 * One paid call's charge lines plus the identity they are recorded under.
 *
 * <p>The subject used to be a {@code reviewId} with the writer binding the literal {@code "REVIEW"}
 * beside it. That was correct while a review was the only thing this deployment could spend money
 * on, and it is the shape where a name lies once a run can spend too ({@code V42} renamed the column
 * for the same reason). Both halves are carried explicitly now, because the pair is what attributes
 * money to a thing and a mismatch between them is not detectable afterwards.
 *
 * @param subjectId   the reviewId or runId this spend belongs to
 * @param subjectKind which of those it is; the ledger's {@code subject_kind} CHECK lists the names
 * @param capability  which capability pack caused the spend (ADR-035) — recorded now because it
 *                    cannot be inferred later
 * @param callRef     the deterministic key that makes recording idempotent under redelivery — see
 *                    {@link CallRefs}
 * @param kind        which paid call this is; stored as the enum NAME, which the ledger's kind CHECK
 *                    lists verbatim
 */
public record ChargeCall(String subjectId, ChargeSubject subjectKind, ChargeCapability capability,
                         String callRef, ChargeKind kind, String model, List<ChargeLine> lines,
                         String credentialRef) {

    public ChargeCall {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(subjectKind, "subjectKind");
        Objects.requireNonNull(capability, "capability");
        // A record's canonical constructor cannot be narrower than the record, so the factories
        // below are a convention rather than a gate. This is the gate: the javadoc's whole reason
        // for carrying both halves is that a mismatch is not detectable afterwards, and a run id
        // has a recognisable shape, so "this says RUN and does not look like a run" is checkable
        // at the one moment it can still be refused.
        if (subjectKind == ChargeSubject.RUN && !subjectId.startsWith(RUN_ID_PREFIX)) {
            throw new IllegalArgumentException(
                    "a RUN charge's subject must be a run id, which '" + subjectId + "' is not;"
                            + " money attributed to the wrong subject cannot be traced back later");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** What every run id begins with — see {@code RunIds}, which the ledger tier does not depend on. */
    private static final String RUN_ID_PREFIX = "run:";

    /** A review's spend: the reviewer capability, against a reviewId. */
    public static ChargeCall forReview(String reviewId, String callRef, ChargeKind kind,
                                       String model, List<ChargeLine> lines) {
        return new ChargeCall(reviewId, ChargeSubject.REVIEW, ChargeCapability.REVIEW,
                callRef, kind, model, lines, null);
    }

    /**
     * A factory run's spend: the build capability, against a runId.
     *
     * <p>V42 added {@code llm_charge.credential_ref} for exactly this and nothing wrote it, because
     * until the credential pool there was no per-run key identity to write. On an UNMETERED
     * deployment every run charge is an asserted zero, so "which key spent this" was unanswerable by
     * any other route — the money column cannot distinguish two keys that both cost nothing.
     *
     * @param credentialRef which pool member paid, or null when the run names none
     */
    public static ChargeCall forRun(String runId, String callRef, String model, List<ChargeLine> lines,
                                    String credentialRef) {
        return new ChargeCall(runId, ChargeSubject.RUN, ChargeCapability.BUILD,
                callRef, ChargeKind.BUILD, model, lines, credentialRef);
    }
}

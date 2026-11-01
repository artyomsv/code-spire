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
     * A harness review run's spend (harness reviews, step 4): the reviewer capability, against the review, naming the
     * key or seat that paid. The run made the call, but the review is what the call was for and what its cap counts.
     */
    public static ChargeCall forReview(ReviewSpend spend, String model, List<ChargeLine> lines) {
        return new ChargeCall(spend.reviewId(), ChargeSubject.REVIEW, ChargeCapability.REVIEW,
                spend.callRef(), spend.kind(), model, lines, spend.credentialRef());
    }

    /**
     * A factory run's spend against a runId, under the run's own kind and always the Build capability.
     * Spec and plan are how a work item becomes a pull request, so they are in the Build pack with
     * build, verify and deliver ({@code docs/factory/PACKAGING.md} section 2, ADR-035). Autonomy is the
     * absence of the human, not a phase: charging preparation to it would put a Build-only deployment's
     * spend under a pack it never bought, and V42 never backfills the capability.
     *
     * <p>V42 added {@code llm_charge.credential_ref} for exactly this and nothing wrote it, because
     * until the credential pool there was no per-run key identity to write. On an UNMETERED
     * deployment every run charge is an asserted zero, so "which key spent this" was unanswerable by
     * any other route — the money column cannot distinguish two keys that both cost nothing.
     *
     * @param spend the run, its key, its kind and the pool member that paid
     */
    public static ChargeCall forRun(RunSpend spend, String model, List<ChargeLine> lines) {
        return new ChargeCall(spend.runId(), ChargeSubject.RUN, ChargeCapability.BUILD,
                spend.callRef(), spend.kind(), model, lines, spend.credentialRef());
    }
}

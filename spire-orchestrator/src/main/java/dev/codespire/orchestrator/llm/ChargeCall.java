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
                         String callRef, ChargeKind kind, String model, List<ChargeLine> lines) {

    public ChargeCall {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(subjectKind, "subjectKind");
        Objects.requireNonNull(capability, "capability");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A review's spend: the reviewer capability, against a reviewId. */
    public static ChargeCall forReview(String reviewId, String callRef, ChargeKind kind,
                                       String model, List<ChargeLine> lines) {
        return new ChargeCall(reviewId, ChargeSubject.REVIEW, ChargeCapability.REVIEW,
                callRef, kind, model, lines);
    }

    /** A factory run's spend: the build capability, against a runId. */
    public static ChargeCall forRun(String runId, String callRef, String model, List<ChargeLine> lines) {
        return new ChargeCall(runId, ChargeSubject.RUN, ChargeCapability.BUILD,
                callRef, ChargeKind.BUILD, model, lines);
    }
}

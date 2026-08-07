package dev.codespire.orchestrator.llm;

import java.util.List;

/**
 * One LLM call's charge lines plus the identity they are recorded under.
 *
 * @param callRef the deterministic key that makes recording idempotent under redelivery — see
 *                {@link CallRefs}
 * @param kind    which paid call this is; stored as the enum NAME, which the ledger's kind CHECK
 *                lists verbatim
 */
public record ChargeCall(String reviewId, String callRef, ChargeKind kind, String model,
                         List<ChargeLine> lines) {

    public ChargeCall {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}

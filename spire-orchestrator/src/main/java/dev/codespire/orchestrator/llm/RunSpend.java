package dev.codespire.orchestrator.llm;

import java.util.Objects;

/**
 * Who a factory run's spend is recorded under: the run, the key that makes the write idempotent, the
 * run's own kind, and the pool member that paid.
 *
 * <p>Grouped so {@link ChargeCall#forRun} takes the kind from the run's row rather than assuming one.
 *
 * @param runId         the run this spend belongs to
 * @param callRef       the deterministic key — see {@link CallRefs#forRun}
 * @param kind          the run's kind as the ledger names it: BUILD, FIX, SPEC or PLAN
 * @param credentialRef which pool member paid, or null when the run names none
 */
public record RunSpend(String runId, String callRef, ChargeKind kind, String credentialRef) {

    public RunSpend {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(callRef, "callRef");
        Objects.requireNonNull(kind, "kind");
    }
}

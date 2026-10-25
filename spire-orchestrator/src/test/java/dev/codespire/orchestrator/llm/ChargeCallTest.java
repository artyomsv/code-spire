package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A run's charge carries the run's own kind, and every run is charged to the Build capability (ADR-035).
 *
 * <p>{@code forRun} used to hard-code BUILD for the kind too, so every fix run's ledger row said BUILD.
 * The capability stays BUILD for all four kinds: {@code docs/factory/PACKAGING.md} section 2 puts spec
 * and plan in the Build pack, and Autonomy is the absence of the human, not a phase. A Build-only
 * deployment's preparation spend recorded under Autonomy could never be corrected, since V42 never
 * backfills the capability.
 */
class ChargeCallTest {

    private static final String RUN_ID = "run::github:TEST-acme/app:TEST-subject:1";
    private static final String CALL_REF = "TEST-call-ref";
    private static final List<ChargeLine> LINES = List.of(ChargeLine.metered(TokenType.INPUT, 10, 1L));

    @Test
    void specAndPlanRunsAreBuild() {
        for (ChargeKind kind : List.of(ChargeKind.SPEC, ChargeKind.PLAN)) {
            ChargeCall call = ChargeCall.forRun(new RunSpend(RUN_ID, CALL_REF, kind, null), "TEST-model", LINES);

            assertEquals(ChargeCapability.BUILD, call.capability(), kind + " is in the Build pack (PACKAGING.md)");
            assertEquals(kind, call.kind());
            assertEquals(ChargeSubject.RUN, call.subjectKind());
        }
    }

    @Test
    void buildAndFixKeepTheBuildCapability() {
        for (ChargeKind kind : List.of(ChargeKind.BUILD, ChargeKind.FIX)) {
            ChargeCall call = ChargeCall.forRun(new RunSpend(RUN_ID, CALL_REF, kind, "TEST-credential"),
                    "TEST-model", LINES);

            assertEquals(ChargeCapability.BUILD, call.capability());
            assertEquals(kind, call.kind(), "the run's own kind, not a hard-coded BUILD");
            assertEquals("TEST-credential", call.credentialRef());
        }
    }

    @Test
    void aReviewKindIsNotARunSpend() {
        for (ChargeKind kind : List.of(ChargeKind.REVIEW, ChargeKind.RECONCILE, ChargeKind.FOLLOWUP)) {
            assertThrows(IllegalArgumentException.class, () -> new RunSpend(RUN_ID, CALL_REF, kind, null),
                    kind + " is a review call, which no run can make");
        }
    }
}

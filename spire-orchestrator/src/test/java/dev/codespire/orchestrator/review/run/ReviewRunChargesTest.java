package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunKind;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeCapability;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.ChargeSubject;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A harness review run's spend: one charge under the review's call slot, naming the key or seat that paid, priced the
 * way the run paid. A plain unit test; every method the path reaches is answered by a fake, because an un-overridden
 * one opens a real database from a unit test (CLAUDE.md, saga test fakes).
 */
class ReviewRunChargesTest {

    private static final String RUN_ID = "run::github:TEST-WS/TEST-REPO:review-7-aaaaaaaaaaaa-0123abcd:1";

    private static final UUID SEAT = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private static final ReviewRunBinding CALL =
            new ReviewRunBinding("review::TEST-WS/TEST-REPO#7", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1);

    private final List<ChargeCall> recorded = new ArrayList<>();
    private final List<String> pricedAs = new ArrayList<>();
    private String paidBy = "SUBSCRIPTION";

    private ReviewRunCharges charges() {
        ReviewRunCharges charges = new ReviewRunCharges();
        charges.ledger = new ReviewProjection() {
            @Override
            public void recordCharges(ChargeCall call) {
                recorded.add(call);
            }
        };
        charges.runs = runs();
        charges.pricer = new LlmModelPricer() {
            @Override
            public List<ChargeLine> priceCall(String model, ModelUsage usage) {
                pricedAs.add("call:" + model);
                return usage.counts().stream().map(count -> ChargeLine.metered(count.type(), count.tokens(), 1L)).toList();
            }

            @Override
            public List<ChargeLine> priceUnmetered(ModelUsage usage) {
                pricedAs.add("unmetered");
                return usage.counts().stream().map(count -> ChargeLine.unmetered(count.type(), count.tokens())).toList();
            }
        };
        charges.maxReportedTokens = -1;
        return charges;
    }

    private FactoryRunProjection runs() {
        return new FactoryRunProjection() {
            @Override public Optional<String> modelOf(String runId) { return Optional.of("TEST-model"); }
            @Override public Optional<String> paidByOf(String runId) { return Optional.of(paidBy); }
            @Override public Optional<UUID> harnessCredentialOf(String runId) { return Optional.of(SEAT); }
            @Override public Optional<RunKind> kindOf(String runId) { return Optional.of(RunKind.REVIEW); }
            @Override public Optional<ReviewRunBinding> reviewBindingOf(String runId) { return Optional.of(CALL); }
            @Override protected void push(String runId) { /* nothing: no live feed from a unit test */ }
        };
    }

    private static RunResult artifact(Map<String, Long> usage) {
        return new RunReviewArtifact(RUN_ID, CALL, "TEST-findings", false, usage, 4, List.of());
    }

    @Test
    void aSeatReviewRunIsChargedOnceAtZeroWithItsTokensAndCredential() {
        charges().record(artifact(Map.of("INPUT", 1200L, "OUTPUT", 340L)), CALL);

        assertEquals(1, recorded.size());
        ChargeCall call = recorded.getFirst();
        assertEquals(CALL.reviewId(), call.subjectId());
        assertEquals(ChargeSubject.REVIEW, call.subjectKind());
        assertEquals(ChargeCapability.REVIEW, call.capability());
        assertEquals(CallRefs.of(CALL.reviewId(), CALL.callSlot(), ChargeKind.REVIEW), call.callRef());
        assertEquals(ChargeKind.REVIEW, call.kind());
        assertEquals(SEAT.toString(), call.credentialRef());
        assertEquals(List.of("unmetered"), pricedAs, "a seat's tokens cost nothing per token");
        assertTrue(call.lines().contains(ChargeLine.unmetered(TokenType.INPUT, 1200)), call.lines().toString());
        assertTrue(call.lines().contains(ChargeLine.unmetered(TokenType.OUTPUT, 340)), call.lines().toString());
    }

    @Test
    void aKeyReviewRunIsPricedAsACall() {
        paidBy = "API_KEY";

        charges().record(artifact(Map.of("INPUT", 1200L)), CALL);

        assertEquals(List.of("call:TEST-model"), pricedAs);
        assertEquals(1, recorded.size());
    }

    /** A failure raised before the agent ran bought nothing, so it consumes no call from the cap either. */
    @Test
    void aReviewRunWithNoTokensIsNotCharged() {
        charges().record(new RunResult.RunFailed(RUN_ID, "RUNTIME_UNAVAILABLE", "TEST-detail", true, null, List.of()), CALL);

        assertEquals(List.of(), recorded);
    }

    @Test
    void aFailedReviewRunWithTokensIsStillCharged() {
        ReviewRunBinding reconcile = new ReviewRunBinding(CALL.reviewId(), ReviewRunBinding.Kind.RECONCILE, 7, CALL.commit(), "TEST-slot", 1);

        charges().record(new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 90L), List.of()),
                reconcile);

        assertEquals(1, recorded.size());
        assertEquals(ChargeKind.RECONCILE, recorded.getFirst().kind());
        assertEquals(CallRefs.of(CALL.reviewId(), "TEST-slot", ChargeKind.RECONCILE), recorded.getFirst().callRef());
    }
}

package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.caps.CapPolicy;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.caps.SpendWindow;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.DefaultLlm;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewDetail;
import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pre-spend gate at ContextAssembled, beside ADR-023's priceability check ({@code isSpendable}).
 *
 * <p>Every assertion against a limit is a DELTA against a baseline measured moments earlier
 * ({@code SpendWindowIT}'s own posture) — this read is deployment-wide and the module shares one
 * database across test classes, so ambient charges from other suites are always in the window. A
 * hard-coded dollar figure would be true only when this suite happened to run in an empty database.
 *
 * <p>Wired the same way as {@link ResultSagaPricingTest#sagaFor} for the LLM credential and the
 * paid-command sink, but with the REAL {@link SpendGate}/{@link CapPolicy}/{@link ReviewProjection}
 * beans injected — the gate under test reads the real ledger and the real settings store, not a fake
 * standing in for them. {@link SpendWindow} is injected only to measure the baseline the assertions
 * below are deltas against; the saga reaches the ledger through {@link SpendGate}, which
 * {@link ConversationSaga} shares, so the two paid-call sites cannot drift apart on what "over the
 * cap" means.
 */
@QuarkusTest
class SpendCapGateTest {

    @Inject
    ReviewProjection projection;

    @Inject
    CapPolicy capPolicy;

    @Inject
    SpendWindow spendWindow;

    @Inject
    SpendGate spendGate;

    @Inject
    AppSettingRepository settings;

    private final List<ActionCommand> emitted = new ArrayList<>();

    @AfterEach
    void clearEveryLimit() {
        settings.set(CapPolicy.KEY_SPEND_CAP, "");
        settings.set(CapPolicy.KEY_CALLS, "");
    }

    /**
     * The test that fails if anyone later "simplifies" the cap to a single money figure. ADR-023
     * established that a money-denominated cap is inert by design on an UNMETERED deployment — every
     * charge there is a legitimate zero, so {@code SUM(cost_millicents)} never approaches any positive
     * limit. The spend cap is set comfortably above whatever this shared database has already spent
     * (baseline + $5.00), so the three zero-cost calls seeded below can trip only the call axis.
     */
    @Test
    void theCallCapFiresOnAnUnmeteredDeploymentWhereTheMoneyCapCannot() {
        SpendWindow.Usage baseline = usage();
        settings.set(CapPolicy.KEY_SPEND_CAP, String.valueOf(baseline.spentMillicents() + 500_000L));
        settings.set(CapPolicy.KEY_CALLS, String.valueOf(baseline.calls() + 2));
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, pr);
        seedUnmeteredCalls(pr, 3);

        ResultSaga saga = sagaFor(pr);
        saga.on(contextAssembled(pr));

        assertTrue(emitted.isEmpty(),
                "the most expensive of the three gates must REFUSE, not merely annotate: a regression "
                        + "that writes the status, posts the note and spends anyway passed this suite");
        ReviewDetail detail = projection.loadDetail(WS, REPO, pr).orElseThrow();
        assertEquals("refused", detail.status());
        assertTrue(detail.note().contains("call cap"),
                "the money cap cannot fire at zero cost — the call axis is the whole point: "
                        + detail.note());
    }

    /**
     * An unset cap must be a complete no-op, or every existing deployment changes behaviour on
     * upgrade — the mistake V30 made by leaving legacy models rateless. No limit is configured; the
     * call count is deliberately huge, so this fails outright if anyone later gives a cap a default.
     */
    @Test
    void anUnsetCapNeverRefuses() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, pr);
        seedUnmeteredCalls(pr, 1_000);

        ResultSaga saga = sagaFor(pr);
        saga.on(contextAssembled(pr));

        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.GenerateReview.class, emitted.get(0),
                "an unset cap must be a no-op, or every existing deployment changes behaviour on upgrade");
    }

    private SpendWindow.Usage usage() {
        return spendWindow.since(Instant.now().minus(capPolicy.window())).orElseThrow(
                () -> new AssertionError("the baseline every assertion here is a delta against could "
                        + "not be measured — the ledger read failed"));
    }

    /**
     * UNMETERED calls, written through the production writer (not raw SQL) so the fixture cannot drift
     * into a row shape {@link dev.codespire.orchestrator.llm.LlmModelPricer} would never produce.
     * {@code cost} is an asserted zero, so only the call axis can register them — that asymmetry is the
     * whole point of this test.
     */
    private void seedUnmeteredCalls(long pr, int count) {
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        for (int i = 0; i < count; i++) {
            projection.recordCharges(new ChargeCall(reviewId, "CANARY-UNMETERED-" + pr + "-" + i,
                    ChargeKind.REVIEW, "TEST-MODEL",
                    List.of(ChargeLine.unmetered(TokenType.INPUT, 1_000))));
        }
    }

    private static ContextAssembled contextAssembled(long pr) {
        return new ContextAssembled(ReviewFixtures.reviewIdFor(pr), pr, commitFor(pr), "context-ref",
                Set.of(), Set.of());
    }

    private static String commitFor(long pr) {
        return "TESTSHA" + pr;
    }

    /** Wired the same way as {@code ResultSagaPricingTest#sagaFor} — hand-built fakes for the LLM
     *  credential and the paid-command sink, the real capPolicy/spendWindow/projection beans injected
     *  above for the gate actually under test. */
    private ResultSaga sagaFor(long pr) {
        ResultSaga saga = new ResultSaga();
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                return List.of();
            }

            @Override
            public ReviewState currentState(String reviewId) {
                return new ReviewState(reviewId, ReviewFixtures.REPO_REF, pr, ReviewState.Status.REVIEWING,
                        commitFor(pr), Set.of(), null, Map.of());
            }
        };
        saga.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        saga.projection = projection;
        saga.workerLlmCredentials = new WorkerLlmCredentials() {
            @Override
            public DefaultLlm resolveDefault(String workspace) {
                return DefaultLlm.spendable("packed-llm-cred", "TEST-MODEL");
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("packed-scm-cred");
            }
        };
        saga.promptTemplates = new WorkerPromptTemplates() {
            @Override
            public dev.codespire.contract.llm.PromptTemplate forKind(dev.codespire.contract.llm.PromptKind kind) {
                return null;
            }
        };
        saga.capPolicy = capPolicy;
        saga.spendGate = spendGate;
        return saga;
    }
}

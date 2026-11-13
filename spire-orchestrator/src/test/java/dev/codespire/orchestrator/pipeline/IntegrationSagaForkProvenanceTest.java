package dev.codespire.orchestrator.pipeline;

import dev.codespire.orchestrator.TestRepositoryProjection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fork provenance a manual registration records (harness reviews, step 8). The saga is wired by
 * {@link IntegrationSagaPolicyTest}'s fakes, which record each write of the provenance column.
 */
class IntegrationSagaForkProvenanceTest {

    private final IntegrationSagaPolicyTest wiring = new IntegrationSagaPolicyTest();

    private IntegrationSaga saga() {
        return wiring.sagaWith(IntegrationSagaPolicyTest.policyMode(false),
                IntegrationSagaPolicyTest.provider(List.of()));
    }

    private static void register(IntegrationSaga saga, Boolean fromFork) {
        saga.onRepository(IntegrationSagaPolicyTest.pr("acc-1", "alice").withFromFork(fromFork),
                TestRepositoryProjection.REPOSITORY_ID);
    }

    /**
     * A manual registration cannot tell a fork from a branch pull request, so it asserts nothing: a webhook's recorded
     * answer survives it (step 8: a false here would turn a fork into a trusted pull request).
     */
    @Test
    void aManualRegistrationAfterAForkWebhookStaysUntrusted() {
        IntegrationSaga saga = saga();
        register(saga, true);
        wiring.fromForkCalls.clear();

        register(saga, null);

        assertTrue(wiring.fromForkCalls.isEmpty(),
                "unknown never overwrites the recorded answer: " + wiring.fromForkCalls);
    }

    /** Nothing is written for a new pull request either, so its column stays NULL, which every reader treats as untrusted. */
    @Test
    void aManualRegistrationOfANewPullRequestIsUntrusted() {
        register(saga(), null);

        assertTrue(wiring.fromForkCalls.isEmpty(), "a NULL provenance is left NULL: " + wiring.fromForkCalls);
    }

    /** A webhook's known false still records, so a branch pull request is trusted. */
    @Test
    void aWebhooksKnownFalseStillMakesItTrusted() {
        register(saga(), false);

        assertTrue(wiring.fromForkCalls.contains("review::acme/web#412:false"), wiring.fromForkCalls.toString());
    }
}

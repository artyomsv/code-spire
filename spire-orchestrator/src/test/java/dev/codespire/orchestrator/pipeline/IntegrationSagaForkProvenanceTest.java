package dev.codespire.orchestrator.pipeline;

import dev.codespire.orchestrator.TestRepositoryProjection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fork provenance a manual registration records (harness reviews, step 8), over {@link IntegrationSagaFixture}'s
 * fakes, which record each write of the provenance column.
 */
class IntegrationSagaForkProvenanceTest extends IntegrationSagaFixture {

    private IntegrationSaga saga() {
        return sagaWith(policyMode(false), provider(List.of()));
    }

    private static void register(IntegrationSaga saga, Boolean fromFork) {
        saga.onRepository(pr("acc-1", "alice").withFromFork(fromFork), TestRepositoryProjection.REPOSITORY_ID);
    }

    /**
     * A manual registration cannot tell a fork from a branch pull request, so it asserts nothing: a webhook's recorded
     * answer survives it (step 8: a false here would turn a fork into a trusted pull request).
     */
    @Test
    void aManualRegistrationAfterAForkWebhookStaysUntrusted() {
        IntegrationSaga saga = saga();
        register(saga, true);
        fromForkCalls.clear();

        register(saga, null);

        assertTrue(fromForkCalls.isEmpty(), "unknown never overwrites the recorded answer: " + fromForkCalls);
    }

    /** Nothing is written for a new pull request either, so its column stays NULL, which every reader treats as untrusted. */
    @Test
    void aManualRegistrationOfANewPullRequestIsUntrusted() {
        register(saga(), null);

        assertTrue(fromForkCalls.isEmpty(), "a NULL provenance is left NULL: " + fromForkCalls);
    }

    /** A webhook's known false still records, so a branch pull request is trusted. */
    @Test
    void aWebhooksKnownFalseStillMakesItTrusted() {
        register(saga(), false);

        assertTrue(fromForkCalls.contains("review::acme/web#412:false"), fromForkCalls.toString());
    }
}

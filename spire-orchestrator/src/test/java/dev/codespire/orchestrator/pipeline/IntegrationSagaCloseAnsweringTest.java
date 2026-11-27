package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.CloseReason;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.TestRepositoryProjection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The safety net under the "responding" mark (production PR #456): a pull request that is merged or closed clears
 * "answering", since no review run follows a close to reset it. Over {@link IntegrationSagaFixture}'s fakes, which
 * record each "answering" write.
 */
class IntegrationSagaCloseAnsweringTest extends IntegrationSagaFixture {

    @Test
    void aMergeClearsAnswering() {
        close(CloseReason.MERGED);

        assertEquals(List.of("review::acme/web#412:false"), answeringCalls);
    }

    @Test
    void aDeclineClearsAnswering() {
        close(CloseReason.DECLINED);

        assertEquals(List.of("review::acme/web#412:false"), answeringCalls);
    }

    private void close(CloseReason reason) {
        sagaWith(policyMode(false), provider(List.of())).onRepository(
                new PullRequestClosed(new RepoRef("acme", "web"), 412L, reason), TestRepositoryProjection.REPOSITORY_ID);
    }
}

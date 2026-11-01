package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.TestRepositoryProjection;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.review.run.ReviewRoundLock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A forced re-run appends its round and replaces the review's run token under the review's round lock (review of PR
 * #368, code-quality/H-1). A harness outcome reads its round and takes its effects under the same lock, so round 1's
 * answer is never applied with round 2's number and token. Hand-written fakes, as {@code ArchivedReviewGateTest} has;
 * every method the re-run reaches is overridden, because an un-overridden one opens a real database (CLAUDE.md).
 */
class ReviewRerunRoundLockTest {

    private static final String REVIEW_ID = ReviewIds.reviewId(new RepoRef("TEST-WS", "TEST-REPO"), 7L);

    private final List<String> steps = new ArrayList<>();

    private boolean starts = true;

    @Test
    void theRoundAndItsRunTokenMoveUnderTheRoundLock() {
        assertTrue(service().rerun("TEST-WS", "TEST-REPO", 7L));

        assertEquals(List.of("lock " + REVIEW_ID, "request", "run token", "unlock", "fetch"), steps);
    }

    /** The control: a declined request writes no run token and sends nothing, and still releases the lock. */
    @Test
    void aDeclinedRerunReplacesNoRunToken() {
        starts = false;

        assertFalse(service().rerun("TEST-WS", "TEST-REPO", 7L));

        assertEquals(List.of("lock " + REVIEW_ID, "request", "unlock"), steps);
    }

    private ReviewRerunService service() {
        ReviewRerunService service = new ReviewRerunService();
        service.projection = projection();
        service.lifecycle = lifecycle();
        service.rounds = roundLock();
        service.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                steps.add("fetch");
            }
        };
        service.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("TEST-packed-scm");
            }
        };
        return service;
    }

    private ReviewProjection projection() {
        return new TestRepositoryProjection() {
            @Override
            public boolean archived(String reviewId) {
                return false;
            }

            @Override
            public Optional<String> commitOf(String reviewId) {
                return Optional.of("TESTSHA1");
            }

            @Override
            public void clearWorkerIdempotency(String reviewId) {
            }

            @Override
            public void markRerunStarted(String reviewId) {
                steps.add("run token");
            }
        };
    }

    private ReviewLifecycleService lifecycle() {
        return new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                steps.add("request");
                return starts ? List.of(new DomainEvent.ReviewRequested("TESTSHA1", "rerun")) : List.of();
            }
        };
    }

    private ReviewRoundLock roundLock() {
        return new ReviewRoundLock() {
            @Override
            public <T> T holding(String reviewId, Supplier<T> work) {
                steps.add("lock " + reviewId);
                T done = work.get();
                steps.add("unlock");
                return done;
            }
        };
    }
}

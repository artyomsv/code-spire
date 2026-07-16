package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.AnswerFollowUp;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommandDispatcher routing unit tests: each ActionCommand type reaches its
 * worker, a null (poison) record is a safe no-op, and the reviewId MDC is set
 * for the duration of the dispatch and always cleared afterwards — even when
 * the worker throws (the exception must still propagate for the nack/DLQ path).
 */
class CommandDispatcherTest {

    private static final String REVIEW_ID = "review::sandbox/demo-repo#7";
    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");

    private CommandDispatcher dispatcher;
    private List<String> calls;
    private List<Object> mdcSeenByWorker;
    private RuntimeException workerFailure;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        mdcSeenByWorker = new ArrayList<>();
        workerFailure = null;
        MDC.remove("reviewId");

        dispatcher = new CommandDispatcher();
        dispatcher.diffWorker = new DiffWorker() {
            @Override
            public void fetchDiff(FetchDiff command) {
                record("fetchDiff");
            }
        };
        dispatcher.contextWorker = new ContextWorker() {
            @Override
            public void gatherContext(GatherContext command) {
                record("gatherContext");
            }
        };
        dispatcher.reviewWorker = new ReviewWorker() {
            @Override
            public void generateReview(GenerateReview command) {
                record("generateReview");
            }

            @Override
            public void postComments(PostComments command) {
                record("postComments");
            }
        };
    }

    private void record(String call) {
        calls.add(call);
        mdcSeenByWorker.add(MDC.get("reviewId"));
        if (workerFailure != null) {
            throw workerFailure;
        }
    }

    @Test
    void fetchDiffRoutesToTheDiffWorker() {
        dispatcher.on(new FetchDiff(REVIEW_ID, REPO, 7, "abc123", null));
        assertEquals(List.of("fetchDiff"), calls);
    }

    @Test
    void gatherContextRoutesToTheContextWorker() {
        dispatcher.on(new GatherContext(REVIEW_ID, REPO, 7, "abc123", Set.of(), List.of(), null));
        assertEquals(List.of("gatherContext"), calls);
    }

    @Test
    void generateReviewRoutesToTheReviewWorker() {
        dispatcher.on(new GenerateReview(REVIEW_ID, REPO, 7, "abc123", null, 1, null, null, null));
        assertEquals(List.of("generateReview"), calls);
    }

    @Test
    void postCommentsRoutesToTheReviewWorker() {
        dispatcher.on(new PostComments(REVIEW_ID, REPO, 7, "abc123", null, null));
        assertEquals(List.of("postComments"), calls);
    }

    @Test
    void answerFollowUpHasNoWorkerYetAndIsIgnoredSafely() {
        dispatcher.on(new AnswerFollowUp(REVIEW_ID, REPO, 7,
                new ThreadRef("comment-1"), "comment-1", "why?", null, null));
        assertTrue(calls.isEmpty(), "no worker handles AnswerFollowUp in P1 — must not throw");
    }

    @Test
    void nullPoisonRecordIsANoOp() {
        dispatcher.on(null); // never-throw deserializer hands null for poison records
        assertTrue(calls.isEmpty());
        assertNull(MDC.get("reviewId"), "no MDC leak from the guard path");
    }

    @Test
    void mdcCarriesTheReviewIdDuringDispatch() {
        dispatcher.on(new FetchDiff(REVIEW_ID, REPO, 7, "abc123", null));
        assertEquals(List.of((Object) REVIEW_ID), mdcSeenByWorker,
                "the worker must observe the command's reviewId in the MDC");
    }

    @Test
    void mdcIsClearedAfterDispatch() {
        dispatcher.on(new FetchDiff(REVIEW_ID, REPO, 7, "abc123", null));
        assertNull(MDC.get("reviewId"), "MDC must not bleed into the next record on this thread");
    }

    @Test
    void mdcIsClearedEvenWhenTheWorkerThrows() {
        workerFailure = new IllegalStateException("worker blew up");
        assertThrows(IllegalStateException.class,
                () -> dispatcher.on(new GenerateReview(REVIEW_ID, REPO, 7, "abc123", null, 1, null, null, null)),
                "the failure must propagate so the record is nacked to cs.dlq");
        assertNull(MDC.get("reviewId"));
    }
}

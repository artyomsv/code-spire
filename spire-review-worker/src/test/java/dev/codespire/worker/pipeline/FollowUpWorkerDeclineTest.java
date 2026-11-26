package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.AnswerFollowUp;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined;
import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined.Reason;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.worker.adapters.WorkerLlmProvider;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Every quiet exit of the API reply path reports a {@code FollowUpDeclined} (production PR #456): the orchestrator set
 * "answering" when it sent the ask, and before this nothing cleared it when the worker chose to stay silent, so the
 * "responding" mark stayed up for good. No model is reached on any of them: the LLM port fails the test if asked.
 */
class FollowUpWorkerDeclineTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = "review::TEST-WS/TEST-REPO#7";
    private static final ThreadRef THREAD = new ThreadRef("TEST-thread-1");
    private static final String COMMENT = "TEST-comment-5";

    private final List<IntegrationEvent> emitted = new ArrayList<>();

    @Test
    void aProviderWithoutThreadsReportsTheDecline() {
        worker(mock(CommentSink.class), new NoClaims(false)).answer(ask(true));

        assertEquals(List.of(declined(Reason.NO_THREAD_SOURCE)), emitted);
    }

    @Test
    void aThirdPersonWithoutAMentionReportsTheDecline() {
        CommentSink sink = threadOf(new ThreadMessage("TEST-alice", "TEST-ONE", false),
                new ThreadMessage("TEST-bob", "TEST-TWO", false));

        worker(sink, new NoClaims(false)).answer(ask(false));

        assertEquals(List.of(declined(Reason.MULTI_PARTY_NO_MENTION)), emitted);
    }

    @Test
    void anAnsweredCommentReportsTheDecline() {
        CommentSink sink = threadOf(new ThreadMessage("TEST-alice", "TEST-QUESTION", false));

        worker(sink, new NoClaims(true)).answer(ask(true));

        assertEquals(List.of(declined(Reason.ALREADY_ANSWERED)), emitted);
    }

    private static FollowUpDeclined declined(Reason reason) {
        return new FollowUpDeclined(REVIEW_ID, THREAD, COMMENT, reason);
    }

    private static AnswerFollowUp ask(boolean mentioned) {
        return new AnswerFollowUp(REVIEW_ID, REPO, 7, THREAD, COMMENT, "TEST-QUESTION", null, null, mentioned, 1, 0,
                1.0);
    }

    private static CommentSink threadOf(ThreadMessage... messages) {
        CommentSink sink = mock(CommentSink.class, withSettings().extraInterfaces(ThreadSource.class));
        when(((ThreadSource) sink).fetchThread(REPO, 7, THREAD))
                .thenReturn(new ThreadTranscript(THREAD, "src/A.java", 3, "b".repeat(40), List.of(messages)));
        return sink;
    }

    private FollowUpWorker worker(CommentSink sink, CommentIdempotencyStore claims) {
        FollowUpWorker worker = new FollowUpWorker();
        worker.promptLog = new PromptLog();
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(mock(DiffSource.class), sink);
            }
        };
        worker.llm = new WorkerLlmProvider() {
            @Override
            public LlmClient forCommand(AnswerFollowUp command) {
                throw new AssertionError("a declined reply must not reach the model");
            }
        };
        worker.idempotency = claims;
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        return worker;
    }

    /** The one claim method a decline reaches; {@code answered} says whether the comment was answered already. */
    private static final class NoClaims extends CommentIdempotencyStore {
        private final boolean answered;

        NoClaims(boolean answered) {
            this.answered = answered;
        }

        @Override
        public Claim claim(String reviewId, String commit, String anchorKey) {
            return answered ? new Claim.AlreadyPosted("TEST-posted-1") : new Claim.Post();
        }

        @Override
        public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
            throw new AssertionError("a declined reply posts nothing");
        }
    }
}

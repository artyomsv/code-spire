package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.AnswerFollowUp;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.worker.adapters.WorkerLlmProvider;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The API reply path end to end over fakes: the answer the worker posts is reported as a {@code FollowUpPosted} that
 * counts a turn (review of PR #371, code-quality/M-1). An event without that flag is one recorded before the turn
 * receipts existed, which the orchestrator files and never counts, so a worker that stopped setting it would make
 * every new answer free of the turn cap. The claim store and the results emitter override every method the path
 * reaches, so no database or broker is opened (CLAUDE.md, saga test fakes).
 */
class FollowUpWorkerAnswerTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = "review::TEST-WS/TEST-REPO#7";
    private static final ThreadRef THREAD = new ThreadRef("TEST-thread-1");
    private static final String COMMIT = "b".repeat(40);

    private final List<IntegrationEvent> emitted = new ArrayList<>();

    @Test
    void aPostedAnswerIsReportedAsATurn() {
        CommentSink sink = mock(CommentSink.class, withSettings().extraInterfaces(ThreadSource.class));
        DiffSource diffs = mock(DiffSource.class);
        LlmProvider llm = mock(LlmProvider.class);
        when(((ThreadSource) sink).fetchThread(REPO, 7, THREAD)).thenReturn(new ThreadTranscript(THREAD, "src/A.java",
                3, COMMIT, List.of(new ThreadMessage("TEST-alice", "TEST-QUESTION", false))));
        when(diffs.fetchDiff(REPO, 7, COMMIT)).thenReturn(new Diff(COMMIT, List.of(), false));
        when(llm.complete(any(), any())).thenReturn(CompletableFuture.completedFuture(
                new Completion("TEST-ANSWER", ModelUsage.of("TEST-model", 1, 1))));
        when(sink.replyInThread(eq(REPO), eq(7L), eq(THREAD), anyString()))
                .thenReturn(new CommentRef("TEST-posted-3", THREAD, CommentKind.REPLY));

        worker(sink, diffs, llm).answer(new AnswerFollowUp(REVIEW_ID, REPO, 7, THREAD, "TEST-comment-5",
                "TEST-QUESTION", null, null, true, 1, 0, 1.0));

        assertEquals(2, emitted.size(), "FollowUpGenerated, then FollowUpPosted: " + emitted);
        assertEquals(new IntegrationEvent.FollowUpPosted(REVIEW_ID, THREAD, "TEST-posted-3", true), emitted.get(1));
    }

    private FollowUpWorker worker(CommentSink sink, DiffSource diffs, LlmProvider llm) {
        FollowUpWorker worker = new FollowUpWorker();
        worker.promptLog = new PromptLog();
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(diffs, sink);
            }
        };
        worker.llm = new WorkerLlmProvider() {
            @Override
            public LlmClient forCommand(AnswerFollowUp command) {
                return new LlmClient(llm, new ModelParams("TEST-model", 0.2, null));
            }
        };
        worker.idempotency = new InMemoryClaims();
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        return worker;
    }

    /** Claim, then post: the two methods the answer path reaches, with no database behind them. */
    private static final class InMemoryClaims extends CommentIdempotencyStore {
        private final Map<String, String> posted = new HashMap<>();

        @Override
        public Claim claim(String reviewId, String commit, String anchorKey) {
            String existing = posted.get(commit + "/" + anchorKey);
            return existing != null ? new Claim.AlreadyPosted(existing) : new Claim.Post();
        }

        @Override
        public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
            posted.put(commit + "/" + anchorKey, postedRef);
        }
    }
}

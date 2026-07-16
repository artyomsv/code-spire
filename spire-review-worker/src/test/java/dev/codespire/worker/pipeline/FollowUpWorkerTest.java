package dev.codespire.worker.pipeline;

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
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FollowUpWorkerTest {

    @Test
    void fetchesThreadAndDiffThenRepliesInThread() {
        ThreadSource threads = mock(ThreadSource.class);
        DiffSource diffs = mock(DiffSource.class);
        CommentSink sink = mock(CommentSink.class);
        LlmProvider llm = mock(LlmProvider.class);
        ModelParams params = new ModelParams("m", 0.2, null);
        RepoRef repo = new RepoRef("artyomsv", "spire-test");
        ThreadRef thread = new ThreadRef("100");

        when(threads.fetchThread(repo, 5L, thread)).thenReturn(
                new ThreadTranscript(thread, "src/App.java", 42, "abc123",
                        List.of(new ThreadMessage("octocat", "why?", false))));
        when(diffs.fetchDiff(eq(repo), eq(5L), eq("abc123")))
                .thenReturn(new Diff(DiffRefs.headOnly("abc123"), List.of(), false));
        when(llm.complete(any(), any())).thenReturn(CompletableFuture.completedFuture(
                new Completion("Because the caller can pass null.", new ModelUsage("m", 1, 1, 0))));
        when(sink.replyInThread(eq(repo), eq(5L), eq(thread), anyString()))
                .thenReturn(new CommentRef("900", thread, CommentKind.REPLY));

        FollowUpWorker.FollowUpResult r = FollowUpWorker.answer(
                repo, 5L, thread, threads, diffs, llm, params, sink);

        verify(threads).fetchThread(repo, 5L, thread);
        verify(diffs).fetchDiff(repo, 5L, "abc123");                       // diff fetched by the thread's anchor commit
        verify(sink).replyInThread(eq(repo), eq(5L), eq(thread), contains("caller can pass null"));
        assertEquals("900", r.postedCommentId());
        assertEquals("Because the caller can pass null.", r.answerText());
    }
}

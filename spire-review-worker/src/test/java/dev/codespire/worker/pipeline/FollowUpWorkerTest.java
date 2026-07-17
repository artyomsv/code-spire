package dev.codespire.worker.pipeline;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.scm.github.GitHubApiException;
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
    void fetchesDiffThenRepliesInThread() {
        DiffSource diffs = mock(DiffSource.class);
        CommentSink sink = mock(CommentSink.class);
        LlmProvider llm = mock(LlmProvider.class);
        ModelParams params = new ModelParams("m", 0.2, null);
        RepoRef repo = new RepoRef("artyomsv", "spire-test");
        ThreadRef thread = new ThreadRef("100");
        ThreadTranscript transcript = new ThreadTranscript(thread, "src/App.java", 42, "abc123",
                List.of(new ThreadMessage("octocat", "why?", false)));

        when(diffs.fetchDiff(eq(repo), eq(5L), eq("abc123")))
                .thenReturn(new Diff(DiffRefs.headOnly("abc123"), List.of(), false));
        when(llm.complete(any(), any())).thenReturn(CompletableFuture.completedFuture(
                new Completion("Because the caller can pass null.", new ModelUsage("m", 1, 1, 0))));
        when(sink.replyInThread(eq(repo), eq(5L), eq(thread), anyString()))
                .thenReturn(new CommentRef("900", thread, CommentKind.REPLY));

        FollowUpWorker.FollowUpResult r = FollowUpWorker.answer(
                repo, 5L, thread, transcript, diffs, llm, params, sink);

        verify(diffs).fetchDiff(repo, 5L, "abc123");                       // diff fetched by the thread's anchor commit
        verify(sink).replyInThread(eq(repo), eq(5L), eq(thread), contains("caller can pass null"));
        assertEquals("900", r.postedCommentId());
        assertEquals("Because the caller can pass null.", r.answerText());
        assertNotNull(r.usage(), "the follow-up LLM call's usage is captured for the cost breakdown");
        assertEquals("m", r.usage().model());
    }

    // --- scope "smart 1:1" gate (shouldAnswer) ---

    @Test
    void answersOneOnOneThreadWithoutAMention() {
        ThreadTranscript t = thread(
                new ThreadMessage("code-spire-bot", "possible NPE", true),
                new ThreadMessage("octocat", "why is this a bug?", false),
                new ThreadMessage("octocat", "and this line?", false));         // same human twice = still 1:1
        assertTrue(FollowUpWorker.shouldAnswer(t, false));
    }

    @Test
    void staysQuietInAMultiHumanThreadWithoutAMention() {
        ThreadTranscript t = thread(
                new ThreadMessage("code-spire-bot", "possible NPE", true),
                new ThreadMessage("octocat", "why is this a bug?", false),
                new ThreadMessage("hubot", "I think octocat is right", false));  // a second human joined
        assertFalse(FollowUpWorker.shouldAnswer(t, false));
    }

    @Test
    void answersAMultiHumanThreadWhenMentioned() {
        ThreadTranscript t = thread(
                new ThreadMessage("code-spire-bot", "possible NPE", true),
                new ThreadMessage("octocat", "why?", false),
                new ThreadMessage("hubot", "@code-spire-bot what do you think?", false));
        assertTrue(FollowUpWorker.shouldAnswer(t, true));
    }

    private static ThreadTranscript thread(ThreadMessage... messages) {
        return new ThreadTranscript(new ThreadRef("100"), "src/App.java", 42, "abc123", List.of(messages));
    }

    // --- transient-failure classification (retry vs dead-letter) ---

    @Test
    void classifiesTransientVersusTerminalFailures() {
        assertTrue(FollowUpWorker.isTransient(new GitHubApiException(503, "GET", "/user", "Unicorn")));
        assertTrue(FollowUpWorker.isTransient(new GitHubApiException(429, "GET", "/x", "rate limited")));
        assertTrue(FollowUpWorker.isTransient(new java.io.IOException("connection reset")));
        assertFalse(FollowUpWorker.isTransient(new GitHubApiException(404, "GET", "/x", "not found")));
        assertFalse(FollowUpWorker.isTransient(new IllegalStateException("boom")));
    }
}

package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.scm.github.GitHubApiException;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.diff.UnifiedDiffParser;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
                .thenReturn(new Diff("abc123", List.of(), false));
        when(llm.complete(any(), any())).thenReturn(CompletableFuture.completedFuture(
                new Completion("Because the caller can pass null.", new ModelUsage("m", 1, 1, 0))));
        when(sink.replyInThread(eq(repo), eq(5L), eq(thread), anyString()))
                .thenReturn(new CommentRef("900", thread, CommentKind.REPLY));

        FollowUpWorker.FollowUpResult r = FollowUpWorker.answer(
                repo, 5L, thread, transcript, diffs, llm, params, sink, null, List.of());

        verify(diffs).fetchDiff(repo, 5L, "abc123");                       // diff fetched by the thread's anchor commit
        verify(sink).replyInThread(eq(repo), eq(5L), eq(thread), contains("caller can pass null"));
        assertEquals("900", r.postedCommentId());
        assertEquals("Because the caller can pass null.", r.answerText());
        assertNotNull(r.usage(), "the follow-up LLM call's usage is captured for the cost breakdown");
        assertEquals("m", r.usage().model());
    }

    @Test
    void nullCommitTranscriptResolvesThePrHeadFirst() {
        // A summary-thread transcript (issueThreadTranscript's fallback) carries no anchor commit --
        // the PR head must be resolved before the diff can be fetched at all.
        DiffSource diffs = mock(DiffSource.class);
        CommentSink sink = mock(CommentSink.class);
        LlmProvider llm = mock(LlmProvider.class);
        ModelParams params = new ModelParams("m", 0.2, null);
        RepoRef repo = new RepoRef("artyomsv", "spire-test");
        ThreadRef thread = new ThreadRef("sum-1");
        ThreadTranscript transcript = new ThreadTranscript(thread, null, 0, null,
                List.of(new ThreadMessage("octocat", "why?", false)));

        when(diffs.fetchPullRequest(eq(repo), eq(5L))).thenReturn(new PullRequest(
                repo, 5L, "T", "d", "feature", "main", "head-9",
                Author.of("1", "octocat", "Octo Cat"), "https://x"));
        when(diffs.fetchDiff(eq(repo), eq(5L), eq("head-9")))
                .thenReturn(new Diff("head-9", List.of(), false));
        when(llm.complete(any(), any())).thenReturn(CompletableFuture.completedFuture(
                new Completion("Because the caller can pass null.", new ModelUsage("m", 1, 1, 0))));
        when(sink.replyInThread(eq(repo), eq(5L), eq(thread), anyString()))
                .thenReturn(new CommentRef("900", thread, CommentKind.REPLY));

        FollowUpWorker.FollowUpResult r = FollowUpWorker.answer(
                repo, 5L, thread, transcript, diffs, llm, params, sink, null, List.of());

        verify(diffs).fetchPullRequest(repo, 5L);            // null commit -> PR head resolved first
        verify(diffs).fetchDiff(repo, 5L, "head-9");          // diff fetched by the resolved head
        assertEquals("900", r.postedCommentId());
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

    // --- prompt scope: the thread's own file, and the findings that belong elsewhere ---

    private static Diff twoFileDiff() {
        return new Diff("abc123", UnifiedDiffParser.parse("""
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -a
                +appChanged
                diff --git a/src/Other.java b/src/Other.java
                --- a/src/Other.java
                +++ b/src/Other.java
                @@ -1 +1 @@
                -b
                +otherChanged
                """), false);
    }

    /**
     * An inline thread asks about one file, and the persona says to answer only about the anchored
     * code — but the prompt used to carry every file in the PR, inviting the survey answers the
     * persona forbids.
     */
    @Test
    void anAnchoredThreadSeesOnlyItsOwnFile() {
        List<FilePatch> files = FollowUpWorker.anchoredFiles(twoFileDiff(), "src/App.java");
        assertEquals(1, files.size());
        assertEquals("src/App.java", files.getFirst().newPath());
    }

    /** A summary thread genuinely is about the whole PR, so it keeps every file. */
    @Test
    void aThreadWithNoAnchorKeepsTheWholeDiff() {
        assertEquals(2, FollowUpWorker.anchoredFiles(twoFileDiff(), null).size());
        assertEquals(2, FollowUpWorker.anchoredFiles(twoFileDiff(), "  ").size());
    }

    /**
     * A finding's file may have been renamed since it was posted. Sending an empty diff would be
     * worse than sending too much, so an anchor that matches nothing falls back to everything.
     */
    @Test
    void anAnchorMatchingNothingFallsBackRatherThanSendingAnEmptyDiff() {
        assertEquals(2, FollowUpWorker.anchoredFiles(twoFileDiff(), "src/Renamed.java").size());
    }

    @Test
    void theReplyPromptNamesTheFindingsThatBelongToOtherThreads() {
        DiffSource diffs = mock(DiffSource.class);
        CommentSink sink = mock(CommentSink.class);
        LlmProvider llm = mock(LlmProvider.class);
        ModelParams params = new ModelParams("m", 0.2, null);
        RepoRef repo = new RepoRef("artyomsv", "spire-test");
        ThreadRef thread = new ThreadRef("100");
        ThreadTranscript transcript = new ThreadTranscript(thread, "src/App.java", 42, "abc123",
                List.of(new ThreadMessage("octocat", "why?", false)));
        when(diffs.fetchDiff(eq(repo), eq(5L), eq("abc123"))).thenReturn(twoFileDiff());
        when(llm.complete(any(), any())).thenReturn(CompletableFuture.completedFuture(
                new Completion("ok", new ModelUsage("m", 1, 1, 0))));
        when(sink.replyInThread(eq(repo), eq(5L), eq(thread), anyString()))
                .thenReturn(new CommentRef("900", thread, CommentKind.REPLY));

        FollowUpWorker.answer(repo, 5L, thread, transcript, diffs, llm, params, sink, null,
                List.of(new PriorFinding("src/App.java", 77, Severity.BLOCKER,
                        "unrelated defect on another line", "thread-9")));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(llm).complete(captor.capture(), eq(params));
        String user = captor.getValue().user();
        assertTrue(user.contains("src/App.java:77"), "the other thread's anchor must be named");
        assertTrue(user.contains("unrelated defect on another line"));
        // The anchored file only: the second file must not reach the prompt at all.
        assertFalse(user.contains("otherChanged"), "an anchored reply must not carry the PR's other files");
    }

    // --- turn-cap hand-off notice ---

    private static final RepoRef CAP_REPO = new RepoRef("artyomsv", "spire-test");
    private static final ThreadRef CAP_THREAD = new ThreadRef("finding-1");
    private static final ActionCommand.NotifyTurnCap CAP_COMMAND =
            new ActionCommand.NotifyTurnCap("review::artyomsv/spire-test#5", CAP_REPO, 5L, CAP_THREAD, 5, "cred");

    /** A worker whose idempotency slot answers with {@code claim}, collecting whatever it emits. */
    private static FollowUpWorker capWorker(CommentIdempotencyStore.Claim claim, CommentSink sink,
                                           List<IntegrationEvent> emitted, List<String> markedKeys) {
        FollowUpWorker worker = new FollowUpWorker();
        worker.promptLog = new PromptLog(); // disabled by default — the seam must not NPE
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(null, sink);
            }
        };
        worker.idempotency = new CommentIdempotencyStore() {
            @Override
            public Claim claim(String reviewId, String commit, String anchorKey) {
                return claim;
            }

            @Override
            public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
                markedKeys.add(commit + "/" + anchorKey);
            }
        };
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        return worker;
    }

    @Test
    void postsTheHandOffNoticeAndEmitsItsOwnEvent() {
        CommentSink sink = mock(CommentSink.class);
        List<IntegrationEvent> emitted = new java.util.ArrayList<>();
        List<String> marked = new java.util.ArrayList<>();
        when(sink.replyInThread(eq(CAP_REPO), eq(5L), eq(CAP_THREAD), anyString()))
                .thenReturn(new CommentRef("notice-1", CAP_THREAD, CommentKind.REPLY));

        capWorker(new CommentIdempotencyStore.Claim.Post(), sink, emitted, marked).notifyTurnCap(CAP_COMMAND);

        verify(sink).replyInThread(eq(CAP_REPO), eq(5L), eq(CAP_THREAD), contains("hand it back to the team"));
        // TurnCapNotified, NOT FollowUpPosted: that event bumps the turn count, so the notice about
        // running out of turns would consume one of them.
        IntegrationEvent.TurnCapNotified notified =
                assertInstanceOf(IntegrationEvent.TurnCapNotified.class, emitted.getFirst());
        assertEquals("notice-1", notified.commentId());
        assertEquals("finding-1", notified.threadRef().value());
        assertEquals(List.of("finding-1/capnotice"), marked,
                "the slot is the THREAD, so further replies find it taken");
    }

    /**
     * The bot must not repeat its hand-off. Every later reply on a capped thread re-reaches this
     * command, so without the claim the thread would collect an "I'm done" per comment — worse than
     * the silence this feature replaced.
     */
    @Test
    void aSecondReplyOnACappedThreadPostsNothingMore() {
        CommentSink sink = mock(CommentSink.class);
        List<IntegrationEvent> emitted = new java.util.ArrayList<>();

        capWorker(new CommentIdempotencyStore.Claim.AlreadyPosted("notice-1"), sink, emitted,
                new java.util.ArrayList<>()).notifyTurnCap(CAP_COMMAND);

        verify(sink, never()).replyInThread(any(), anyLong(), any(), anyString());
        assertTrue(emitted.isEmpty(), "nothing posted, nothing to report");
    }

    @Test
    void theNoticeNamesTheCapAndInvitesTheMentionThatOverridesIt() {
        String text = FollowUpWorker.capNoticeText(5);
        assertTrue(text.contains("5"), "says how many turns were spent, so the limit is legible");
        assertTrue(text.contains("@-mention"),
                "ConversationPolicy lets a mention override the cap — the notice must offer that way back");
    }
}

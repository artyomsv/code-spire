package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ReplyRetry;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.scm.github.GitHubApiException;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Harness reviews, step 7: the review worker prepares a reply on a harness and posts the run's answer. */
class HarnessReplyWorkerTest {

    private static final String REVIEW_ID = "review::TEST-work/app#7";
    private static final RepoRef REPO = new RepoRef("TEST-work", "app");
    private static final ThreadRef THREAD = new ThreadRef("TEST-thread-1");
    private static final String COMMENT = "TEST-comment-5";
    private static final String HEAD = "c".repeat(40);
    private static final String KEY = "followup:" + COMMENT;

    private final List<IntegrationEvent> emitted = new ArrayList<>();
    /** The claim slots by {@code thread/key}: the posted ref, or null while only claimed. */
    private final Map<String, String> slots = new HashMap<>();
    private CommentSink sink;
    private DiffSource diffs;
    private HarnessReplyWorker worker;

    @BeforeEach
    void setUp() {
        sink = mock(CommentSink.class, withSettings().extraInterfaces(ThreadSource.class));
        diffs = mock(DiffSource.class);
        when(diffs.fetchPullRequest(REPO, 7)).thenReturn(new PullRequest(REPO, 7, "TEST-TITLE", "TEST-DESCRIPTION",
                "TEST-source", "main", HEAD, Author.of("TEST-id", "TEST-user", "TEST-user"),
                "https://forge.example.test/TEST-work/app/pull/7"));
        worker = workerWith(sink);
    }

    private HarnessReplyWorker workerWith(CommentSink comments) {
        HarnessReplyWorker built = new HarnessReplyWorker();
        built.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(diffs, comments);
            }
        };
        // Overrides both methods the reply path reaches, so no real database is opened (CLAUDE.md gotcha).
        built.idempotency = new CommentIdempotencyStore() {
            @Override
            public Claim claim(String reviewId, String commit, String anchorKey) {
                String posted = slots.get(commit + "/" + anchorKey);
                slots.putIfAbsent(commit + "/" + anchorKey, null);
                return posted == null ? new Claim.Post() : new Claim.AlreadyPosted(posted);
            }

            @Override
            public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
                slots.put(commit + "/" + anchorKey, postedRef);
            }
        };
        built.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        return built;
    }

    private static ThreadTranscript thread(ThreadMessage... messages) {
        return new ThreadTranscript(THREAD, "src/A.java", 3, "b".repeat(40), List.of(messages));
    }

    private void threadIs(ThreadTranscript transcript) {
        when(((ThreadSource) sink).fetchThread(REPO, 7, THREAD)).thenReturn(transcript);
    }

    private static ActionCommand.PrepareHarnessReply prepare(boolean mentioned, int maxAttempts) {
        return new ActionCommand.PrepareHarnessReply(REVIEW_ID, REPO, 7, THREAD, COMMENT, null, mentioned, 2,
                List.of(new PriorFinding("src/B.java", 9, Severity.MINOR, "TEST-OTHER-FINDING", "TEST-thread-2")),
                new ReplyRetry(maxAttempts, 0, 1.0));
    }

    private static ActionCommand.PostHarnessReply post(String answer) {
        return new ActionCommand.PostHarnessReply(REVIEW_ID, REPO, 7, THREAD, COMMENT, answer, null,
                new ReplyRetry(1, 0, 1.0));
    }

    @Test
    void aReplyIsPreparedWithItsThread() {
        threadIs(thread(new ThreadMessage("TEST-bot", "TEST-BOT-SAID", true),
                new ThreadMessage("TEST-human", "TEST-HUMAN-ASKED", false)));

        worker.prepare(prepare(false, 1));

        IntegrationEvent.HarnessReplyPrepared prepared = assertInstanceOf(IntegrationEvent.HarnessReplyPrepared.class,
                emitted.getFirst());
        assertEquals(1, emitted.size());
        assertEquals(HEAD, prepared.commit(), "the run checks out the pull request's head");
        assertEquals(2, prepared.round());
        assertEquals(THREAD, prepared.threadRef());
        assertEquals(COMMENT, prepared.triggeringCommentId());
        String file = prepared.inputs().asMap().get(HarnessReviewInputs.THREAD);
        assertTrue(file.contains("[reviewer] TEST-human: TEST-HUMAN-ASKED"), file);
        assertTrue(file.contains("TEST-OTHER-FINDING"), file);
        assertTrue(slots.containsKey(THREAD.value() + "/" + KEY), "the API path's own key is claimed");
    }

    @Test
    void aMultiPartyThreadWithNoMentionIsLeftAlone() {
        threadIs(thread(new ThreadMessage("TEST-bot", "TEST-BOT-SAID", true),
                new ThreadMessage("TEST-human-1", "TEST-ONE", false),
                new ThreadMessage("TEST-human-2", "TEST-TWO", false)));

        worker.prepare(prepare(false, 1));
        assertEquals(List.of(), emitted);

        worker.prepare(prepare(true, 1));
        assertInstanceOf(IntegrationEvent.HarnessReplyPrepared.class, emitted.getFirst(), "a mention engages");
    }

    @Test
    void anAnsweredCommentIsNotPreparedAgain() {
        threadIs(thread(new ThreadMessage("TEST-human", "TEST-HUMAN-ASKED", false)));
        slots.put(THREAD.value() + "/" + KEY, "TEST-posted-9");

        worker.prepare(prepare(false, 1));

        assertEquals(List.of(), emitted);
        verify(diffs, never()).fetchPullRequest(any(), anyLong());
    }

    @Test
    void aProviderWithoutThreadsPreparesNothing() {
        HarnessReplyWorker noThreads = workerWith(mock(CommentSink.class));

        noThreads.prepare(prepare(true, 1));

        assertEquals(List.of(), emitted);
    }

    @Test
    void aTransientThreadReadIsRetriedThenDeadLettered() {
        when(((ThreadSource) sink).fetchThread(REPO, 7, THREAD))
                .thenThrow(new GitHubApiException(503, "GET", "/x", "TEST-unavailable"));

        assertThrows(GitHubApiException.class, () -> worker.prepare(prepare(false, 2)));

        verify((ThreadSource) sink, org.mockito.Mockito.times(2)).fetchThread(REPO, 7, THREAD);
        assertEquals(List.of(), emitted);
    }

    @Test
    void anAnswerIsPostedInItsThread() {
        when(sink.replyInThread(eq(REPO), eq(7L), eq(THREAD), anyString()))
                .thenReturn(new CommentRef("TEST-posted-9", THREAD, CommentKind.REPLY));

        worker.post(post("  TEST-ANSWER  \n"));

        verify(sink).replyInThread(REPO, 7, THREAD, "TEST-ANSWER");
        IntegrationEvent.FollowUpGenerated generated = assertInstanceOf(IntegrationEvent.FollowUpGenerated.class,
                emitted.get(0));
        assertNull(generated.usage(), "the run was charged by ReviewRunCharges, never again here");
        assertEquals(COMMENT, generated.triggeringCommentId());
        assertEquals("TEST-ANSWER", generated.answerText());
        assertEquals(new IntegrationEvent.FollowUpPosted(REVIEW_ID, THREAD, "TEST-posted-9"), emitted.get(1));
        assertEquals("TEST-posted-9", slots.get(THREAD.value() + "/" + KEY));
    }

    @Test
    void aRedeliveredPostPostsNothing() {
        when(sink.replyInThread(eq(REPO), eq(7L), eq(THREAD), anyString()))
                .thenReturn(new CommentRef("TEST-posted-9", THREAD, CommentKind.REPLY));
        worker.post(post("TEST-ANSWER"));
        emitted.clear();

        worker.post(post("TEST-OTHER-ANSWER"));

        verify(sink, org.mockito.Mockito.times(1)).replyInThread(any(), anyLong(), any(), anyString());
        assertEquals(List.of(), emitted);
    }

    @Test
    void anAnswerWithCodeIsPostedUnescaped() {
        String answer = "It is.\n\n```java\nif (n < 2) { return; }\n```";
        when(sink.replyInThread(eq(REPO), eq(7L), eq(THREAD), anyString()))
                .thenReturn(new CommentRef("TEST-posted-9", THREAD, CommentKind.REPLY));

        worker.post(post(answer));

        verify(sink).replyInThread(REPO, 7, THREAD, answer);
    }
}

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


/**
 * Harness reviews, step 7: the review worker posts a reply run's answer once, mentioned and escaped, and resends its
 * completion events until both are known to have gone out (review of PR #371, code-quality/M-1).
 */
class HarnessReplyWorkerPostTest extends HarnessReplyWorkerFixture {

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
        assertEquals(new IntegrationEvent.FollowUpPosted(REVIEW_ID, THREAD, "TEST-posted-9", true), emitted.get(1));
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

    /** The model never writes a mention; the forge's own one, remembered at prepare, opens the posted answer. */
    @Test
    void thePostedAnswerOpensWithTheMentionThePrepareRemembered() {
        threadIs(thread(new ThreadMessage("TEST-alice", "TEST-HUMAN-ASKED", false)));
        when(sink.mention(ALICE)).thenReturn(java.util.Optional.of("@TEST-alice"));
        when(sink.replyInThread(eq(REPO), eq(7L), eq(THREAD), anyString()))
                .thenReturn(new CommentRef("TEST-posted-9", THREAD, CommentKind.REPLY));

        worker.prepare(prepare(false, 1));
        worker.post(post("TEST-ANSWER"));

        verify(sink).replyInThread(REPO, 7, THREAD, "@TEST-alice TEST-ANSWER");
        IntegrationEvent.FollowUpGenerated generated = (IntegrationEvent.FollowUpGenerated) emitted.get(1);
        assertEquals("@TEST-alice TEST-ANSWER", generated.answerText(), "the history shows what was posted");
    }

    /** A forge with no mention for this author (no login, no account id) posts the answer as it is. */
    @Test
    void anAuthorTheForgeCannotMentionGetsThePlainAnswer() {
        threadIs(thread(new ThreadMessage("TEST-alice", "TEST-HUMAN-ASKED", false)));
        when(sink.mention(ALICE)).thenReturn(java.util.Optional.empty());
        when(sink.replyInThread(eq(REPO), eq(7L), eq(THREAD), anyString()))
                .thenReturn(new CommentRef("TEST-posted-9", THREAD, CommentKind.REPLY));

        worker.prepare(prepare(false, 1));
        worker.post(post("TEST-ANSWER"));

        verify(sink).replyInThread(REPO, 7, THREAD, "TEST-ANSWER");
    }

    /**
     * A publish that fails after the post: the retry posts no second comment and sends both completion events with
     * the stored comment id, so the thread is counted and "answering" clears (review of PR #371, code-quality/M-1).
     */
    @Test
    void aPublishThatFailsAfterThePostIsSentAgainWithoutASecondComment() {
        when(sink.replyInThread(eq(REPO), eq(7L), eq(THREAD), anyString()))
                .thenReturn(new CommentRef("TEST-posted-9", THREAD, CommentKind.REPLY));
        failingEmits = 1;

        assertThrows(IllegalStateException.class, () -> worker.post(post("TEST-ANSWER")));
        worker.post(post("TEST-ANSWER"));

        verify(sink, org.mockito.Mockito.times(1)).replyInThread(any(), anyLong(), any(), anyString());
        assertEquals(2, emitted.size(), emitted.toString());
        assertInstanceOf(IntegrationEvent.FollowUpGenerated.class, emitted.get(0));
        assertEquals(new IntegrationEvent.FollowUpPosted(REVIEW_ID, THREAD, "TEST-posted-9", true), emitted.get(1));
    }

    /** The second of the two events failing is recovered the same way: both are sent again, one comment stays. */
    @Test
    void aSecondEventThatFailsIsSentAgainToo() {
        when(sink.replyInThread(eq(REPO), eq(7L), eq(THREAD), anyString()))
                .thenReturn(new CommentRef("TEST-posted-9", THREAD, CommentKind.REPLY));
        worker.results = new ResultsEmitter() {
            private int calls;

            @Override
            public void emit(IntegrationEvent event) {
                if (++calls == 2) {
                    throw new IllegalStateException("TEST-publish-failed");
                }
                emitted.add(event);
            }
        };

        assertThrows(IllegalStateException.class, () -> worker.post(post("TEST-ANSWER")));
        worker.post(post("TEST-ANSWER"));
        worker.post(post("TEST-ANSWER"));

        verify(sink, org.mockito.Mockito.times(1)).replyInThread(any(), anyLong(), any(), anyString());
        assertEquals(3, emitted.size(), "FollowUpGenerated twice, FollowUpPosted once, then nothing: " + emitted);
        assertEquals(new IntegrationEvent.FollowUpPosted(REVIEW_ID, THREAD, "TEST-posted-9", true), emitted.get(2));
    }

    /**
     * Both publishes went out, then the done mark failed: the retry posts no second comment and sends both events
     * again, which the orchestrator counts once per answer (ReviewThreadViewIT, review of PR #371, M-1).
     */
    @Test
    void aDoneMarkThatFailsAfterBothPublishesSendsThemAgainWithOneComment() {
        when(sink.replyInThread(eq(REPO), eq(7L), eq(THREAD), anyString()))
                .thenReturn(new CommentRef("TEST-posted-9", THREAD, CommentKind.REPLY));
        failingDoneMarks = 1;

        assertThrows(IllegalStateException.class, () -> worker.post(post("TEST-ANSWER")));
        worker.post(post("TEST-ANSWER"));
        worker.post(post("TEST-ANSWER"));

        verify(sink, org.mockito.Mockito.times(1)).replyInThread(any(), anyLong(), any(), anyString());
        assertEquals(4, emitted.size(), "both events twice, then nothing once the done mark holds: " + emitted);
        assertEquals(new IntegrationEvent.FollowUpPosted(REVIEW_ID, THREAD, "TEST-posted-9", true), emitted.get(3));
    }

    /** Raw HTML in model text must not become markup; code keeps its "<" (review of PR #371, rules/H-1). */
    @Test
    void rawHtmlOutsideCodeIsEscapedAndCodeIsNot() {
        when(sink.replyInThread(eq(REPO), eq(7L), eq(THREAD), anyString()))
                .thenReturn(new CommentRef("TEST-posted-9", THREAD, CommentKind.REPLY));

        worker.post(post("<b>TEST</b> and `a < b`"));

        verify(sink).replyInThread(REPO, 7, THREAD, "&lt;b>TEST&lt;/b> and `a < b`");
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

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


/** Harness reviews, step 7: the review worker prepares a reply on a harness (the thread, its claim, thread.md). */
class HarnessReplyWorkerPrepareTest extends HarnessReplyWorkerFixture {

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
}

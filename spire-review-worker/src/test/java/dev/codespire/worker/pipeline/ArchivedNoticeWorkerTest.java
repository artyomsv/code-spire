package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archived notice: one fixed-text comment, posted once per REVIEW however many events or
 * threads re-trigger it, and never a paid LLM call (spec §"the archived notice").
 */
class ArchivedNoticeWorkerTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = "review::TEST-WS/TEST-REPO#1";

    /** Tracks every top-level comment and every reply, so a test can assert "posted once" directly
     * rather than through a mocking framework's call count. */
    private static final class RecordingCommentSink implements CommentSink {
        private final List<String> summaries = new ArrayList<>();
        private final List<String> replies = new ArrayList<>();

        List<String> summaries() {
            return summaries;
        }

        List<String> replies() {
            return replies;
        }

        @Override
        public ScmType type() {
            return ScmType.GITHUB;
        }

        @Override
        public CommentRef postSummary(RepoRef repo, long prId, String bodyMd) {
            summaries.add(bodyMd);
            return new CommentRef("summary-" + summaries.size(), null, CommentKind.SUMMARY);
        }

        @Override
        public CommentRef postInline(RepoRef repo, long prId, String headCommit, InlineAnchor anchor,
                                     String bodyMd) {
            throw new UnsupportedOperationException("the archived notice never posts inline");
        }

        @Override
        public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
            replies.add(bodyMd);
            return new CommentRef("reply-" + replies.size(), thread, CommentKind.REPLY);
        }

        @Override
        public Author getPullRequestAuthor(RepoRef repo, long prId) {
            throw new UnsupportedOperationException("the archived notice never reads the PR author");
        }
    }

    /** Emulates the real store's claim-then-post semantics with no database: the same slot claimed
     * twice returns Post once and AlreadyPosted thereafter. */
    private static final class InMemoryIdempotencyStore extends CommentIdempotencyStore {
        private final Map<String, String> posted = new HashMap<>();

        @Override
        public Claim claim(String reviewId, String commit, String anchorKey) {
            String existing = posted.get(slot(reviewId, commit, anchorKey));
            return existing != null ? new Claim.AlreadyPosted(existing) : new Claim.Post();
        }

        @Override
        public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
            posted.put(slot(reviewId, commit, anchorKey), postedRef);
        }

        private static String slot(String reviewId, String commit, String anchorKey) {
            return reviewId + "/" + commit + "/" + anchorKey;
        }
    }

    private static ActionCommand.NotifyArchived notice(String threadRefValue) {
        ThreadRef thread = threadRefValue == null ? null : new ThreadRef(threadRefValue);
        return new ActionCommand.NotifyArchived(REVIEW_ID, REPO, 1L, thread, "TEST-CREDENTIAL");
    }

    /** A worker wired to the recording sink and an in-memory claim store — no database, no LLM
     * client, so a stray call to either fails the test rather than passing silently. */
    private static FollowUpWorker worker(RecordingCommentSink sink, List<IntegrationEvent> emitted) {
        FollowUpWorker worker = new FollowUpWorker();
        worker.promptLog = new PromptLog();
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(null, sink);
            }
        };
        worker.idempotency = new InMemoryIdempotencyStore();
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        return worker;
    }

    @Test
    void theNoticePostsOnceHoweverManyEventsArrive() {
        RecordingCommentSink sink = new RecordingCommentSink();
        FollowUpWorker worker = worker(sink, new ArrayList<>());

        worker.notifyArchived(notice("TEST-THREAD"));
        worker.notifyArchived(notice("TEST-THREAD"));

        assertEquals(1, sink.replies().size());
    }

    @Test
    void theNoticeIsClaimedPerReviewNotPerThread() {
        RecordingCommentSink sink = new RecordingCommentSink();
        FollowUpWorker worker = worker(sink, new ArrayList<>());

        worker.notifyArchived(notice("TEST-THREAD-A"));
        worker.notifyArchived(notice("TEST-THREAD-B"));

        assertEquals(1, sink.replies().size(), "a second thread must not produce a second notice");
    }

    @Test
    void aNoticeWithNoThreadGoesToTheTopLevelPrComment() {
        RecordingCommentSink sink = new RecordingCommentSink();
        worker(sink, new ArrayList<>()).notifyArchived(notice(null));

        assertEquals(1, sink.summaries().size());
        assertTrue(sink.replies().isEmpty());
    }

    /**
     * {@code worker.llm} is deliberately left unset (null): if {@code notifyArchived} ever brokered
     * an LLM client the way {@code answer} does, this would NPE instead of passing quietly.
     */
    @Test
    void theNoticeBrokersNoModelCall() {
        RecordingCommentSink sink = new RecordingCommentSink();
        List<IntegrationEvent> emitted = new ArrayList<>();
        FollowUpWorker worker = worker(sink, emitted);

        assertDoesNotThrow(() -> worker.notifyArchived(notice("TEST-THREAD")));

        assertTrue(emitted.stream().noneMatch(e -> e instanceof IntegrationEvent.FollowUpGenerated),
                "retiring a PR must cost no tokens — no model-usage event is ever emitted");
    }

    @Test
    void theNoticeEmitsArchivedNotifiedNotFollowUpPosted() {
        RecordingCommentSink sink = new RecordingCommentSink();
        List<IntegrationEvent> emitted = new ArrayList<>();
        worker(sink, emitted).notifyArchived(notice("TEST-THREAD"));

        IntegrationEvent.ArchivedNotified notified = assertInstanceOf(
                IntegrationEvent.ArchivedNotified.class, emitted.getFirst(),
                "FollowUpPosted would bump the turn count for a notice that consumed no turn");
        assertEquals(REVIEW_ID, notified.reviewId());
        assertEquals("reply-1", notified.commentId());
    }
}

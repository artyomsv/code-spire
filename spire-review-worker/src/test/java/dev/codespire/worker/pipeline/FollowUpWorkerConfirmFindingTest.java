package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.Severity;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirming a conversation-raised finding in the thread it came from: fixed text naming the
 * severity and anchor, no LLM call, and no {@code FollowUpPosted} — confirming a filing is not the
 * bot taking a conversational turn (spec §"the confirmation notice", mirroring the turn-cap and
 * archived notices).
 */
class FollowUpWorkerConfirmFindingTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = "review::TEST-WS/TEST-REPO#1";
    private static final ThreadRef THREAD = new ThreadRef("TEST-THREAD");

    /** Tracks every top-level comment and every reply, so a test can assert "posted once" directly
     * rather than through a mocking framework's call count. Mirrors ArchivedNoticeWorkerTest. */
    private static final class RecordingCommentSink implements CommentSink {
        private final List<String> summaries = new ArrayList<>();
        private final List<String> replies = new ArrayList<>();

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
            throw new UnsupportedOperationException("a finding confirmation never posts inline");
        }

        @Override
        public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
            replies.add(bodyMd);
            return new CommentRef("reply-" + replies.size(), thread, CommentKind.REPLY);
        }

        @Override
        public Author getPullRequestAuthor(RepoRef repo, long prId) {
            throw new UnsupportedOperationException("a finding confirmation never reads the PR author");
        }
    }

    /** Emulates the real store's claim-then-post semantics with no database. */
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

    private static ActionCommand.ConfirmFinding command(String triggeringCommentId) {
        return new ActionCommand.ConfirmFinding(REVIEW_ID, REPO, 1L, THREAD, triggeringCommentId,
                Severity.MAJOR, "src/Foo.java", 44, "TEST-CREDENTIAL");
    }

    private static FollowUpWorker worker(RecordingCommentSink sink, InMemoryIdempotencyStore claims) {
        return worker(sink, claims, new ArrayList<>());
    }

    private static FollowUpWorker worker(RecordingCommentSink sink, InMemoryIdempotencyStore claims,
                                         List<IntegrationEvent> emitted) {
        FollowUpWorker worker = new FollowUpWorker();
        worker.promptLog = new PromptLog();
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(null, sink);
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

    @Test
    void namesTheSeverityAndTheAnchor() {
        String text = FollowUpWorker.confirmText(Severity.MAJOR, "src/Foo.java", 44);

        assertTrue(text.contains("MAJOR"));
        assertTrue(text.contains("src/Foo.java:44"));
    }

    @Test
    void postsOncePerTriggeringComment() {
        // Same claim shape as followup: a redelivered ConfirmFinding must not post a second reply.
        RecordingCommentSink sink = new RecordingCommentSink();
        InMemoryIdempotencyStore claims = new InMemoryIdempotencyStore();

        worker(sink, claims).confirmFinding(command("c-901"));
        worker(sink, claims).confirmFinding(command("c-901"));

        assertEquals(1, sink.replies().size());
    }

    /**
     * Deliberately different from the turn-cap and archived notices: a second /finding in the same
     * thread is a second finding, and deserves its own confirmation rather than finding the slot
     * already taken.
     */
    @Test
    void aSecondFindingInTheSameThreadGetsItsOwnConfirmation() {
        RecordingCommentSink sink = new RecordingCommentSink();
        InMemoryIdempotencyStore claims = new InMemoryIdempotencyStore();

        worker(sink, claims).confirmFinding(command("c-901"));
        worker(sink, claims).confirmFinding(command("c-902"));

        assertEquals(2, sink.replies().size());
    }

    @Test
    void doesNotConsumeAConversationTurn() {
        // Confirming costs no LLM call and must not push the thread toward its turn cap: the notice
        // is not the bot taking part in the discussion. TurnCapNotified exists for the same reason.
        List<IntegrationEvent> emitted = new ArrayList<>();

        worker(new RecordingCommentSink(), new InMemoryIdempotencyStore(), emitted)
                .confirmFinding(command("c-901"));

        assertTrue(emitted.stream().noneMatch(IntegrationEvent.FollowUpPosted.class::isInstance));
    }
}

package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ConversationFindingRefusal;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spoken half of a {@code /finding} refusal: reached only when there IS a thread to reply into
 * (a {@code /finding} typed in a summary thread) — a command with nowhere to post at all is refused
 * on the orchestrator's timeline alone and never reaches this worker.
 */
class FollowUpWorkerRefuseFindingTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = "review::TEST-WS/TEST-REPO#1";
    private static final ThreadRef THREAD = new ThreadRef("TEST-THREAD");

    /** Mirrors ArchivedNoticeWorkerTest / FollowUpWorkerConfirmFindingTest. */
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
            throw new UnsupportedOperationException("a finding refusal never posts inline");
        }

        @Override
        public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
            replies.add(bodyMd);
            return new CommentRef("reply-" + replies.size(), thread, CommentKind.REPLY);
        }

        @Override
        public Author getPullRequestAuthor(RepoRef repo, long prId) {
            throw new UnsupportedOperationException("a finding refusal never reads the PR author");
        }
    }

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

    private static ActionCommand.RefuseFinding command(ThreadRef thread) {
        return new ActionCommand.RefuseFinding(REVIEW_ID, REPO, 1L, thread, "TEST-CREDENTIAL");
    }

    private static FollowUpWorker worker(RecordingCommentSink sink, InMemoryIdempotencyStore claims) {
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
                throw new AssertionError("a finding refusal reports nothing back — the orchestrator "
                        + "already recorded it on the timeline before dispatching this command");
            }
        };
        return worker;
    }

    @Test
    void repliesWithTheSharedRefusalText() {
        RecordingCommentSink sink = new RecordingCommentSink();

        worker(sink, new InMemoryIdempotencyStore()).refuseFinding(command(THREAD));

        assertEquals(List.of(ConversationFindingRefusal.NO_ANCHOR_REPLY), sink.replies());
    }

    @Test
    void postsOncePerThreadHoweverManyTimesItIsMisused() {
        // The text never varies, so repeating it does not help — unlike a confirmation, which
        // deserves a fresh reply for each distinct finding.
        RecordingCommentSink sink = new RecordingCommentSink();
        InMemoryIdempotencyStore claims = new InMemoryIdempotencyStore();

        worker(sink, claims).refuseFinding(command(THREAD));
        worker(sink, claims).refuseFinding(command(THREAD));

        assertEquals(1, sink.replies().size());
    }

    @Test
    void aDifferentThreadGetsItsOwnRefusal() {
        RecordingCommentSink sink = new RecordingCommentSink();
        InMemoryIdempotencyStore claims = new InMemoryIdempotencyStore();

        worker(sink, claims).refuseFinding(command(THREAD));
        worker(sink, claims).refuseFinding(command(new ThreadRef("TEST-THREAD-OTHER")));

        assertEquals(2, sink.replies().size());
    }
}

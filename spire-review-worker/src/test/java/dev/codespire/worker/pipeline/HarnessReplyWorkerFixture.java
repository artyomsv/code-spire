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
 * The real {@link HarnessReplyWorker} over fakes, shared by {@code HarnessReplyWorkerPrepareTest} and
 * {@code HarnessReplyWorkerPostTest}: the forge clients are mocks, and the claim store and the results emitter override
 * every method the reply path reaches, so no real database or broker is opened (CLAUDE.md, saga test fakes).
 */
abstract class HarnessReplyWorkerFixture {

    static final String REVIEW_ID = "review::TEST-work/app#7";
    static final RepoRef REPO = new RepoRef("TEST-work", "app");
    static final ThreadRef THREAD = new ThreadRef("TEST-thread-1");
    static final String COMMENT = "TEST-comment-5";
    static final String HEAD = "c".repeat(40);
    static final String KEY = "followup:" + COMMENT;
    static final Author ALICE = Author.of("TEST-id-9", "TEST-alice", "TEST Alice");

    final List<IntegrationEvent> emitted = new ArrayList<>();
    /** The claim slots by {@code thread/key}: the posted ref, or null while only claimed. */
    final Map<String, String> slots = new HashMap<>();
    /** How many of the next emits fail, as a publish that times out after the post. */
    int failingEmits;
    /** How many of the next done marks fail, as a database outage after both publishes. */
    int failingDoneMarks;
    CommentSink sink;
    DiffSource diffs;
    HarnessReplyWorker worker;

    @BeforeEach
    void setUp() {
        sink = mock(CommentSink.class, withSettings().extraInterfaces(ThreadSource.class));
        diffs = mock(DiffSource.class);
        when(diffs.fetchPullRequest(REPO, 7)).thenReturn(new PullRequest(REPO, 7, "TEST-TITLE", "TEST-DESCRIPTION",
                "TEST-source", "main", HEAD, Author.of("TEST-id", "TEST-user", "TEST-user"),
                "https://forge.example.test/TEST-work/app/pull/7"));
        worker = workerWith(sink);
    }

    HarnessReplyWorker workerWith(CommentSink comments) {
        HarnessReplyWorker built = new HarnessReplyWorker();
        built.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(diffs, comments);
            }
        };
        built.idempotency = idempotency();
        built.results = results();
        return built;
    }

    /** Overrides every method the reply path reaches, so no real database is opened (CLAUDE.md gotcha). */
    CommentIdempotencyStore idempotency() {
        return new CommentIdempotencyStore() {
            @Override
            public Claim claim(String reviewId, String commit, String anchorKey) {
                String posted = slots.get(commit + "/" + anchorKey);
                slots.putIfAbsent(commit + "/" + anchorKey, null);
                return posted == null ? new Claim.Post() : new Claim.AlreadyPosted(posted);
            }

            @Override
            public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
                if (anchorKey.startsWith("harness:reply-done:") && failingDoneMarks > 0) {
                    failingDoneMarks--;
                    throw new IllegalStateException("TEST-database-down");
                }
                slots.put(commit + "/" + anchorKey, postedRef);
            }

            @Override
            public Map<String, String> postedFor(String reviewId, String commit) {
                Map<String, String> posted = new HashMap<>();
                slots.forEach((slot, ref) -> {
                    if (ref != null && slot.startsWith(commit + "/")) {
                        posted.put(slot.substring(commit.length() + 1), ref);
                    }
                });
                return posted;
            }
        };
    }

    /** The real one awaits a Kafka ack; this one records, or fails the next {@code failingEmits} publishes. */
    ResultsEmitter results() {
        return new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                if (failingEmits > 0) {
                    failingEmits--;
                    throw new IllegalStateException("TEST-publish-failed");
                }
                emitted.add(event);
            }
        };
    }

    static ThreadTranscript thread(ThreadMessage... messages) {
        return new ThreadTranscript(THREAD, "src/A.java", 3, "b".repeat(40), List.of(messages));
    }

    void threadIs(ThreadTranscript transcript) {
        when(((ThreadSource) sink).fetchThread(REPO, 7, THREAD)).thenReturn(transcript);
    }

    static ActionCommand.PrepareHarnessReply prepare(boolean mentioned, int maxAttempts) {
        return new ActionCommand.PrepareHarnessReply(REVIEW_ID, REPO, 7, THREAD, COMMENT, null, mentioned, 2,
                List.of(new PriorFinding("src/B.java", 9, Severity.MINOR, "TEST-OTHER-FINDING", "TEST-thread-2")),
                new ReplyRetry(maxAttempts, 0, 1.0), ALICE);
    }

    static ActionCommand.PostHarnessReply post(String answer) {
        return new ActionCommand.PostHarnessReply(REVIEW_ID, REPO, 7, THREAD, COMMENT, answer, null,
                new ReplyRetry(1, 0, 1.0));
    }
}

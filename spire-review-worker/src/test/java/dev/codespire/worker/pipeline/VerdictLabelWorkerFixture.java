package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.diff.UnifiedDiffParser;
import dev.codespire.worker.adapters.WorkerScmClients;
import dev.codespire.worker.pipeline.ReviewWorkerTest.InMemoryIdempotency;

import java.util.ArrayList;
import java.util.List;

/**
 * A review worker wired with hand-written fakes for the verdict label tests: a GitHub-like sink holding labels, an
 * in-memory claim store kept across deliveries, and a pull request whose head a test may move.
 */
final class VerdictLabelWorkerFixture {

    static final RepoRef REPO = new RepoRef("TEST-ws", "TEST-repo");
    static final String REVIEW_ID = "review::TEST-ws/TEST-repo#9";
    static final String COMMIT = "TEST-commit";
    private static final String DIFF = """
            diff --git a/src/A.java b/src/A.java
            --- a/src/A.java
            +++ b/src/A.java
            @@ -1,2 +1,2 @@
             line1
            -old2
            +new2
            """;

    final List<IntegrationEvent> emitted = new ArrayList<>();
    final LabelRecordingSink sink = new LabelRecordingSink();
    final DesiredState idempotency = new DesiredState();
    /** The pull request's head; a test moves it to supersede the run. */
    String head = COMMIT;

    ReviewWorker worker() {
        ReviewWorker worker = new ReviewWorker();
        worker.mapper = new ObjectMapper();
        worker.inlinePostThrottleMs = 0;
        worker.rateLimitRetryCapSeconds = 0;
        worker.rateLimitBudgetSeconds = 180;
        worker.idempotency = idempotency;
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        DiffSource diffs = new PullRequestAtHead();
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(diffs, sink);
            }
        };
        return worker;
    }

    /** Labels on, posted as the verdict it decided. */
    static PostComments labelled(ReviewVerdict assessment) {
        return unlabelledCommand().withVerdict(assessment, assessment, true).withVerdictLabels(true);
    }

    /** Labels on, "Reviewer may approve" off: posted as a Comment whatever it decided. */
    static PostComments labelledPostedAsComment(ReviewVerdict assessment) {
        return unlabelledCommand().withVerdict(assessment, ReviewVerdict.COMMENT, false).withVerdictLabels(true);
    }

    /** Labels off, posted as the verdict it decided. */
    static PostComments unlabelled(ReviewVerdict assessment) {
        return unlabelledCommand().withVerdict(assessment, assessment, true).withVerdictLabels(false);
    }

    /** A command from before the verdict and label fields existed. */
    static PostComments unlabelledCommand() {
        return new PostComments(REVIEW_ID, REPO, 9, COMMIT,
                new ReviewResult(List.of(), "summary", ModelUsage.of("TEST-MODEL", 0, 0)), null);
    }

    /** The pull request at {@link #head}, with a one-file diff. */
    private final class PullRequestAtHead implements DiffSource {
        @Override
        public ScmType type() {
            return ScmType.GITHUB;
        }

        @Override
        public String apiHost() {
            return "api.example.invalid";
        }

        @Override
        public PullRequest fetchPullRequest(RepoRef repo, long prId) {
            return new PullRequest(repo, prId, "TEST pr", "TEST", "feature/t", "main",
                    head, Author.of("TEST-id", "test", "Test"), "https://example.invalid");
        }

        @Override
        public Diff fetchDiff(RepoRef repo, long prId, String commit) {
            return new Diff(commit, UnifiedDiffParser.parse(DIFF), false);
        }
    }

    /**
     * The in-memory claim store with the review's desired verdict label, as the orchestrator records it. A test may
     * fail the next reads, as a lost database connection would.
     */
    static final class DesiredState extends InMemoryIdempotency {
        DesiredLabel desired;
        int failingReads;

        void desire(int round, String label) {
            desired = new DesiredLabel(round, label);
        }

        @Override
        public java.util.Optional<DesiredLabel> desiredLabel(String reviewId) {
            if (failingReads > 0) {
                failingReads--;
                throw new IllegalStateException("TEST: review_label_desired read failed");
            }
            return java.util.Optional.ofNullable(desired);
        }
    }

    /** A forge with no label call: the port's default. Every posting method is unreachable here. */
    static final class PlainSink implements CommentSink {
        @Override
        public ScmType type() {
            return ScmType.GITLAB;
        }

        @Override
        public CommentRef postSummary(RepoRef repo, long prId, String bodyMd) {
            throw new UnsupportedOperationException("TEST: not reached");
        }

        @Override
        public CommentRef postInline(RepoRef repo, long prId, String headCommit, InlineAnchor anchor, String bodyMd) {
            throw new UnsupportedOperationException("TEST: not reached");
        }

        @Override
        public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
            throw new UnsupportedOperationException("TEST: not reached");
        }

        @Override
        public Author getPullRequestAuthor(RepoRef repo, long prId) {
            throw new UnsupportedOperationException("TEST: not reached");
        }
    }
}

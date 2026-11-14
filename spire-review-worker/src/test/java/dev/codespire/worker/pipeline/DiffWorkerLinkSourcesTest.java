package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.LinkSource;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.PullRequestComment;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worker.adapters.WorkerCodeReferences;
import dev.codespire.worker.adapters.WorkerContextReferences;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness reviews, step 8b: an untrusted pull request also reads its comments and reports who wrote each link; a
 * same-repository pull request reads no comment and sends what it sent before.
 */
class DiffWorkerLinkSourcesTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final FetchDiff SAME_REPOSITORY = new FetchDiff("TEST-review", REPO, 7, "abc123", null);
    private static final FetchDiff UNTRUSTED = SAME_REPOSITORY.withUntrustedPullRequest(true);
    private static final String PR_LINK = "https://example.invalid/TEST-pr-link";
    private static final String COMMENT_LINK = "https://example.invalid/TEST-comment-link";

    private final List<IntegrationEvent> emitted = new ArrayList<>();
    private final List<PullRequestComment> comments = new ArrayList<>();
    private int commentReads;
    private RuntimeException commentFailure;
    private DiffWorker worker;

    @BeforeEach
    void setUp() {
        worker = new DiffWorker();
        worker.references = new WorkerContextReferences();
        worker.codeRefs = new WorkerCodeReferences();
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        DiffSource source = diffSource();
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(source, null);
            }
        };
    }

    @Test
    void anUntrustedPullRequestReportsEachSourceWithItsAuthor() {
        comments.add(new PullRequestComment(Author.of("TEST-101", "TEST-maintainer", "TEST"), "see " + COMMENT_LINK));
        comments.add(new PullRequestComment(Author.of("TEST-102", "TEST-chatter", "TEST"), "looks good"));

        worker.fetchDiff(UNTRUSTED);

        List<LinkSource> sources = fetched().linkSources();
        assertEquals(2, sources.size(), "a comment with no link is left out: " + sources);
        assertEquals(new LinkSource(LinkSource.Kind.PULL_REQUEST, "TEST-7", "TEST-author", Set.of(PR_LINK)),
                sources.get(0));
        assertEquals(new LinkSource(LinkSource.Kind.COMMENT, "TEST-101", "TEST-maintainer", Set.of(COMMENT_LINK)),
                sources.get(1));
        assertEquals(Set.of(PR_LINK), fetched().references());
    }

    @Test
    void aSameRepositoryPullRequestReadsNoComments() {
        comments.add(new PullRequestComment(Author.of("TEST-101", "TEST-maintainer", "TEST"), COMMENT_LINK));

        worker.fetchDiff(SAME_REPOSITORY);

        assertEquals(0, commentReads);
        assertTrue(fetched().linkSources().isEmpty(), fetched().linkSources().toString());
        assertEquals(Set.of(PR_LINK), fetched().references());
    }

    @Test
    void aFailedCommentReadKeepsThePullRequestSourceAndTheReview() {
        commentFailure = new IllegalStateException("TEST forge down");

        worker.fetchDiff(UNTRUSTED);

        assertEquals(List.of(new LinkSource(LinkSource.Kind.PULL_REQUEST, "TEST-7", "TEST-author", Set.of(PR_LINK))),
                fetched().linkSources());
    }

    private DiffFetched fetched() {
        assertEquals(1, emitted.size(), emitted.toString());
        return assertInstanceOf(DiffFetched.class, emitted.getFirst());
    }

    private DiffSource diffSource() {
        return new DiffSource() {
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
                return new PullRequest(repo, prId, "TEST title", "context: " + PR_LINK, "TEST-branch", "main",
                        "abc123", Author.of("TEST-7", "TEST-author", "TEST"), "https://example.invalid/pr");
            }

            @Override
            public Diff fetchDiff(RepoRef repo, long prId, String commit) {
                return new Diff(commit, List.of(), false);
            }

            @Override
            public List<PullRequestComment> fetchPullRequestComments(RepoRef repo, long prId) {
                commentReads++;
                if (commentFailure != null) {
                    throw commentFailure;
                }
                return comments;
            }
        };
    }
}

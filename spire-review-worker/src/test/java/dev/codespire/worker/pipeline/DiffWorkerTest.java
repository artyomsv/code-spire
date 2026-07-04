package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.diff.UnifiedDiffParser;
import dev.codespire.scm.bitbucket.BitbucketApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DiffWorker error-path unit tests (the 404-abandon and retryable-classification branches). */
class DiffWorkerTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final FetchDiff COMMAND = new FetchDiff("review::sandbox/demo-repo#7", REPO, 7, "abc123");

    private DiffWorker worker;
    private List<IntegrationEvent> emitted;
    private RuntimeException failure;

    @BeforeEach
    void setUp() {
        emitted = new ArrayList<>();
        worker = new DiffWorker();
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        worker.diffSource = new DiffSource() {
            @Override
            public ScmType type() {
                return ScmType.BITBUCKET_CLOUD;
            }

            @Override
            public PullRequest fetchPullRequest(RepoRef repo, long prId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Diff fetchDiff(RepoRef repo, long prId, String commit) {
                if (failure != null) {
                    throw failure;
                }
                return new Diff(DiffRefs.headOnly(commit), UnifiedDiffParser.parse("""
                        diff --git a/x.java b/x.java
                        --- a/x.java
                        +++ b/x.java
                        @@ -1 +1 @@
                        -a
                        +b
                        """), false);
            }
        };
    }

    @Test
    void happyPathEmitsMetadataOnly() {
        worker.fetchDiff(COMMAND);
        DiffFetched fetched = assertInstanceOf(DiffFetched.class, emitted.getFirst());
        assertEquals(1, fetched.changedFiles());
        assertEquals("abc123", fetched.commit());
    }

    @Test
    void notFoundAbandonsQuietlyAsSuperseded() {
        failure = new BitbucketApiException(404, "GET", "/diff");
        worker.fetchDiff(COMMAND);
        assertTrue(emitted.isEmpty(), "no event for a force-pushed-away commit");
    }

    @Test
    void serverErrorIsRetryable() {
        failure = new BitbucketApiException(503, "GET", "/diff");
        worker.fetchDiff(COMMAND);
        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getFirst());
        assertTrue(failed.retryable());
    }

    @Test
    void clientErrorIsTerminal() {
        failure = new BitbucketApiException(403, "GET", "/diff");
        worker.fetchDiff(COMMAND);
        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getFirst());
        assertFalse(failed.retryable());
    }
}

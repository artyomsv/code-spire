package dev.codespire.worker.adapters;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.port.ScmIngress;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.diff.UnifiedDiffParser;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stub SCM adapters for dev/test (spire.scm.provider=stub): the pipeline runs
 * end-to-end without any external SCM. Every value is self-labeling synthetic
 * (STUB/TEST) — never mistakable for real data.
 */
public final class StubScm {

    private StubScm() {
    }

    /** A deterministic synthetic diff the stub LLM's canned finding can anchor to. */
    static final String STUB_DIFF = """
            diff --git a/src/Demo.java b/src/Demo.java
            --- a/src/Demo.java
            +++ b/src/Demo.java
            @@ -1,3 +1,4 @@
             class Demo {
            +    int stubAddedLine = 42;
                 void run() {}
             }
            """;

    /** Webhooks are meaningless without a real SCM — reject them all. */
    public static final class RejectingIngress implements ScmIngress {

        @Override
        public ScmType type() {
            return ScmType.BITBUCKET_CLOUD;
        }

        @Override
        public boolean verifySignature(RawWebhook raw) {
            return false;
        }

        @Override
        public List<IntegrationEvent> translate(RawWebhook raw) {
            return List.of();
        }
    }

    public static final class StubDiffSource implements DiffSource {

        @Override
        public ScmType type() {
            return ScmType.BITBUCKET_CLOUD;
        }

        @Override
        public PullRequest fetchPullRequest(RepoRef repo, long prId) {
            // head=null: the stub cannot know the simulator's random commit, and a
            // null head deliberately skips the workers' head-moved check (Commits).
            return new PullRequest(repo, prId, "TEST: stub PR", "STUB description",
                    "feature/TEST-stub", "main", DiffRefs.headOnly(null),
                    Author.of("TEST-account-id", "test-author", "TEST Author"),
                    "https://example.invalid/stub/" + prId);
        }

        @Override
        public Diff fetchDiff(RepoRef repo, long prId, String commit) {
            return new Diff(DiffRefs.headOnly(commit), UnifiedDiffParser.parse(STUB_DIFF), false);
        }
    }

    public static final class LoggingCommentSink implements CommentSink {

        private static final Logger LOG = Logger.getLogger(LoggingCommentSink.class);
        private final AtomicLong ids = new AtomicLong(9000);

        @Override
        public ScmType type() {
            return ScmType.BITBUCKET_CLOUD;
        }

        @Override
        public CommentRef postSummary(RepoRef repo, long prId, String bodyMd) {
            String id = "STUB-" + ids.incrementAndGet();
            LOG.infof("STUB summary comment on %s#%d: %.120s", repo.full(), prId, bodyMd);
            return new CommentRef(id, new ThreadRef(id), CommentKind.SUMMARY);
        }

        @Override
        public CommentRef postInline(RepoRef repo, long prId, DiffRefs refs, InlineAnchor anchor, String bodyMd) {
            String id = "STUB-" + ids.incrementAndGet();
            LOG.infof("STUB inline comment on %s#%d %s: %.120s", repo.full(), prId, anchor.anchorKey(), bodyMd);
            return new CommentRef(id, new ThreadRef(id), CommentKind.INLINE);
        }

        @Override
        public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
            String id = "STUB-" + ids.incrementAndGet();
            LOG.infof("STUB reply on %s#%d in thread %s", repo.full(), prId, thread.value());
            return new CommentRef(id, thread, CommentKind.REPLY);
        }

        @Override
        public Author getPullRequestAuthor(RepoRef repo, long prId) {
            return Author.of("TEST-account-id", "test-author", "TEST Author");
        }
    }
}

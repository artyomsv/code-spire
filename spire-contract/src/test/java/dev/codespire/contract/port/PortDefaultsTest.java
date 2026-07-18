package dev.codespire.contract.port;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortDefaultsTest {

    private final RepoRef repo = new RepoRef("ws", "repo");

    @Test
    void diffSourceCompareDefaultsToUnavailable() {
        DiffSource source = new DiffSource() {
            @Override public ScmType type() { return ScmType.GITHUB; }
            @Override public dev.codespire.contract.scm.PullRequest fetchPullRequest(RepoRef r, long id) { return null; }
            @Override public dev.codespire.contract.scm.Diff fetchDiff(RepoRef r, long id, String commit) { return null; }
        };
        assertNull(source.fetchCompareDiff(repo, "aaa", "bbb"));
    }

    @Test
    void commentSinkResolveDefaultsToUnsupportedAndUpdateThrows() {
        CommentSink sink = new CommentSink() {
            @Override public ScmType type() { return ScmType.BITBUCKET_CLOUD; }
            @Override public dev.codespire.contract.scm.CommentRef postSummary(RepoRef r, long id, String b) { return null; }
            @Override public dev.codespire.contract.scm.CommentRef postInline(RepoRef r, long id,
                    dev.codespire.contract.scm.DiffRefs refs, dev.codespire.contract.scm.InlineAnchor a, String b) { return null; }
            @Override public dev.codespire.contract.scm.CommentRef replyInThread(RepoRef r, long id, ThreadRef t, String b) { return null; }
            @Override public dev.codespire.contract.scm.Author getPullRequestAuthor(RepoRef r, long id) { return null; }
        };
        assertEquals(CommentSink.ThreadResolution.UNSUPPORTED,
                sink.resolveThread(repo, 1L, new ThreadRef("t")));
        assertThrows(UnsupportedOperationException.class,
                () -> sink.updateComment(repo, 1L, "c1", "body"));
    }
}

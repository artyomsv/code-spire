package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.Side;
import dev.codespire.contract.scm.ThreadRef;

import java.util.Map;

/**
 * GitHub write adapter (SCM-MAPPING §4-§6). The PR summary is an ISSUE comment;
 * inline review comments go on the pulls endpoint and require {@code commit_id}
 * (the head SHA), {@code path}, {@code line} and {@code side} (RIGHT = NEW,
 * LEFT = OLD). {@link ThreadRef} carries the review-comment id — replies POST to
 * {@code .../comments/{id}/replies}.
 */
public class GitHubCommentSink implements CommentSink {

    private final GitHubClient client;

    public GitHubCommentSink(GitHubClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.GITHUB;
    }

    @Override
    public CommentRef postSummary(RepoRef repo, long prId, String bodyMd) {
        JsonNode created = client.postJson(issueCommentsPath(repo, prId), Map.of("body", bodyMd));
        String id = created.path("id").asText();
        return new CommentRef(id, new ThreadRef(id), CommentKind.SUMMARY);
    }

    @Override
    public CommentRef postInline(RepoRef repo, long prId, DiffRefs refs, InlineAnchor anchor, String bodyMd) {
        boolean old = anchor.side() == Side.OLD;
        int line = old ? anchor.oldLine() : anchor.newLine();
        JsonNode created = client.postJson(reviewCommentsPath(repo, prId), Map.of(
                "body", bodyMd,
                "commit_id", refs.headSha(),
                "path", anchor.path(),
                "line", line,
                "side", old ? "LEFT" : "RIGHT"));
        String id = created.path("id").asText();
        return new CommentRef(id, new ThreadRef(id), CommentKind.INLINE);
    }

    @Override
    public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
        JsonNode created = client.postJson(
                reviewCommentsPath(repo, prId) + "/" + thread.value() + "/replies",
                Map.of("body", bodyMd));
        return new CommentRef(created.path("id").asText(), thread, CommentKind.REPLY);
    }

    @Override
    public Author getPullRequestAuthor(RepoRef repo, long prId) {
        JsonNode user = client.getJson(prPath(repo, prId)).path("user");
        String login = user.path("login").asText("");
        return Author.of(user.path("id").asText(""), login, login);
    }

    private String prPath(RepoRef repo, long prId) {
        return "/repos/" + repo.workspace() + "/" + repo.slug() + "/pulls/" + prId;
    }

    private String reviewCommentsPath(RepoRef repo, long prId) {
        return prPath(repo, prId) + "/comments";
    }

    private String issueCommentsPath(RepoRef repo, long prId) {
        return "/repos/" + repo.workspace() + "/" + repo.slug() + "/issues/" + prId + "/comments";
    }
}

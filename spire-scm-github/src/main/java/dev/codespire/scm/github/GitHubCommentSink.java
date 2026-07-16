package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.Side;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitHub write adapter (SCM-MAPPING §4-§6). The PR summary is an ISSUE comment;
 * inline review comments go on the pulls endpoint and require {@code commit_id}
 * (the head SHA), {@code path}, {@code line} and {@code side} (RIGHT = NEW,
 * LEFT = OLD). {@link ThreadRef} carries the review-comment id — replies POST to
 * {@code .../comments/{id}/replies}.
 */
public class GitHubCommentSink implements CommentSink, ThreadSource {

    // Bounds thread re-fetch on a pathological PR (100 comments/page × pages).
    private static final int MAX_THREAD_PAGES = 20;

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
        String id = requireCommentId(created, issueCommentsPath(repo, prId));
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
        String id = requireCommentId(created, reviewCommentsPath(repo, prId));
        return new CommentRef(id, new ThreadRef(id), CommentKind.INLINE);
    }

    @Override
    public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
        String path = reviewCommentsPath(repo, prId) + "/" + thread.value() + "/replies";
        JsonNode created = client.postJson(path, Map.of("body", bodyMd));
        return new CommentRef(requireCommentId(created, path), thread, CommentKind.REPLY);
    }

    /** A 2xx without an id must not flow an empty key into the idempotency store. */
    private static String requireCommentId(JsonNode created, String path) {
        String id = created.hasNonNull("id") ? created.get("id").asText() : "";
        if (id.isBlank()) {
            throw new GitHubApiException(200, "POST", path, "2xx response carried no comment id");
        }
        return id;
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

    @Override
    public ThreadTranscript fetchThread(RepoRef repo, long prId, ThreadRef thread) {
        String botLogin = client.getJson("/user").path("login").asText("");
        String root = thread.value();
        String path = "";
        int line = 0;
        String commit = "";
        List<ThreadMessage> messages = new ArrayList<>();
        // The review-comments endpoint paginates (100/page max); a thread's replies can span pages, so walk
        // pages until a short/empty one. MAX_THREAD_PAGES bounds a pathological PR.
        for (int page = 1; page <= MAX_THREAD_PAGES; page++) {
            JsonNode pageComments = client.getJson(reviewCommentsPath(repo, prId) + "?per_page=100&page=" + page);
            int count = 0;
            for (JsonNode c : pageComments) {
                count++;
                String id = c.path("id").asText();
                String inReplyTo = c.path("in_reply_to_id").asText("");
                if (!root.equals(id) && !root.equals(inReplyTo)) {
                    continue; // a different thread
                }
                if (root.equals(id)) {                      // the root carries the anchor
                    path = c.path("path").asText("");
                    line = c.path("original_line").asInt(c.path("line").asInt(0));
                    commit = c.path("commit_id").asText("");
                }
                String login = c.path("user").path("login").asText("");
                messages.add(new ThreadMessage(login, c.path("body").asText("").trim(), login.equals(botLogin)));
            }
            if (count < 100) {
                break; // last page reached
            }
        }
        return new ThreadTranscript(thread, path, line, commit, messages);
    }
}

package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;

import java.util.HashMap;
import java.util.Map;

/**
 * GitLab write adapter (SCM-MAPPING §4-§6). The PR summary is a merge-request
 * NOTE; an inline comment opens a DISCUSSION anchored by a {@code position}
 * carrying all three {@link DiffRefs} SHAs plus the old/new path and line (a
 * wrong line/side combination is rejected with HTTP 400). {@link ThreadRef}
 * carries the {@code discussion_id} — replies POST to
 * {@code .../discussions/{discussion_id}/notes}.
 */
public class GitLabCommentSink implements CommentSink {

    private final GitLabClient client;

    public GitLabCommentSink(GitLabClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.GITLAB;
    }

    @Override
    public CommentRef postSummary(RepoRef repo, long prId, String bodyMd) {
        String path = GitLabDiffSource.mrPath(repo, prId) + "/notes";
        JsonNode created = client.postJson(path, Map.of("body", bodyMd));
        String id = requireId(created.path("id"), path);
        return new CommentRef(id, new ThreadRef(id), CommentKind.SUMMARY);
    }

    @Override
    public CommentRef postInline(RepoRef repo, long prId, DiffRefs refs, InlineAnchor anchor, String bodyMd) {
        Map<String, Object> position = new HashMap<>();
        position.put("position_type", "text");
        position.put("base_sha", refs.baseSha());
        position.put("start_sha", refs.startSha());
        position.put("head_sha", refs.headSha());
        position.put("old_path", anchor.srcPath());
        position.put("new_path", anchor.path());
        // Only the side(s) the line exists on are sent — GitLab 400s on a bad combo.
        if (anchor.oldLine() != null) {
            position.put("old_line", anchor.oldLine());
        }
        if (anchor.newLine() != null) {
            position.put("new_line", anchor.newLine());
        }

        String path = GitLabDiffSource.mrPath(repo, prId) + "/discussions";
        JsonNode created = client.postJson(path, Map.of("body", bodyMd, "position", position));
        String discussionId = requireId(created.path("id"), path);
        String noteId = firstNoteId(created, discussionId);
        return new CommentRef(noteId, new ThreadRef(discussionId), CommentKind.INLINE);
    }

    @Override
    public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
        String path = GitLabDiffSource.mrPath(repo, prId) + "/discussions/" + thread.value() + "/notes";
        JsonNode created = client.postJson(path, Map.of("body", bodyMd));
        return new CommentRef(requireId(created.path("id"), path), thread, CommentKind.REPLY);
    }

    @Override
    public Author getPullRequestAuthor(RepoRef repo, long prId) {
        JsonNode user = client.getJson(GitLabDiffSource.mrPath(repo, prId)).path("author");
        String username = user.path("username").asText("");
        return Author.of(user.path("id").asText(""), username, user.path("name").asText(username));
    }

    /** A discussion carries its notes; the first note's id is the reply/idempotency handle. */
    private static String firstNoteId(JsonNode discussion, String fallback) {
        JsonNode note = discussion.path("notes").path(0).path("id");
        return note.isMissingNode() || note.asText("").isBlank() ? fallback : note.asText();
    }

    /** A 2xx without an id must not flow an empty key into the idempotency store. */
    private static String requireId(JsonNode idNode, String path) {
        String id = idNode.isMissingNode() ? "" : idNode.asText("");
        if (id.isBlank()) {
            throw new GitLabApiException(200, "POST", path, "2xx response carried no id");
        }
        return id;
    }
}

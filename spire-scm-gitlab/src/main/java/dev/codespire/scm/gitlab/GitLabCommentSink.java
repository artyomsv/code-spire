package dev.codespire.scm.gitlab;

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
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GitLab write adapter (SCM-MAPPING §4-§6). The PR summary is a merge-request
 * NOTE; an inline comment opens a DISCUSSION anchored by a {@code position}
 * carrying all three {@link DiffRefs} SHAs plus the old/new path and line (a
 * wrong line/side combination is rejected with HTTP 400). {@link ThreadRef}
 * carries the {@code discussion_id} — replies POST to
 * {@code .../discussions/{discussion_id}/notes}.
 */
public class GitLabCommentSink implements CommentSink, ThreadSource {

    // Bounds thread re-fetch on a pathological MR (100 notes/page × pages).
    private static final int MAX_THREAD_PAGES = 20;
    private static final System.Logger LOG = System.getLogger(GitLabCommentSink.class.getName());

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
        String discussionPath = GitLabDiffSource.mrPath(repo, prId) + "/discussions/" + thread.value() + "/notes";
        try {
            JsonNode created = client.postJson(discussionPath, Map.of("body", bodyMd));
            return new CommentRef(requireId(created.path("id"), discussionPath), thread, CommentKind.REPLY);
        } catch (GitLabApiException notADiscussion) {
            if (!notADiscussion.isNotFound()) {
                throw notADiscussion;
            }
            // The ref is a plain note id (a summary conversation), not a discussion_id — reply is a new MR note.
            String notesPath = GitLabDiffSource.mrPath(repo, prId) + "/notes";
            JsonNode created = client.postJson(notesPath, Map.of("body", bodyMd));
            return new CommentRef(requireId(created.path("id"), notesPath), thread, CommentKind.REPLY);
        }
    }

    /**
     * A discussion's notes each carry {@code resolvable}/{@code resolved} flags. If every
     * resolvable note is already resolved, a human beat us to it (ALREADY_RESOLVED, no PUT);
     * otherwise resolve the whole discussion in one call.
     */
    @Override
    public ThreadResolution resolveThread(RepoRef repo, long prId, ThreadRef thread) {
        String path = GitLabDiffSource.mrPath(repo, prId) + "/discussions/" + thread.value();
        JsonNode discussion = client.getJson(path);
        boolean anyUnresolved = false;
        for (JsonNode note : discussion.path("notes")) {
            if (note.path("resolvable").asBoolean(false) && !note.path("resolved").asBoolean(false)) {
                anyUnresolved = true;
            }
        }
        if (!anyUnresolved) {
            return ThreadResolution.ALREADY_RESOLVED;
        }
        client.putJson(path, Map.of("resolved", true));
        return ThreadResolution.RESOLVED_NOW;
    }

    /** In-place summary rewrite on a re-review — the summary is a merge-request note. */
    @Override
    public CommentRef updateComment(RepoRef repo, long prId, String commentId, String bodyMd) {
        String path = GitLabDiffSource.mrPath(repo, prId) + "/notes/" + commentId;
        client.putJson(path, Map.of("body", bodyMd));
        return new CommentRef(commentId, new ThreadRef(commentId), CommentKind.SUMMARY);
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

    @Override
    public ThreadTranscript fetchThread(RepoRef repo, long prId, ThreadRef thread) {
        String botUsername = botUsername();
        String discussionPath = GitLabDiffSource.mrPath(repo, prId) + "/discussions/" + thread.value();
        try {
            return discussionTranscript(client.getJson(discussionPath), thread, botUsername);
        } catch (GitLabApiException notADiscussion) {
            if (!notADiscussion.isNotFound()) {
                throw notADiscussion;
            }
            return noteTailTranscript(repo, prId, thread, botUsername);
        }
    }

    /** A discussion carries its notes and (for a diff discussion) the code anchor on the first note's position. */
    private static ThreadTranscript discussionTranscript(JsonNode discussion, ThreadRef thread, String botUsername) {
        String path = null;
        int line = 0;
        String commit = null;
        List<ThreadMessage> messages = new ArrayList<>();
        for (JsonNode note : discussion.path("notes")) {
            if (note.path("system").asBoolean(false)) {
                continue; // state-change notes are not conversation turns
            }
            JsonNode position = note.path("position");
            if (path == null && position.isObject()) {
                path = position.path("new_path").asText(null);
                line = position.path("new_line").asInt(0);
                commit = nullIfBlank(position.path("head_sha").asText(""));
            }
            messages.add(toMessage(note, botUsername));
        }
        return new ThreadTranscript(thread, path, line, commit, messages);
    }

    /** Fallback for a plain-note ref (a summary conversation): the MR notes tail from the ref onward, no anchor. */
    private ThreadTranscript noteTailTranscript(RepoRef repo, long prId, ThreadRef thread, String botUsername) {
        String root = thread.value();
        List<ThreadMessage> messages = new ArrayList<>();
        boolean pastRoot = false;
        for (int page = 1; page <= MAX_THREAD_PAGES; page++) {
            JsonNode notes = client.getJson(GitLabDiffSource.mrPath(repo, prId) + "/notes?per_page=100&page=" + page);
            int count = 0;
            for (JsonNode note : notes) {
                count++;
                if (!pastRoot) {
                    if (!root.equals(note.path("id").asText())) {
                        continue;
                    }
                    pastRoot = true;
                }
                if (note.path("system").asBoolean(false)) {
                    continue;
                }
                messages.add(toMessage(note, botUsername));
            }
            if (count < 100) {
                break;
            }
            if (page == MAX_THREAD_PAGES) {
                LOG.log(System.Logger.Level.WARNING,
                        "thread " + root + " transcript may be truncated after " + MAX_THREAD_PAGES + " pages");
            }
        }
        return new ThreadTranscript(thread, null, 0, null, messages);
    }

    private static ThreadMessage toMessage(JsonNode note, String botUsername) {
        String username = note.path("author").path("username").asText("");
        return new ThreadMessage(username, note.path("body").asText("").trim(),
                !username.isEmpty() && username.equals(botUsername));
    }

    /** Best-effort token-owner username to label the bot's own turns; a transient failure degrades to "". */
    private String botUsername() {
        try {
            return client.getJson("/user").path("username").asText("");
        } catch (RuntimeException transientFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "botUsername lookup failed — messages will not be attributed to the bot", transientFailure);
            return "";
        }
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

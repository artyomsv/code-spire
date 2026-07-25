package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.Side;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bitbucket Cloud write adapter (SCM-MAPPING §4-§6). Inline anchoring uses the
 * flat {@code inline:{path, to|from}} model — {@code to} = NEW-side line,
 * {@code from} = OLD-side line, mutually exclusive ({@code from} wins if both
 * are sent, so we send exactly one). Replies inherit the parent's anchor and
 * must NOT resend {@code inline}.
 */
public class BitbucketCloudCommentSink implements CommentSink, ThreadSource {

    private static final int MAX_THREAD_PAGES = 20;
    private static final System.Logger LOG = System.getLogger(BitbucketCloudCommentSink.class.getName());

    private final BitbucketCloudClient client;
    private final String brokeredBotAccountId;

    public BitbucketCloudCommentSink(BitbucketCloudClient client) {
        this(client, null);
    }

    /**
     * @param botAccountId the registry's bot account id, so a thread transcript can be attributed
     *                     without a live {@code GET /user} — the call Bitbucket's scoped tokens reject
     *                     unless they carry {@code read:account}. Null falls back to that lookup.
     */
    public BitbucketCloudCommentSink(BitbucketCloudClient client, String botAccountId) {
        this.client = client;
        this.brokeredBotAccountId = botAccountId;
    }

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_CLOUD;
    }

    @Override
    public CommentRef postSummary(RepoRef repo, long prId, String bodyMd) {
        JsonNode created = client.postJson(commentsPath(repo, prId),
                Map.of("content", Map.of("raw", bodyMd)));
        String id = requireCommentId(created, commentsPath(repo, prId));
        return new CommentRef(id, new ThreadRef(id), CommentKind.SUMMARY);
    }

    @Override
    public CommentRef postInline(RepoRef repo, long prId, String headCommit, InlineAnchor anchor, String bodyMd) {
        // Bitbucket anchors by path+line alone; it needs no commit SHA at all.
        Map<String, Object> inline = anchor.side() == Side.OLD
                ? Map.of("path", anchor.path(), "from", anchor.oldLine())
                : Map.of("path", anchor.path(), "to", anchor.newLine());
        JsonNode created = client.postJson(commentsPath(repo, prId),
                Map.of("content", Map.of("raw", bodyMd), "inline", inline));
        String id = requireCommentId(created, commentsPath(repo, prId));
        return new CommentRef(id, new ThreadRef(id), CommentKind.INLINE);
    }

    @Override
    public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
        JsonNode created = client.postJson(commentsPath(repo, prId),
                Map.of("content", Map.of("raw", bodyMd),
                        "parent", Map.of("id", parseCommentId(thread, repo, prId))));
        return new CommentRef(requireCommentId(created, commentsPath(repo, prId)), thread, CommentKind.REPLY);
    }

    /** A 2xx without an id must not flow an empty key into the idempotency store. */
    private static String requireCommentId(JsonNode created, String path) {
        String id = created.hasNonNull("id") ? created.get("id").asText() : "";
        if (id.isBlank()) {
            throw new BitbucketApiException(200, "POST", path, "2xx response carried no comment id");
        }
        return id;
    }

    /** Bitbucket parent ids are numeric; a non-numeric ThreadRef stays inside the adapter contract. */
    private static long parseCommentId(ThreadRef thread, RepoRef repo, long prId) {
        try {
            return Long.parseLong(thread.value());
        } catch (NumberFormatException e) {
            throw new BitbucketApiException(400, "POST", "/repositories/" + repo.workspace() + "/"
                    + repo.slug() + "/pullrequests/" + prId + "/comments",
                    "thread ref is not a numeric Bitbucket comment id");
        }
    }

    /** Rewrite an existing comment's body in place (summary update on re-reviews). */
    @Override
    public CommentRef updateComment(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
        // Bitbucket makes a comment its own thread root, so the thread ref is the comment to PUT.
        String commentId = thread.value();
        String path = "/repositories/" + repo.full() + "/pullrequests/" + prId
                + "/comments/" + commentId;
        client.putJson(path, Map.of("content", Map.of("raw", bodyMd)));
        return new CommentRef(commentId, new ThreadRef(commentId), CommentKind.SUMMARY);
    }

    /**
     * Resolve a PR comment thread. Bitbucket Cloud gained this API
     * ({@code POST .../comments/{id}/resolve}), so a fixed finding shows "Resolved", not just the
     * SCM's auto-"Outdated" badge. A GET first distinguishes a human (or a prior redelivery) who
     * already resolved it — {@code resolution} present → ALREADY_RESOLVED, so the caller skips the
     * reply — from a thread we resolve now.
     */
    @Override
    public ThreadResolution resolveThread(RepoRef repo, long prId, ThreadRef thread) {
        String commentPath = prPath(repo, prId) + "/comments/" + thread.value();
        if (client.getJson(commentPath).path("resolution").isObject()) {
            return ThreadResolution.ALREADY_RESOLVED;
        }
        client.postJson(commentPath + "/resolve", Map.of());
        return ThreadResolution.RESOLVED_NOW;
    }

    @Override
    public Author getPullRequestAuthor(RepoRef repo, long prId) {
        JsonNode author = client.getJson(prPath(repo, prId)).path("author");
        return Author.of(
                author.path("account_id").asText(""),
                author.path("nickname").asText(""),
                author.path("display_name").asText(""));
    }

    private String prPath(RepoRef repo, long prId) {
        return "/repositories/" + repo.workspace() + "/" + repo.slug() + "/pullrequests/" + prId;
    }

    private String commentsPath(RepoRef repo, long prId) {
        return prPath(repo, prId) + "/comments";
    }

    @Override
    public ThreadTranscript fetchThread(RepoRef repo, long prId, ThreadRef thread) {
        String botAccountId = botAccountId();
        List<JsonNode> all = new ArrayList<>();
        for (int page = 1; page <= MAX_THREAD_PAGES; page++) {
            JsonNode body = client.getJson(commentsPath(repo, prId) + "?pagelen=100&page=" + page);
            JsonNode values = body.path("values");
            values.forEach(all::add);
            if (body.path("next").isMissingNode() || body.path("next").asText("").isBlank()) {
                break;
            }
            if (page == MAX_THREAD_PAGES) {
                LOG.log(System.Logger.Level.WARNING,
                        "thread " + thread.value() + " transcript may be truncated after " + MAX_THREAD_PAGES + " pages");
            }
        }
        return subtree(all, thread, botAccountId);
    }

    /** Collect the root plus every descendant (linked by parent.id), preserving list order; anchor from the root. */
    private static ThreadTranscript subtree(List<JsonNode> all, ThreadRef thread, String botAccountId) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode c : all) {
            byId.put(c.path("id").asText(), c);
        }
        // A reply can land on the bot's own answer (Bitbucket threads by immediate parent), so the
        // caller's ref may be mid-thread. Walk up to the top-level comment so the transcript is the
        // WHOLE conversation (parity with GitHub/GitLab), not just the branch under the replied-to comment.
        String root = rootOf(thread.value(), byId);
        String path = null;
        int line = 0;
        List<ThreadMessage> messages = new ArrayList<>();
        for (JsonNode c : all) {
            if (!inThread(c, root, byId)) {
                continue;
            }
            if (root.equals(c.path("id").asText()) && c.path("inline").isObject()) {
                path = c.path("inline").path("path").asText(null);
                line = c.path("inline").path("to").asInt(c.path("inline").path("from").asInt(0));
            }
            messages.add(toMessage(c, botAccountId));
        }
        return new ThreadTranscript(thread, path, line, null, messages);
    }

    /** Walk parent.id up to the thread's top-level comment (bounded by the map size); returns the
     *  input id when it has no parent, or when the parent paged out of the fetched set. */
    private static String rootOf(String commentId, Map<String, JsonNode> byId) {
        String id = commentId;
        for (int hop = 0; hop <= byId.size(); hop++) {
            JsonNode node = byId.get(id);
            if (node == null) {
                return id; // not in the fetched set — treat as its own root
            }
            JsonNode parent = node.path("parent").path("id");
            if (parent.isMissingNode() || parent.isNull()) {
                return id; // no parent → top-level comment
            }
            id = parent.asText();
        }
        return id;
    }

    /** True if the comment is the root or chains up to it through parent.id (bounded by the map size). */
    private static boolean inThread(JsonNode comment, String root, Map<String, JsonNode> byId) {
        String id = comment.path("id").asText();
        for (int hop = 0; hop <= byId.size(); hop++) {
            if (root.equals(id)) {
                return true;
            }
            JsonNode ancestor = byId.get(id);
            if (ancestor == null) {
                return false;           // parent paged out of the fetched set — treat as a different thread
            }
            JsonNode parent = ancestor.path("parent").path("id");
            if (parent.isMissingNode() || parent.isNull()) {
                return false;
            }
            id = parent.asText();
        }
        return false;
    }

    private static ThreadMessage toMessage(JsonNode c, String botAccountId) {
        String accountId = c.path("user").path("account_id").asText("");
        String display = c.path("user").path("nickname").asText(accountId);
        return new ThreadMessage(display, c.path("content").path("raw").asText("").trim(),
                !accountId.isEmpty() && accountId.equals(botAccountId));
    }

    /** The bot's account id used to label its own turns: the brokered one when the orchestrator supplied
     *  it, else a best-effort token-owner lookup whose failure degrades to "" (nothing attributed). */
    private String botAccountId() {
        if (brokeredBotAccountId != null && !brokeredBotAccountId.isBlank()) {
            return brokeredBotAccountId;
        }
        try {
            return client.getJson("/user").path("account_id").asText("");
        } catch (RuntimeException transientFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "botAccountId lookup failed — messages will not be attributed to the bot", transientFailure);
            return "";
        }
    }
}

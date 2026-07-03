package dev.codespire.scm.bitbucket;

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
 * Bitbucket Cloud write adapter (SCM-MAPPING §4-§6). Inline anchoring uses the
 * flat {@code inline:{path, to|from}} model — {@code to} = NEW-side line,
 * {@code from} = OLD-side line, mutually exclusive ({@code from} wins if both
 * are sent, so we send exactly one). Replies inherit the parent's anchor and
 * must NOT resend {@code inline}.
 */
public class BitbucketCloudCommentSink implements CommentSink {

    private final BitbucketCloudClient client;

    public BitbucketCloudCommentSink(BitbucketCloudClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_CLOUD;
    }

    @Override
    public CommentRef postSummary(RepoRef repo, long prId, String bodyMd) {
        JsonNode created = client.postJson(commentsPath(repo, prId),
                Map.of("content", Map.of("raw", bodyMd)));
        String id = created.path("id").asText();
        return new CommentRef(id, new ThreadRef(id), CommentKind.SUMMARY);
    }

    @Override
    public CommentRef postInline(RepoRef repo, long prId, DiffRefs refs, InlineAnchor anchor, String bodyMd) {
        // Bitbucket needs no SHAs (refs feeds GitLab/GitHub adapters).
        Map<String, Object> inline = anchor.side() == Side.OLD
                ? Map.of("path", anchor.path(), "from", anchor.oldLine())
                : Map.of("path", anchor.path(), "to", anchor.newLine());
        JsonNode created = client.postJson(commentsPath(repo, prId),
                Map.of("content", Map.of("raw", bodyMd), "inline", inline));
        String id = created.path("id").asText();
        return new CommentRef(id, new ThreadRef(id), CommentKind.INLINE);
    }

    @Override
    public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
        JsonNode created = client.postJson(commentsPath(repo, prId),
                Map.of("content", Map.of("raw", bodyMd),
                        "parent", Map.of("id", Long.parseLong(thread.value()))));
        return new CommentRef(created.path("id").asText(), thread, CommentKind.REPLY);
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
}

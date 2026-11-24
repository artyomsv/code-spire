package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.CommentSink.ThreadResolution;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;

import java.util.HashMap;
import java.util.Map;

/**
 * Review-thread resolution over GraphQL, the only way to reach a thread's resolution state (no REST equivalent).
 *
 * <p>Package-private: {@link GitHubCommentSink} is the port implementation and delegates here, so the GraphQL walk
 * does not grow that class past what a reader can hold.
 */
final class GitHubThreadResolver {

    private static final System.Logger LOG = System.getLogger(GitHubThreadResolver.class.getName());

    /** Bounds the walk on a pathological PR (100 threads/page × pages), as the sink bounds its transcripts. */
    private static final int MAX_THREAD_PAGES = 20;

    // Paginated by review thread (not comment) — a PR with many threads walks pages the
    // same way fetchThread walks REST comment pages.
    private static final String THREADS_QUERY = """
            query($owner:String!,$name:String!,$pr:Int!,$cursor:String){
              repository(owner:$owner,name:$name){ pullRequest(number:$pr){
                reviewThreads(first:100,after:$cursor){
                  pageInfo{hasNextPage endCursor}
                  nodes{id isResolved comments(first:1){nodes{databaseId}}}}}}}""";

    private static final String RESOLVE_MUTATION = """
            mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{isResolved}}}""";

    private final GitHubClient client;

    GitHubThreadResolver(GitHubClient client) {
        this.client = client;
    }

    /**
     * Walks review threads by GraphQL cursor looking for the one whose FIRST (root)
     * comment's {@code databaseId} equals {@code thread}'s value. A match that is already
     * resolved (a human beat us to it) is ALREADY_RESOLVED with no mutation. If NO thread
     * matches — the comment was deleted, or the finding was re-posted under a new id so the
     * carried thread ref is stale — we CANNOT resolve it, so degrade to UNSUPPORTED (reply-only)
     * rather than falsely reporting {@code resolved:true} while doing nothing. Same when the
     * pagination cap is exhausted with pages remaining.
     */
    ThreadResolution resolve(RepoRef repo, long prId, ThreadRef thread) {
        String cursor = null;
        for (int page = 0; page < MAX_THREAD_PAGES; page++) {
            JsonNode threads = client.postGraphQl(THREADS_QUERY, threadsQueryVariables(repo, prId, cursor))
                    .path("repository").path("pullRequest").path("reviewThreads");
            ThreadResolution found = resolveInPage(threads.path("nodes"), thread);
            if (found != null) {
                return found;
            }
            if (!threads.path("pageInfo").path("hasNextPage").asBoolean(false)) {
                // No thread matched this ref (deleted, or re-posted under a new id). Don't fake
                // success — degrade to reply-only so the outcome is an honest resolved:false.
                LOG.log(System.Logger.Level.WARNING, "resolveThread " + thread.value()
                        + ": no matching review thread — degrading to reply-only");
                return ThreadResolution.UNSUPPORTED;
            }
            cursor = threads.path("pageInfo").path("endCursor").asText(null);
        }
        LOG.log(System.Logger.Level.WARNING, "resolveThread " + thread.value() + ": pagination cap ("
                + MAX_THREAD_PAGES + " pages) exhausted — degrading to reply-only");
        return ThreadResolution.UNSUPPORTED;
    }

    private ThreadResolution resolveInPage(JsonNode nodes, ThreadRef thread) {
        for (JsonNode node : nodes) {
            String rootCommentId = node.path("comments").path("nodes").path(0).path("databaseId").asText("");
            if (!thread.value().equals(rootCommentId)) {
                continue;
            }
            if (node.path("isResolved").asBoolean(false)) {
                return ThreadResolution.ALREADY_RESOLVED;
            }
            client.postGraphQl(RESOLVE_MUTATION, Map.of("id", node.path("id").asText()));
            return ThreadResolution.RESOLVED_NOW;
        }
        return null;
    }

    private static Map<String, Object> threadsQueryVariables(RepoRef repo, long prId, String cursor) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", repo.workspace());
        vars.put("name", repo.slug());
        vars.put("pr", (int) prId);
        vars.put("cursor", cursor);
        return vars;
    }
}

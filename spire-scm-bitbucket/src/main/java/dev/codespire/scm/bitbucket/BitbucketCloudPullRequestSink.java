package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.PullRequestSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.scm.RepoRef;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Opens a pull request on Bitbucket Cloud (SCM-MAPPING.md §8).
 *
 * <p><b>Bitbucket does not refuse a duplicate either</b>, so as on GitLab the find-first call is the
 * only thing between a redelivered record and two pull requests — not a second line of defence.
 *
 * <p><b>Its request body nests where the other two are flat.</b> A branch is
 * {@code source.branch.name}, not {@code head}; the number comes back as {@code id} and the link as
 * {@code links.html.href}. A missing nested node reads back through Jackson's {@code path(...)} as an
 * empty node rather than as an error, so a wrong nesting produces a zero and a blank URL rather than
 * a failure — which is why both are checked before a ref is built.
 */
public class BitbucketCloudPullRequestSink implements PullRequestSink {

    /** Bitbucket's wording when the source has nothing the destination lacks. */
    private static final String NO_CHANGES = "no changes";

    /** And its wording for a duplicate, which this forge does not refuse — see the class javadoc. */
    private static final String ALREADY_EXISTS = "already exists";

    private final BitbucketCloudClient client;

    public BitbucketCloudPullRequestSink(BitbucketCloudClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_CLOUD;
    }

    @Override
    public PullRequestRef open(RepoRef repo, NewPullRequest request) {
        Optional<PullRequestRef> existing = findByHead(repo, request.headBranch());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return read(client.postJson(pullRequestsPath(repo), Map.of(
                    "title", request.title(),
                    "description", request.bodyMd(),
                    "source", Map.of("branch", Map.of("name", request.headBranch())),
                    "destination", Map.of("branch", Map.of("name", request.baseBranch())))),
                    pullRequestsPath(repo));
        } catch (BitbucketApiException e) {
            return recover(repo, request, e);
        }
    }

    @Override
    public Optional<PullRequestRef> findByHead(RepoRef repo, String headBranch) {
        if (headBranch == null || headBranch.isBlank()) {
            throw new IllegalArgumentException("a source branch is needed to look for its pull request");
        }
        // Bitbucket filters through its own query language rather than through named parameters, and
        // a branch name may carry a slash -- so the whole expression is encoded, quotes included.
        String query = "source.branch.name=\"" + headBranch + "\" AND state=\"OPEN\"";
        String path = pullRequestsPath(repo) + "?pagelen=1&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode found = client.getJson(path).path("values");
        if (!found.isArray() || found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(read(found.get(0), path));
    }

    private PullRequestRef recover(RepoRef repo, NewPullRequest request, BitbucketApiException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (detail.contains(NO_CHANGES)) {
            throw new NothingToPropose("the branch " + request.headBranch() + " has no changes "
                    + request.baseBranch() + " does not already have, so there is nothing to review", e);
        }
        if (detail.contains(ALREADY_EXISTS)) {
            return findByHead(repo, request.headBranch()).orElseThrow(() -> e);
        }
        throw e;
    }

    private static PullRequestRef read(JsonNode node, String path) {
        long number = node.path("id").asLong(0);
        String url = node.path("links").path("html").path("href").asText("");
        if (number <= 0 || url.isBlank()) {
            throw new BitbucketApiException(200, "POST", path,
                    "response carried no pull request id or html link");
        }
        return new PullRequestRef(number, url);
    }

    private static String pullRequestsPath(RepoRef repo) {
        return "/repositories/" + repo.workspace() + "/" + repo.slug() + "/pullrequests";
    }
}

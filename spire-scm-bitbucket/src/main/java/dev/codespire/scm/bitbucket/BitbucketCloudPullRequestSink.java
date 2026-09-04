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
 *
 * <p><b>And it filters through a query LANGUAGE rather than through named parameters</b>, which is
 * the one place in these three adapters where a value is interpolated into a syntax instead of being
 * encoded as a parameter. See {@link #findByHead}.
 */
public class BitbucketCloudPullRequestSink implements PullRequestSink {

    /**
     * Bitbucket's full wording, not its most generic sub-phrase.
     *
     * <p>This was {@code "no changes"} until a review pointed out that two generic words matched
     * against a 500-character raw body snippet will eventually match something else — and a false
     * match here reports a real failure as "the agent changed nothing", which is the one direction
     * the port exists to prevent. The full phrase plus the status is much harder to hit by accident.
     */
    private static final String NO_CHANGES = "there are no changes to be pulled";

    /** The status that wording must also carry; Bitbucket answers a validation refusal as 400. */
    private static final int BAD_REQUEST = 400;

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
        return findByHead(repo, request.headBranch(), request.baseBranch())
                .orElseGet(() -> create(repo, request));
    }

    private PullRequestRef create(RepoRef repo, NewPullRequest request) {
        String path = pullRequestsPath(repo);
        try {
            return read(client.postJson(path, Map.of(
                    "title", request.title(),
                    "description", request.bodyMd(),
                    "source", Map.of("branch", Map.of("name", request.headBranch())),
                    "destination", Map.of("branch", Map.of("name", request.baseBranch())))),
                    "POST", path);
        } catch (BitbucketApiException e) {
            return recover(repo, request, e);
        }
    }

    /**
     * <b>The branch names go into a query LANGUAGE, so they are refused rather than escaped.</b>
     *
     * <p>A double quote is a legal character in a git refname — {@code git check-ref-format} does not
     * forbid it — and {@code URLEncoder} protects the transport, not Bitbucket's query parser: the
     * quote arrives decoded and terminates the string early. {@code x" OR state="OPEN} would widen
     * the clause to the repository's first open pull request, which the caller then records as this
     * run's delivery. That is not theoretical here: {@code /fix} reads the source branch from the
     * webhook projection, and a pull-request author controls it.
     *
     * <p>Refused rather than escaped because Bitbucket's own escaping rule for this language is not
     * something this repository has verified, and a wrong escape is indistinguishable from none.
     */
    @Override
    public Optional<PullRequestRef> findByHead(RepoRef repo, String headBranch, String baseBranch) {
        requireBranch(headBranch, "source");
        requireBranch(baseBranch, "destination");
        // BOTH branches: a pull request is unique per (source, destination) pair, and Bitbucket will
        // hold two open from one source onto different destinations.
        String query = "source.branch.name=\"" + headBranch + "\""
                + " AND destination.branch.name=\"" + baseBranch + "\""
                + " AND state=\"OPEN\"";
        String path = pullRequestsPath(repo) + "?pagelen=1&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode found = client.getJson(path).path("values");
        if (!found.isArray() || found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(read(found.get(0), "GET", path));
    }

    /** See the GitHub adapter for the full argument; only the nothing-to-propose wording is matched. */
    private PullRequestRef recover(RepoRef repo, NewPullRequest request, BitbucketApiException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (e.status() == BAD_REQUEST && detail.contains(NO_CHANGES)) {
            throw new NothingToPropose("the branch " + request.headBranch() + " has no changes "
                    + request.baseBranch() + " does not already have, so there is nothing to review", e);
        }
        try {
            return findByHead(repo, request.headBranch(), request.baseBranch()).orElseThrow(() -> e);
        } catch (BitbucketApiException lookupFailed) {
            if (lookupFailed != e) {
                e.addSuppressed(lookupFailed);
            }
            throw e;
        }
    }

    private static PullRequestRef read(JsonNode node, String method, String path) {
        long number = node.path("id").asLong(0);
        String url = node.path("links").path("html").path("href").asText("");
        if (number <= 0 || url.isBlank()) {
            throw new BitbucketApiException(200, method, path,
                    "response carried no pull request id or html link");
        }
        return new PullRequestRef(number, url);
    }

    private static void requireBranch(String branch, String which) {
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException(
                    "a " + which + " branch is needed to look for its pull request");
        }
        if (branch.indexOf('"') >= 0 || branch.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("a branch name carrying a quote or a backslash cannot "
                    + "be placed in Bitbucket's query language safely: " + branch);
        }
    }

    private static String pullRequestsPath(RepoRef repo) {
        return "/repositories/" + repo.workspace() + "/" + repo.slug() + "/pullrequests";
    }
}

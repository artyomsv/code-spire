package dev.codespire.scm.github;

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
 * Opens a pull request on GitHub (SCM-MAPPING.md §8).
 *
 * <p>Takes the same {@link GitHubClient} every other adapter in this module takes, and deliberately
 * does not build its own: {@code RedirectHandlingHasOneHomeTest} allows exactly one hand-rolled
 * redirect policy in the repository and it lives in that client. A second HTTP client here would
 * fail the build, which is the check working.
 *
 * <p><b>The client this is handed must carry the FACTORY-role account's token.</b> That is the
 * composition root's choice and cannot be checked from here — the client holds an opaque bearer
 * token and no identity.
 */
public class GitHubPullRequestSink implements PullRequestSink {

    /**
     * GitHub's own wording for "the head has no commits the base lacks".
     *
     * <p>Matched on the message because GitHub gives it the same 422 as every other validation
     * failure — there is no code to switch on. Lower-cased at the comparison, since the casing has
     * changed at least once in the API's history and a run that produced nothing would then be
     * reported as a permission fault.
     */
    private static final String NO_COMMITS = "no commits between";

    /**
     * The status the wording must ALSO carry.
     *
     * <p>The wording alone decided this until a review named the asymmetry. An unmatched failure
     * degrades SAFELY — it stays a fault, which is what the port promises. A falsely matched one
     * degrades in the direction the port exists to prevent, reporting a run as "the agent changed
     * nothing" when the forge refused for another reason entirely. Since the match runs against a
     * 500-character raw body snippet, an HTML error page from a proxy in front of a self-hosted
     * instance is scanned by the same substring test as a real validation response; requiring the
     * status too costs nothing and removes that whole class.
     */
    private static final int UNPROCESSABLE = 422;

    private final GitHubClient client;

    public GitHubPullRequestSink(GitHubClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.GITHUB;
    }

    @Override
    public PullRequestRef open(RepoRef repo, NewPullRequest request) {
        // Find FIRST, because the record that triggers this is redelivered on every consumer
        // restart and by then the branch is already pushed. GitHub happens to refuse the duplicate,
        // but the other two forges do not, so the guard belongs here rather than in one forge's
        // behaviour -- and a refusal would be an exception where the caller needs a number.
        return findByHead(repo, request.headBranch(), request.baseBranch())
                .orElseGet(() -> create(repo, request));
    }

    private PullRequestRef create(RepoRef repo, NewPullRequest request) {
        String path = pullsPath(repo);
        try {
            return read(client.postJson(path, Map.of(
                    "title", request.title(),
                    "head", request.headBranch(),
                    "base", request.baseBranch(),
                    "body", request.bodyMd())), "POST", path);
        } catch (GitHubApiException e) {
            return recover(repo, request, e);
        }
    }

    @Override
    public Optional<PullRequestRef> findByHead(RepoRef repo, String headBranch, String baseBranch) {
        requireBranch(headBranch, "head");
        requireBranch(baseBranch, "base");
        // BOTH branches, because an open pull request is unique per (head, base) pair -- GitHub's own
        // duplicate refusal fires only when both match. Filtering on the head alone is WIDER than the
        // rule the forge enforces: it would answer a pull request aimed at another base, which the
        // caller then records as this run's delivery while the one that should exist never opens.
        //
        // state=open, because a merged pull request for a reused branch name must not suppress a new
        // one. GitHub's default is `open`, and relying on a default the API could change is how a
        // guard stops guarding without anyone editing it.
        String path = pullsPath(repo) + "?state=open&per_page=1"
                + "&base=" + encode(baseBranch)
                + "&head=" + encode(repo.workspace() + ":" + headBranch);
        JsonNode found = client.getJson(path);
        if (!found.isArray() || found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(read(found.get(0), "GET", path));
    }

    /**
     * What a refused create really meant: an outcome, someone else's pull request, or a fault.
     *
     * <p><b>Only the nothing-to-propose wording is matched.</b> An earlier version also matched
     * GitHub's "a pull request already exists" text, which was fragile for no benefit — that case is
     * identifiable by BEHAVIOUR instead. Any other refusal might be a delivery that raced us between
     * the lookup and the create, so this asks the forge whether one exists now. If it does, a race
     * was the cause whatever the forge called it; if it does not, the original failure is what an
     * operator must read.
     *
     * <p>The lookup's own fault is attached as suppressed rather than thrown. An earlier version let
     * a 503 on the re-read REPLACE the original 422, so the operator saw a failed GET and never
     * learned the create had been refused.
     */
    private PullRequestRef recover(RepoRef repo, NewPullRequest request, GitHubApiException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (e.status() == UNPROCESSABLE && detail.contains(NO_COMMITS)) {
            throw new NothingToPropose("the branch " + request.headBranch() + " has no commits that "
                    + request.baseBranch() + " does not already have, so there is nothing to review", e);
        }
        try {
            return findByHead(repo, request.headBranch(), request.baseBranch()).orElseThrow(() -> e);
        } catch (GitHubApiException lookupFailed) {
            if (lookupFailed != e) {
                e.addSuppressed(lookupFailed);
            }
            throw e;
        }
    }

    /**
     * A 2xx that carries no number is a failure, not a pull request.
     *
     * <p>The same rule this module already applies to comment ids: an absent field reads back as a
     * primitive zero, and {@code factory_run.pr_id} would then hold a row that addresses nothing.
     *
     * @param method the verb that produced this node. Named rather than assumed, because both the
     *     create and the lookup land here and an operator reading "POST" for a malformed GET response
     *     goes looking for a request that was never made
     */
    private static PullRequestRef read(JsonNode node, String method, String path) {
        long number = node.path("number").asLong(0);
        String url = node.path("html_url").asText("");
        if (number <= 0 || url.isBlank()) {
            throw new GitHubApiException(200, method, path,
                    "response carried no pull request number or URL");
        }
        return new PullRequestRef(number, url);
    }

    private static void requireBranch(String branch, String which) {
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException(
                    "a " + which + " branch is needed to look for its pull request");
        }
    }

    private static String pullsPath(RepoRef repo) {
        return "/repos/" + repo.workspace() + "/" + repo.slug() + "/pulls";
    }

    /** The filters travel in a query string, and a branch name may legitimately contain a slash. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

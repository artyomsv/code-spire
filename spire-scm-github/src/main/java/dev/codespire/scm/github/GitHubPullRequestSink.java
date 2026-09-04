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
 * <p><b>The client this is handed must carry the FACTORY-role account's token, not the reviewer's.</b>
 * That is the caller's choice and cannot be checked from here — the client holds an opaque bearer
 * token and no identity. A branch pushed as one account with a pull request opened by another is a
 * pull request nobody can attribute, so the composition root resolves the machine account explicitly.
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

    /** And its wording for "you already opened this one", which the find-first path should preempt. */
    private static final String ALREADY_EXISTS = "a pull request already exists";

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
        Optional<PullRequestRef> existing = findByHead(repo, request.headBranch());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return read(client.postJson(pullsPath(repo), Map.of(
                    "title", request.title(),
                    "head", request.headBranch(),
                    "base", request.baseBranch(),
                    "body", request.bodyMd())), pullsPath(repo));
        } catch (GitHubApiException e) {
            return recover(repo, request, e);
        }
    }

    @Override
    public Optional<PullRequestRef> findByHead(RepoRef repo, String headBranch) {
        if (headBranch == null || headBranch.isBlank()) {
            throw new IllegalArgumentException("a head branch is needed to look for its pull request");
        }
        // state=open, because a merged pull request for a reused branch name must not suppress a new
        // one. GitHub's default is `open`, and relying on a default that the API could change is how
        // a guard stops guarding without anyone editing it.
        String path = pullsPath(repo) + "?state=open&per_page=1&head="
                + encode(repo.workspace() + ":" + headBranch);
        JsonNode found = client.getJson(path);
        if (!found.isArray() || found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(read(found.get(0), path));
    }

    /**
     * What a refused create really meant: an outcome, someone else's pull request, or a fault.
     *
     * <p><b>The already-exists 422 is reachable despite the find-first call above</b>, because two
     * deliveries can race between the read and the write. The loser must ANSWER the winner's pull
     * request rather than report a failure — the caller needs a number either way, and a second
     * delivery is not an error. So it re-reads. If the re-read finds nothing, the original
     * exception is raised: inventing a success for a pull request nobody can see would be worse
     * than saying the create failed.
     *
     * <p>Returns rather than throws where it can, so the value travels as a value. An earlier draft
     * carried the recovered ref INSIDE an exception, which is a return type wearing a disguise.
     */
    private PullRequestRef recover(RepoRef repo, NewPullRequest request, GitHubApiException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (detail.contains(NO_COMMITS)) {
            throw new NothingToPropose("the branch " + request.headBranch() + " has no commits that "
                    + request.baseBranch() + " does not already have, so there is nothing to review", e);
        }
        if (detail.contains(ALREADY_EXISTS)) {
            return findByHead(repo, request.headBranch()).orElseThrow(() -> e);
        }
        throw e;
    }

    /**
     * A 2xx that carries no number is a failure, not a pull request.
     *
     * <p>The same rule this module already applies to comment ids: an absent field reads back as a
     * primitive zero, and {@code factory_run.pr_id} would then hold a row that addresses nothing.
     */
    private static PullRequestRef read(JsonNode node, String path) {
        long number = node.path("number").asLong(0);
        String url = node.path("html_url").asText("");
        if (number <= 0 || url.isBlank()) {
            throw new GitHubApiException(200, "POST", path,
                    "response carried no pull request number or URL");
        }
        return new PullRequestRef(number, url);
    }

    private static String pullsPath(RepoRef repo) {
        return "/repos/" + repo.workspace() + "/" + repo.slug() + "/pulls";
    }

    /** The head filter travels in a query string, and a branch name may legitimately contain a slash. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

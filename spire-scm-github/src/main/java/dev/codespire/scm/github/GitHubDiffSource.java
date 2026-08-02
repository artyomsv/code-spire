package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.IdentitySource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.diff.UnifiedDiffParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.util.List;

/**
 * GitHub read adapter (SCM-MAPPING §2/§3). {@code workspace}=owner, {@code slug}=repo,
 * {@code prId}=the PR number. The diff comes from the same PR URL requested with the
 * {@code application/vnd.github.diff} media type (raw unified diff), parsed by the
 * shared {@link UnifiedDiffParser}. The returned Diff carries the reviewed commit;
 * the worker's stale-run pre-check (ADR-013) guarantees current == expected.
 */
public class GitHubDiffSource implements DiffSource, IdentitySource {

    private final GitHubClient client;

    public GitHubDiffSource(GitHubClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.GITHUB;
    }

    @Override
    public String apiHost() {
        return client.apiHost(); // github.com or a GitHub Enterprise host — keyed per instance
    }

    /** GET /user — the token owner (SCM-MAPPING §GitHub); {@code id} is the stable numeric account id. */
    @Override
    public Author whoami() {
        JsonNode user = client.getJson("/user");
        String login = user.path("login").asText("");
        return Author.of(user.path("id").asText(""), login, user.path("name").asText(login));
    }

    @Override
    public void assertRepoAccessible(RepoRef repo) {
        client.getJson("/repos/" + repo.full());
    }

    @Override
    public PullRequest fetchPullRequest(RepoRef repo, long prId) {
        JsonNode pr = client.getJson(prPath(repo, prId));
        JsonNode user = pr.path("user");
        String login = user.path("login").asText("");
        return new PullRequest(
                repo,
                prId,
                pr.path("title").asText(""),
                pr.path("body").asText(""),
                pr.path("head").path("ref").asText(""),
                pr.path("base").path("ref").asText(""),
                (pr.path("head").path("sha").asText("")),
                Author.of(user.path("id").asText(""), login, login),
                pr.path("html_url").asText(""));
    }

    @Override
    public Diff fetchDiff(RepoRef repo, long prId, String commit) {
        String diffText = client.getDiff(prPath(repo, prId));
        List<FilePatch> files = UnifiedDiffParser.parse(diffText);
        return new Diff(commit, files, false);
    }

    /** The reconciliation lens (prior head -> new head), same diff media type as the PR diff. */
    @Override
    public String fetchCompareDiff(RepoRef repo, String base, String head) {
        return client.getDiff("/repos/" + repo.full() + "/compare/" + base + "..." + head);
    }

    /**
     * {@code GET /repos/{owner}/{repo}/contents/{path}?ref={branch}} with the raw media type.
     *
     * <p>A 404 is the ordinary answer for a repository that has no such file, so it yields null rather
     * than an exception — the port's contract. Anything else is a real failure and propagates.
     */
    @Override
    public String fetchTextFileOnBranch(RepoRef repo, String branch, String path) {
        try {
            return client.getRaw("/repos/" + repo.full() + "/contents/" + path
                    + "?ref=" + URLEncoder.encode(branch, StandardCharsets.UTF_8));
        } catch (GitHubApiException e) {
            if (e.isNotFound()) {
                return null;
            }
            throw e;
        }
    }

    private String prPath(RepoRef repo, long prId) {
        return "/repos/" + repo.workspace() + "/" + repo.slug() + "/pulls/" + prId;
    }
}

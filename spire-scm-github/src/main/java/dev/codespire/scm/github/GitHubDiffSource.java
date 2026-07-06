package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.diff.UnifiedDiffParser;

import java.util.List;

/**
 * GitHub read adapter (SCM-MAPPING §2/§3). {@code workspace}=owner, {@code slug}=repo,
 * {@code prId}=the PR number. The diff comes from the same PR URL requested with the
 * {@code application/vnd.github.diff} media type (raw unified diff), parsed by the
 * shared {@link UnifiedDiffParser}. The returned Diff carries the reviewed commit;
 * the worker's stale-run pre-check (ADR-013) guarantees current == expected.
 */
public class GitHubDiffSource implements DiffSource {

    private final GitHubClient client;

    public GitHubDiffSource(GitHubClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.GITHUB;
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
                DiffRefs.headOnly(pr.path("head").path("sha").asText("")),
                Author.of(user.path("id").asText(""), login, login),
                pr.path("html_url").asText(""));
    }

    @Override
    public Diff fetchDiff(RepoRef repo, long prId, String commit) {
        String diffText = client.getDiff(prPath(repo, prId));
        List<FilePatch> files = UnifiedDiffParser.parse(diffText);
        return new Diff(DiffRefs.headOnly(commit), files, false);
    }

    private String prPath(RepoRef repo, long prId) {
        return "/repos/" + repo.workspace() + "/" + repo.slug() + "/pulls/" + prId;
    }
}

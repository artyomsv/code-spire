package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.IdentitySource;
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
 * Bitbucket Cloud read adapter (SCM-MAPPING §2/§3). The /diff endpoint returns
 * raw unified diff text for the PR's CURRENT state — callers pass the commit
 * they expect and the returned Diff carries it; the worker's stale-run
 * pre-check (ADR-013) guarantees we only act when current == expected.
 */
public class BitbucketCloudDiffSource implements DiffSource, IdentitySource {

    private final BitbucketCloudClient client;

    public BitbucketCloudDiffSource(BitbucketCloudClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_CLOUD;
    }

    /** GET /2.0/user — the token owner (baseUrl already carries /2.0); {@code account_id} is the stable id. */
    @Override
    public Author whoami() {
        JsonNode user = client.getJson("/user");
        String username = user.path("username").asText(user.path("nickname").asText(""));
        return Author.of(user.path("account_id").asText(""), username, user.path("display_name").asText(username));
    }

    @Override
    public PullRequest fetchPullRequest(RepoRef repo, long prId) {
        JsonNode pr = client.getJson(prPath(repo, prId));
        JsonNode author = pr.path("author");
        return new PullRequest(
                repo,
                prId,
                pr.path("title").asText(""),
                pr.path("description").asText(""),
                pr.path("source").path("branch").path("name").asText(""),
                pr.path("destination").path("branch").path("name").asText(""),
                DiffRefs.headOnly(pr.path("source").path("commit").path("hash").asText("")),
                Author.of(author.path("account_id").asText(""),
                        author.path("nickname").asText(""),
                        author.path("display_name").asText("")),
                pr.path("links").path("html").path("href").asText(""));
    }

    @Override
    public Diff fetchDiff(RepoRef repo, long prId, String commit) {
        String diffText = client.getText(prPath(repo, prId) + "/diff");
        List<FilePatch> files = UnifiedDiffParser.parse(diffText);
        return new Diff(DiffRefs.headOnly(commit), files, false);
    }

    private String prPath(RepoRef repo, long prId) {
        return "/repositories/" + repo.workspace() + "/" + repo.slug() + "/pullrequests/" + prId;
    }
}

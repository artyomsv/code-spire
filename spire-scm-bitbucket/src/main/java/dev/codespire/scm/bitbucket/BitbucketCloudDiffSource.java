package dev.codespire.scm.bitbucket;

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

import java.util.List;

/**
 * Bitbucket Cloud read adapter (SCM-MAPPING §2/§3). The /diff endpoint returns
 * raw unified diff text for the PR's CURRENT state — callers pass the commit
 * they expect and the returned Diff carries it; the worker's stale-run
 * pre-check (ADR-013) guarantees we only act when current == expected.
 */
public class BitbucketCloudDiffSource implements DiffSource, IdentitySource {

    private static final System.Logger LOG = System.getLogger(BitbucketCloudDiffSource.class.getName());

    private final BitbucketCloudClient client;

    public BitbucketCloudDiffSource(BitbucketCloudClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_CLOUD;
    }

    @Override
    public String apiHost() {
        return client.apiHost(); // api.bitbucket.org, or a Data Center host — keyed per instance
    }

    /** GET /2.0/user — the token owner (baseUrl already carries /2.0); {@code account_id} is the stable id. */
    @Override
    public Author whoami() {
        JsonNode user = client.getJson("/user");
        String username = user.path("username").asText(user.path("nickname").asText(""));
        return Author.of(user.path("account_id").asText(""), username, user.path("display_name").asText(username));
    }

    /**
     * Validates a credential that can't identify a user — Bitbucket
     * workspace/project/repository access tokens act as a synthetic bot and CANNOT
     * call {@code /user} (they 401 "not supported for this endpoint"). Instead we
     * confirm the token can list the workspace's repositories, which is the exact
     * capability a review needs (read PRs/diffs). Throws the adapter's API
     * exception if the token can't reach the workspace.
     *
     * @param workspace the Bitbucket workspace slug the provider is scoped to
     */
    public void assertWorkspaceAccess(String workspace) {
        client.getJson("/repositories/" + workspace + "?pagelen=1");
    }

    /**
     * Bitbucket's variant of the neutral registration question: try {@code /user} first
     * (keeps auto-identity for App Passwords, API tokens and user PATs), and on failure
     * fall back to the workspace check above, because the token may be one of the
     * account-less kinds described there.
     *
     * <p>Only the adapter can know that, which is why the fallback lives here rather than
     * in the caller: nothing in the orchestrator has to recognise Bitbucket to get this
     * behaviour, and no other adapter inherits it.
     */
    @Override
    public Author whoamiOrValidate(String workspace) {
        try {
            return whoami();
        } catch (RuntimeException whoamiFailed) {
            try {
                assertWorkspaceAccess(workspace);
            } catch (RuntimeException workspaceFailed) {
                // The fallback's status/detail is the actionable one (bad scope, wrong
                // workspace slug), so log it — but rethrow the /user failure, so a
                // genuinely bad token surfaces as the auth error the caller asked about
                // instead of the fallback's secondary complaint.
                LOG.log(System.Logger.Level.WARNING,
                        "Workspace fallback validation failed for workspace '" + workspace + "'",
                        workspaceFailed);
                throw whoamiFailed;
            }
            return Author.of("", "", ""); // usable token, but no user account to name
        }
    }

    @Override
    public void assertRepoAccessible(RepoRef repo) {
        client.getJson("/repositories/" + repo.full());
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
                (pr.path("source").path("commit").path("hash").asText("")),
                Author.of(author.path("account_id").asText(""),
                        author.path("nickname").asText(""),
                        author.path("display_name").asText("")),
                pr.path("links").path("html").path("href").asText(""));
    }

    @Override
    public Diff fetchDiff(RepoRef repo, long prId, String commit) {
        String diffText = client.getText(prPath(repo, prId) + "/diff");
        List<FilePatch> files = UnifiedDiffParser.parse(diffText);
        return new Diff(commit, files, false);
    }

    /**
     * The reconciliation lens (prior head -> new head). Bitbucket's compare spec is
     * {@code source..destination} — {@code {head}..{base}} yields the changes reachable
     * from head that are not in base.
     */
    @Override
    public String fetchCompareDiff(RepoRef repo, String base, String head) {
        return client.getText("/repositories/" + repo.full() + "/diff/" + head + ".." + base);
    }

    /**
     * {@code GET /repositories/{workspace}/{slug}/src/{branch}/{path}} returns the file verbatim.
     *
     * <p>A 404 is the ordinary answer for a repository with no such file, so it yields null rather
     * than an exception — the port's contract. Anything else is a real failure and propagates.
     */
    @Override
    public String fetchTextFileOnBranch(RepoRef repo, String branch, String path) {
        try {
            return client.getText("/repositories/" + repo.full() + "/src/" + branch + "/" + path);
        } catch (BitbucketApiException e) {
            if (e.isNotFound()) {
                return null;
            }
            throw e;
        }
    }

    private String prPath(RepoRef repo, long prId) {
        return "/repositories/" + repo.workspace() + "/" + repo.slug() + "/pullrequests/" + prId;
    }
}

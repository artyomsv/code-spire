package dev.codespire.orchestrator.adapters;

import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;

/**
 * Obviously-synthetic PR source used when {@code spire.scm.provider=stub}, so the
 * manual-register endpoint works in stub mode. All values are self-labeling test
 * data. The orchestrator never fetches diffs, so that path is unsupported.
 */
public class StubDiffSource implements DiffSource {

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_CLOUD;
    }

    @Override
    public PullRequest fetchPullRequest(RepoRef repo, long prId) {
        return new PullRequest(repo, prId, "TEST: manually registered stub PR", "STUB description",
                "feature/TEST-stub", "main", DiffRefs.headOnly("cafe0000"),
                Author.of("TEST-account-id", "test-author", "TEST Author"),
                "https://example.invalid/stub/" + prId);
    }

    @Override
    public Diff fetchDiff(RepoRef repo, long prId, String commit) {
        throw new UnsupportedOperationException("orchestrator does not fetch diffs");
    }
}

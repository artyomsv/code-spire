package dev.codespire.contract.port;

import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;

/** SCM read adapter. Diffs are fetched on demand and never persisted (ADR-011). */
public interface DiffSource {

    ScmType type();

    PullRequest fetchPullRequest(RepoRef repo, long prId);

    /** Canonical diff for the given head commit. A 404 means the commit was force-pushed away -> treat as superseded. */
    Diff fetchDiff(RepoRef repo, long prId, String commit);
}
